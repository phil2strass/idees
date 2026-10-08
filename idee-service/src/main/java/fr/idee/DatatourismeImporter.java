package fr.idee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DatatourismeImporter {
    static final String ENDPOINT = "https://api.datatourisme.fr/v1/entertainmentAndEvent";
    static final String FIELDS = "*";
    static final int SELECTION_VERSION = 1;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DatatourismeMapper mapper;
    private final TransactionTemplate transaction, independent;
    private final String key;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public DatatourismeImporter(JdbcTemplate db, ObjectMapper json, DatatourismeMapper mapper,
            PlatformTransactionManager manager, @Value("${idee.datatourisme.api-key:}") String key,
            @Value("${idee.datatourisme.enabled:false}") boolean enabled) {
        this.db=db; this.json=json; this.mapper=mapper; this.key=key; this.enabled=enabled;
        transaction=new TransactionTemplate(manager);
        independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isEnabled() { return enabled && !key.isBlank(); }

    // Persist the request; the scheduled worker keeps control of quota and pagination.
    public int requestSync() {
        if (!isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Import DATAtourisme desactive ou cle absente.");
        return transaction.execute(status -> {
            if (!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(67468007)",Boolean.class)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Une page est en cours de traitement. Reessayez dans quelques instants.");
            return db.update("""
                UPDATE idee_datatourisme_state SET sync_requested=true
                WHERE next_url IS NULL AND NOT sync_requested
                """);
        });
    }

    // One committed page per tick: restarts resume the exact cursor saved with the records.
    @Scheduled(initialDelay=30000, fixedDelay=5000)
    public void tick() {
        if (!enabled || key.isBlank()) return;
        String[] department = {null};
        UUID[] currentRun = {null};
        try {
            transaction.executeWithoutResult(status -> {
                if (!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(67468007)",Boolean.class))) return;
                if (!Boolean.TRUE.equals(db.queryForObject("SELECT next_request_at<=now() FROM idee_datatourisme_quota WHERE singleton",Boolean.class))) return;
                var states=db.queryForList("""
                    SELECT * FROM idee_datatourisme_state
                    WHERE sync_requested OR selection_version < 1 OR next_url IS NOT NULL OR completed_at IS NULL OR completed_at < now()-interval '1 day'
                    ORDER BY (next_url IS NOT NULL) DESC, department LIMIT 1
                    """);
                if (states.isEmpty()) return;
                var state=states.getFirst(); department[0]=(String)state.get("department");
                String dep=department[0], url=(String)state.get("next_url");
                boolean enrich=((Number)state.get("selection_version")).intValue()<SELECTION_VERSION;
                if (enrich) url=null; // An old cursor carries the old fields/lang selection.
                if (url==null) {
                    boolean full=enrich || state.get("full_completed_at")==null || instant(state.get("full_completed_at")).isBefore(Instant.now().minus(Duration.ofDays(30)));
                    // Watermark is the previous run START, with two days of overlap.
                    LocalDate update=full?null:instant(state.get("started_at")).atZone(ZoneOffset.UTC).toLocalDate().minusDays(2);
                    url=firstUrl(dep,update);
                    String initialUrl=url;
                    UUID newRun=UUID.randomUUID();
                    // Persist the start even if the first page fails; its retry keeps this run ID.
                    independent.executeWithoutResult(s -> {
                        db.update("INSERT INTO idee_import_run(id,department,mode) VALUES(?,?,?)",newRun,dep,full?"full":"update");
                        db.update("UPDATE idee_datatourisme_state SET next_url=?,run_id=?,mode=?,started_at=now(),pages=0,objects=0,last_error=NULL,selection_version=?,sync_requested=false WHERE department=?",
                            initialUrl,newRun,full?"full":"update",SELECTION_VERSION,dep);
                    });
                }
                UUID run=db.queryForObject("SELECT run_id FROM idee_datatourisme_state WHERE department=?",UUID.class,dep);
                currentRun[0]=run;
                // Also resume a traversal that started before the history migration.
                independent.executeWithoutResult(s -> db.update("""
                    INSERT INTO idee_import_run(id,department,mode,started_at)
                    SELECT run_id,department,mode,started_at FROM idee_datatourisme_state WHERE department=?
                    ON CONFLICT(id) DO NOTHING
                    """,dep));
                db.queryForObject("SELECT set_config('idee.import_run_id',?,true)",String.class,run.toString());
                URI uri=trustedUrl(url);
                // Commit the reservation even if HTTP or the page transaction fails.
                independent.executeWithoutResult(s -> db.update("UPDATE idee_datatourisme_quota SET next_request_at=now()+interval '4 seconds' WHERE singleton"));
                JsonNode page=fetch(uri);
                String next=nextUrl(page);
                if (url.equals(next)) throw new IllegalArgumentException("Repeated cursor");
                List<Long> changed=new ArrayList<>();
                for (JsonNode event:page.get("objects")) {
                    var previous=db.queryForList("""
                        SELECT o.id,e.payload IS DISTINCT FROM ?::jsonb AS changed
                        FROM idee_outing o LEFT JOIN idee_datatourisme_event e ON e.outing_id=o.id
                        WHERE o.source_name='datatourisme' AND o.external_id=?
                        """,event.toString(),event.path("uuid").asText());
                    long outing=mapper.upsert(event,dep,run);
                    boolean created=previous.isEmpty(),updated=!created && Boolean.TRUE.equals(previous.getFirst().get("changed"));
                    recordOuting(run,outing,created,updated);
                    changed.add(outing);
                }
                mapper.project(changed);
                db.update("UPDATE idee_datatourisme_state SET next_url=?,pages=pages+1,objects=objects+?,last_error=NULL WHERE department=?",next,page.get("objects").size(),dep);
                db.update("""
                    UPDATE idee_import_run SET pages=pages+1,objects=objects+?,last_processed_at=now(),
                      last_error=NULL,state=?,completed_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?
                    """,page.get("objects").size(),next==null?"completed":"running",next==null,run);
                if (next==null) {
                    String mode=db.queryForObject("SELECT mode FROM idee_datatourisme_state WHERE department=?",String.class,dep);
                    if ("full".equals(mode)) {
                        db.update("UPDATE idee_datatourisme_presence SET active=false WHERE department=? AND seen_run<>?",dep,run);
                        var archived=db.queryForList("""
                            UPDATE idee_outing o SET status='archived',updated_at=now()
                            FROM idee_datatourisme_event e WHERE e.outing_id=o.id
                            AND NOT EXISTS(SELECT 1 FROM idee_datatourisme_presence p WHERE p.uuid=e.uuid AND p.active)
                            AND o.status<>'archived' RETURNING o.id
                            """);
                        for(var outing:archived) recordOuting(run,((Number)outing.get("id")).longValue(),false,true);
                    }
                    db.update("UPDATE idee_datatourisme_state SET completed_at=now(),full_completed_at=CASE WHEN mode='full' THEN now() ELSE full_completed_at END WHERE department=?",dep);
                }
                LoggerFactory.getLogger(getClass()).info("DATAtourisme département {} : {} fiches, fin={}",dep,page.get("objects").size(),next==null);
            });
        } catch (Exception error) {
            // Never log HTTP bodies, credentials or cursor URLs.
            String message=error instanceof ApiFailure api?"HTTP "+api.status:error.getClass().getSimpleName();
            long delay=error instanceof ApiFailure api?api.delay:300;
            independent.executeWithoutResult(s -> {
                db.update("UPDATE idee_datatourisme_quota SET next_request_at=now()+(? * interval '1 second') WHERE singleton",delay);
                if (department[0]!=null) db.update("UPDATE idee_datatourisme_state SET last_error=? WHERE department=?",message,department[0]);
                if (currentRun[0]!=null) db.update("UPDATE idee_import_run SET state='retrying',last_error=? WHERE id=?",message,currentRun[0]);
            });
            LoggerFactory.getLogger(getClass()).warn("DATAtourisme : {}, reprise dans {} secondes",message,delay);
        }
    }

    private void recordOuting(UUID run,long outing,boolean created,boolean updated) {
        db.update("""
            INSERT INTO idee_import_run_outing(run_id,outing_id,new_outing,updated_outing) VALUES(?,?,?,?)
            ON CONFLICT(run_id,outing_id) DO UPDATE SET
              new_outing=idee_import_run_outing.new_outing OR excluded.new_outing,
              updated_outing=(idee_import_run_outing.updated_outing OR excluded.updated_outing)
                AND NOT (idee_import_run_outing.new_outing OR excluded.new_outing)
            """,run,outing,created,updated);
    }

    JsonNode fetch(URI uri) {
        try {
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("X-API-Key",key)
                    .header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new ApiFailure(response.statusCode(),retryDelay(response.statusCode(),response.headers().firstValue("Retry-After").orElse(null)));
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Import interrupted");
        } catch (java.io.IOException e) { throw new IllegalStateException("Import transport failed"); }
    }

    static long retryDelay(int status,String header) {
        long minimum=status==429?3600:300;
        if (header!=null) {
            try { return Math.max(minimum,Long.parseLong(header)); }
            catch (NumberFormatException ignored) {
                try { return Math.max(minimum,Duration.between(Instant.now(),ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()); }
                catch (java.time.format.DateTimeParseException ignoredDate) { }
            }
        }
        return minimum;
    }

    static String firstUrl(String department,LocalDate update) {
        var builder=UriComponentsBuilder.fromUriString(ENDPOINT).queryParam("department",department)
                .queryParam("page_size",250).queryParam("lang","*").queryParam("fields",FIELDS);
        if (update!=null) builder.queryParam("update",update);
        return builder.build().encode().toUriString();
    }

    static URI trustedUrl(String url) {
        URI uri=URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"api.datatourisme.fr".equals(uri.getHost())
                || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getUserInfo()!=null || uri.getFragment()!=null
                || !Set.of("/v1/entertainmentAndEvent","/v1/catalog").contains(uri.getPath()))
            throw new IllegalArgumentException("Untrusted pagination URL");
        return uri;
    }

    static String nextUrl(JsonNode page) {
        if (!page.path("objects").isArray() || !page.path("meta").isObject())
            throw new IllegalArgumentException("Invalid page shape");
        JsonNode meta=page.get("meta"),cursor=meta.get("next");
        // The live API omits next on its final page instead of sending JSON null.
        if (cursor==null) {
            boolean last=meta.path("page").isIntegralNumber() && meta.path("total_pages").isIntegralNumber()
                    && meta.path("page").asInt()>0 && meta.path("page").asInt()==meta.path("total_pages").asInt();
            boolean empty=page.get("objects").isEmpty() && meta.path("total").isIntegralNumber() && meta.path("total").asLong()==0;
            if (!last && !empty) throw new IllegalArgumentException("Missing cursor before final page");
            return null;
        }
        if (cursor.isNull()) return null;
        if (!cursor.isTextual() || cursor.asText().isBlank()) throw new IllegalArgumentException("Invalid cursor");
        return trustedUrl(cursor.asText()).toString();
    }

    private static Instant instant(Object value) { return ((java.sql.Timestamp)value).toInstant(); }
    private static final class ApiFailure extends RuntimeException {
        final int status; final long delay;
        ApiFailure(int status,long delay) { this.status=status; this.delay=delay; }
    }
}
