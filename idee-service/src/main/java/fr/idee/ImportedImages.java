package fr.idee;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable, shared URL cache. Network requests never hold an import transaction open. */
@Service
public class ImportedImages {
    static final int MAX_BYTES=20*1024*1024;
    private final JdbcTemplate db;
    final Path directory;
    private final boolean enabled;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ImportedImages(JdbcTemplate db,@Value("${idee.images.directory:data/images}") String directory,
            @Value("${idee.images.enabled:true}") boolean enabled) {
        this.db=db; this.directory=Path.of(directory).toAbsolutePath().normalize(); this.enabled=enabled;
    }

    public Map<String,Object> status() {
        return Map.of("enabled",enabled,"counts",db.queryForList("SELECT state,count(*) AS count FROM idee_image_download GROUP BY state ORDER BY state"),
                "recentErrors",db.queryForList("SELECT source_url,attempts,next_attempt_at,last_error FROM idee_image_download WHERE last_error IS NOT NULL ORDER BY next_attempt_at DESC LIMIT 20"));
    }

    public Map<String,Object> enqueueAllMissing() {
        if (!enabled) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Téléchargement désactivé : activer IDEE_IMAGES_ENABLED et redémarrer l’API.");
        repairMissingFiles();
        int queued=db.update("""
            INSERT INTO idee_image_download(source_url)
            SELECT DISTINCT m.url FROM idee_media m JOIN idee_outing o ON o.id=m.outing_id
            WHERE o.source_name='datatourisme' AND m.url ~ '^https?://'
            ON CONFLICT(source_url) DO UPDATE SET state='pending',next_attempt_at=now()
            WHERE idee_image_download.state IN ('pending','retrying')
            """);
        return Map.of("queued",queued,"statusUrl","/api/admin/images/status");
    }

    @Scheduled(initialDelay=30000,fixedDelay=5000,scheduler="imageScheduler")
    public void tick() {
        if (!enabled) return;
        for (int i=0;i<10;i++) if (!processNext()) break;
    }

    @Scheduled(initialDelay=15000,fixedDelay=3600000,scheduler="imageScheduler")
    public void repairMissingFiles() {
        if (!enabled) return;
        for (var row:db.queryForList("""
            SELECT d.source_url,d.local_url FROM idee_image_download d WHERE d.state='ready'
            ORDER BY EXISTS(SELECT 1 FROM idee_media m WHERE m.url=d.source_url) DESC,d.source_url
            """)) {
            String local=(String)row.get("local_url");
            String filename=local==null ? "" : local.substring(local.lastIndexOf('/')+1);
            if (filename.matches("[a-f0-9]{64}\\.(jpg|png|gif|webp|avif)")) {
                try {
                    String named=registerFilename(ImageNames.hash(filename),filename.substring(64),(String)row.get("source_url"));
                    Path old=directory.resolve(filename), target=directory.resolve(named);
                    if (Files.isRegularFile(old) && !Files.exists(target))
                        Files.move(old,target,StandardCopyOption.ATOMIC_MOVE);
                    if (Files.isRegularFile(target)) {
                        db.update("UPDATE idee_image_download SET local_url=? WHERE source_url=? AND local_url=? AND state='ready'",
                                "/api/media/images/"+named,row.get("source_url"),local);
                        filename=named;
                    }
                } catch (IOException error) {
                    LoggerFactory.getLogger(getClass()).warn("Renommage image : {}, reprise au prochain contrôle",error.getClass().getSimpleName());
                }
            }
            if (!ImageNames.valid(filename) || !Files.isRegularFile(directory.resolve(filename)))
                db.update("UPDATE idee_image_download SET state='pending',local_url=NULL,next_attempt_at=now() WHERE source_url=? AND state='ready'",row.get("source_url"));
        }
    }

    boolean processNext() {
        // Lease + attempt number allow concurrent API/cron workers and recovery after a restart.
        var jobs=db.queryForList("""
            UPDATE idee_image_download SET state='downloading',attempts=attempts+1,next_attempt_at=now()+interval '10 minutes'
            WHERE source_url=(SELECT source_url FROM idee_image_download
              WHERE state<>'ready' AND next_attempt_at<=now() ORDER BY next_attempt_at,source_url
              LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING source_url,attempts
            """);
        if (jobs.isEmpty()) return false;
        String url=(String)jobs.getFirst().get("source_url");
        int attempt=((Number)jobs.getFirst().get("attempts")).intValue();
        try {
            byte[] bytes=download(URI.create(url));
            String extension=imageExtension(bytes);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String filename=registerFilename(hash,extension,url);
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,"image-",".part");
            try {
                Files.write(temp,bytes);
                Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                Files.move(temp,directory.resolve(filename),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
            db.update("""
                UPDATE idee_image_download SET state='ready',local_url=?,downloaded_at=now(),last_error=NULL
                WHERE source_url=? AND attempts=?
                ""","/api/media/images/"+filename,url,attempt);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // No remote bodies or credentials in error messages/logs.
            String error=e instanceof ImageFailure ? e.getMessage() : e.getClass().getSimpleName();
            db.update("""
                UPDATE idee_image_download SET state='retrying',last_error=?,next_attempt_at=now()+(? * interval '1 second')
                WHERE source_url=? AND attempts=?
                """,error,Math.min(86400L,300L*(1L<<Math.min(attempt-1,9))),url,attempt);
            LoggerFactory.getLogger(getClass()).warn("Image importée : {}, nouvelle tentative planifiée",error);
        }
        return true;
    }

    private String registerFilename(String hash,String extension,String sourceUrl) {
        var titles=db.queryForList("""
            SELECT o.title,p.city FROM idee_media m JOIN idee_outing o ON o.id=m.outing_id
            LEFT JOIN idee_place p ON p.id=o.place_id WHERE m.url=? ORDER BY o.id,m.position,m.id LIMIT 1
            """,sourceUrl);
        String title=titles.isEmpty()?null:(String)titles.getFirst().get("title");
        String city=titles.isEmpty()?null:(String)titles.getFirst().get("city");
        db.update("INSERT INTO idee_image_file(content_hash,filename) VALUES(?,?) ON CONFLICT DO NOTHING",
                hash,ImageNames.descriptive(title,city,hash.substring(0,16),extension));
        // Keep the full digest in the registry; fall back to it for the rare short-name collision.
        if (db.queryForObject("SELECT count(*) FROM idee_image_file WHERE content_hash=?",Integer.class,hash)==0)
            db.update("INSERT INTO idee_image_file(content_hash,filename) VALUES(?,?) ON CONFLICT(content_hash) DO NOTHING",
                    hash,ImageNames.descriptive(title,city,hash,extension));
        return db.queryForObject("SELECT filename FROM idee_image_file WHERE content_hash=?",String.class,hash);
    }

    String storedFilename(String requested) {
        boolean legacy=requested.matches("[a-f0-9]{64}\\.(jpg|png|gif|webp|avif)");
        var names=legacy ? db.queryForList("SELECT filename FROM idee_image_file WHERE content_hash=?",ImageNames.hash(requested))
                : db.queryForList("SELECT filename FROM idee_image_file WHERE filename=?",requested);
        String stored=names.isEmpty()?requested:(String)names.getFirst().get("filename");
        if (!stored.endsWith(requested.substring(requested.lastIndexOf('.')))) return null;
        // Only the canonical descriptive name and its historic hash-only alias are accepted.
        if (!requested.equals(stored) && !requested.matches("[a-f0-9]{64}\\.(jpg|png|gif|webp|avif)")) return null;
        if (Files.isRegularFile(directory.resolve(stored))) return stored;
        if (requested.matches("[a-f0-9]{64}\\.(jpg|png|gif|webp|avif)") && Files.isRegularFile(directory.resolve(requested))) return requested;
        return null;
    }

    byte[] download(URI start) throws IOException,InterruptedException {
        URI uri=start;
        for (int redirects=0;redirects<=5;redirects++) {
            validateUrl(uri);
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                    .header("Accept","image/jpeg,image/png,image/webp,image/gif,image/avif")
                    .GET().build(),info -> {
                        if (info.statusCode()!=200) return HttpResponse.BodySubscribers.replacing(new byte[0]);
                        return limitedBody(MAX_BYTES);
                    });
            if (Set.of(301,302,303,307,308).contains(response.statusCode())) {
                String location=response.headers().firstValue("Location").orElseThrow(()->new ImageFailure("Redirection sans destination"));
                uri=uri.resolve(location); continue;
            }
            if (response.statusCode()!=200) throw new ImageFailure("HTTP "+response.statusCode());
            imageExtension(response.body());
            return response.body();
        }
        throw new ImageFailure("Trop de redirections");
    }

    void validateUrl(URI uri) throws UnknownHostException {
        if (!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"")) || uri.getHost()==null
                || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=80 && uri.getPort()!=443))
            throw new ImageFailure("Adresse image non autorisée");
        for (InetAddress address:InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes=address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || bytes.length==16 && (bytes[0]&0xfe)==0xfc
                    || bytes.length==4 && ((bytes[0]&255)==0 || (bytes[0]&255)==100 && (bytes[1]&255)>=64 && (bytes[1]&255)<=127))
                throw new ImageFailure("Adresse réseau privée non autorisée");
        }
    }

    static HttpResponse.BodySubscriber<byte[]> limitedBody(int limit) {
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            Flow.Subscription subscription; long size;
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription s) { subscription=s; delegate.onSubscribe(s); }
            public void onNext(List<ByteBuffer> buffers) {
                for (var b:buffers) size+=b.remaining();
                if (size>limit) { subscription.cancel(); delegate.onError(new ImageFailure("Image trop volumineuse")); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        };
    }

    static String imageExtension(byte[] bytes) {
        if (bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) return ".jpg";
        if (bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return ".png";
        if (bytes.length>=6 && (ascii(bytes,0,6).equals("GIF87a") || ascii(bytes,0,6).equals("GIF89a"))) return ".gif";
        if (bytes.length>=12 && ascii(bytes,0,4).equals("RIFF") && ascii(bytes,8,4).equals("WEBP")) return ".webp";
        if (bytes.length>=16 && ascii(bytes,4,4).equals("ftyp") && Set.of("avif","avis").contains(ascii(bytes,8,4))) return ".avif";
        throw new ImageFailure("Format image non pris en charge");
    }
    private static String ascii(byte[] bytes,int offset,int length) {
        return new String(bytes,offset,length,java.nio.charset.StandardCharsets.US_ASCII);
    }
    static class ImageFailure extends RuntimeException { ImageFailure(String message) { super(message); } }
}
