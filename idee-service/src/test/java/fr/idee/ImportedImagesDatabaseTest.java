package fr.idee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.images.enabled=false","idee.openai.translations-enabled=false","idee.mistral.auto-enabled=false","idee.datatourisme.enabled=false","idee.calendar-refresh-enabled=false"})
class ImportedImagesDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired DatatourismeMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired CatalogController catalog;
    @Autowired PlatformTransactionManager manager;
    @TempDir Path directory;

    @Test void importQueuesOnceRetriesAndPublishesLocalImagesWithCredits() throws Exception {
        assertTrue(db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_"));
        var event=json.readTree("""
            {"uuid":"11111111-2222-3333-4444-555555555555","label":{"@fr":"Image locale"},
             "hasMainRepresentation":[{"hasAnnotation":[{"credits":"Photo test","rights":"Licence test"}],
              "hasRelatedResource":[{"locator":"https://example.org/photo.png"}]}]}
            """);
        var tx=new TransactionTemplate(manager);
        long outing=tx.execute(s->mapper.upsert(event,"67",UUID.randomUUID()));
        tx.executeWithoutResult(s->mapper.upsert(event,"67",UUID.randomUUID()));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM idee_image_download",Integer.class));
        String slug=db.queryForObject("SELECT slug FROM idee_outing WHERE id=?",String.class,outing);
        assertEquals("https://example.org/photo.png",firstImage(slug).get("url"));
        var calls=new AtomicInteger();
        var images=new ImportedImages(db,directory.toString(),true) {
            @Override byte[] download(URI uri) {
                if (calls.incrementAndGet()==1) throw new ImageFailure("HTTP 503");
                return ImportedImagesTest.PNG;
            }
        };
        assertEquals(1,images.enqueueAllMissing().get("queued"));
        assertTrue(images.processNext());
        assertEquals("retrying",db.queryForObject("SELECT state FROM idee_image_download",String.class));
        assertFalse(images.processNext(),"Backoff is respected");
        assertEquals(1,images.enqueueAllMissing().get("queued"),"Explicit launch retries failures immediately");
        assertTrue(images.processNext());
        String local=(String)firstImage(slug).get("url");
        assertTrue(local.matches("/api/media/images/image-locale-[a-f0-9]{16}\\.png"));
        assertEquals("Photo test",firstImage(slug).get("credit"));
        assertEquals("Licence test",firstImage(slug).get("license"));
        var response=new ImportedImageController(images).image(local.substring(local.lastIndexOf('/')+1));
        assertArrayEquals(ImportedImagesTest.PNG,response.getBody().getInputStream().readAllBytes());
        assertEquals("public, max-age=31536000, immutable",response.getHeaders().getCacheControl());
        tx.executeWithoutResult(s->mapper.upsert(event,"67",UUID.randomUUID()));
        assertFalse(images.processNext(),"Reimport reuses the downloaded image");
        assertEquals(2,calls.get());
        assertEquals(local,firstImage(slug).get("url"));
        assertEquals(0,images.enqueueAllMissing().get("queued"),"Downloaded files are preserved");
        assertEquals("https://example.org/photo.png",db.queryForObject("SELECT url FROM idee_media WHERE outing_id=?",String.class,outing));
        var newEvent=event.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)newEvent.at("/hasMainRepresentation/0/hasRelatedResource/0")).put("locator","https://example.org/new.png");
        tx.executeWithoutResult(s->mapper.upsert(newEvent,"67",UUID.randomUUID()));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM idee_image_download",Integer.class));
        assertTrue(images.processNext());
        assertEquals(local,firstImage(slug).get("url"),"Identical file bytes share the same local file");
        try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
        var rollback=event.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)rollback.at("/hasMainRepresentation/0/hasRelatedResource/0")).put("locator","https://example.org/rollback.png");
        tx.executeWithoutResult(s->{mapper.upsert(rollback,"67",UUID.randomUUID()); s.setRollbackOnly();});
        assertEquals(2,db.queryForObject("SELECT count(*) FROM idee_image_download",Integer.class),"Queue rolls back with the import");
        String hash=db.queryForObject("SELECT content_hash FROM idee_image_file",String.class);
        String oldName=hash+".png";
        var controller=new ImportedImageController(images);
        assertEquals(301,controller.image(oldName).getStatusCode().value());
        assertEquals(local,controller.image(oldName).getHeaders().getLocation().toString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.image(hash+".jpg"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.image("invented-"+hash.substring(0,16)+".png"));
        // Simulate the pre-upgrade disk and database, then migrate without network traffic.
        Files.move(directory.resolve(local.substring(local.lastIndexOf('/')+1)),directory.resolve(oldName));
        db.update("UPDATE idee_image_download SET local_url=?","/api/media/images/"+oldName);
        db.update("DELETE FROM idee_image_file");
        int beforeMigration=calls.get();
        images.repairMissingFiles();
        assertEquals(beforeMigration,calls.get());
        assertFalse(Files.exists(directory.resolve(oldName)));
        assertEquals(local,firstImage(slug).get("url"));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM idee_image_download WHERE state='ready'",Integer.class));
        assertEquals(301,controller.image(oldName).getStatusCode().value());
        ((com.fasterxml.jackson.databind.node.ObjectNode)newEvent.get("label")).put("@fr","Nouveau titre");
        tx.executeWithoutResult(s->mapper.upsert(newEvent,"67",UUID.randomUUID()));
        assertEquals(local,firstImage(slug).get("url"),"A changed title does not rename an existing image");
        Files.delete(directory.resolve(local.substring(local.lastIndexOf('/')+1)));
        images.repairMissingFiles();
        assertEquals(2,db.queryForObject("SELECT count(*) FROM idee_image_download WHERE state='pending'",Integer.class));
        assertTrue(images.processNext(),"Missing files are downloaded again");
        assertTrue(Files.isRegularFile(directory.resolve(local.substring(local.lastIndexOf('/')+1))));
        // Force two registry digests to share a short prefix: no file may be overwritten.
        byte[] other=Arrays.copyOf(ImportedImagesTest.PNG,ImportedImagesTest.PNG.length+1);
        other[other.length-1]=42;
        String otherHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(other));
        db.update("INSERT INTO idee_image_file(content_hash,filename) VALUES(?,?)",
                otherHash.substring(0,16)+"b".repeat(48),ImageNames.descriptive("Nouveau titre",null,otherHash.substring(0,16),".png"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)newEvent.at("/hasMainRepresentation/0/hasRelatedResource/0")).put("locator","https://example.org/collision.png");
        tx.executeWithoutResult(s->mapper.upsert(newEvent,"67",UUID.randomUUID()));
        db.update("UPDATE idee_image_download SET next_attempt_at=now()+interval '1 day' WHERE source_url<>?","https://example.org/collision.png");
        var collision=new ImportedImages(db,directory.toString(),true) {
            @Override byte[] download(URI uri) { return other; }
        };
        assertTrue(collision.processNext());
        assertEquals("/api/media/images/nouveau-titre-"+otherHash+".png",firstImage(slug).get("url"));
        assertArrayEquals(ImportedImagesTest.PNG,Files.readAllBytes(directory.resolve(local.substring(local.lastIndexOf('/')+1))));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> firstImage(String slug) {
        return ((List<Map<String,Object>>)catalog.outing(slug).get("images")).getFirst();
    }
}
