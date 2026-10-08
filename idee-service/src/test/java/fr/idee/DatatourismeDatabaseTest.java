package fr.idee;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false","idee.mistral.auto-enabled=false","idee.datatourisme.enabled=false","server.port=0"})
class DatatourismeDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired DatatourismeMapper mapper;
    @Autowired OutingSourceDetails sourceDetails;
    @Autowired PlatformTransactionManager manager;
    @Test void committedCursorUpsertRollbackAndFullReconciliation() throws Exception {
        // Refuse to mutate the normal application schema even if the opt-in was set accidentally.
        assertTrue(db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_"));
        AtomicReference<JsonNode> response=new AtomicReference<>();
        List<String> urls=new ArrayList<>();
        var importer=new DatatourismeImporter(db,json,mapper,manager,"test-key",true) {
            @Override JsonNode fetch(URI uri) { urls.add(uri.toString()); return response.get(); }
        };
        String uuid="000c8ab9-8f93-34eb-86b0-5356e3594e7c";
        String event="""
            {"uuid":"%s","label":{"@fr":"Exposition","@en":"Exhibition"},
             "identifier":"LOCAL-42","hasOrganizationIdentifier":5,"creationDate":"2025-01-02","lastUpdate":"2026-09-24","lastUpdateDatatourisme":"2026-09-24T13:00:00Z",
             "hasDescription":[{"description":{"@fr":"Description française","@en":"English description"}}],
             "hasContact":[{"legalName":"Accueil","telephone":["01","02","01"],"homepage":["https://example.org"]}],
             "hasBeenCreatedBy":{"legalName":"Producteur"},"hasBeenPublishedBy":[{"legalName":"Éditeur"}],
             "hasTheme":[{"key":"Christmas","label":{"@fr":"Noël","@en":"Christmas"}}],"type":["Exhibition"],
             "isLocatedAt":[{"address":[{"addressLocality":"Erstein","hasAddressCity":{"insee":"67130","isPartOfDepartment":{"insee":"67"}}},{"addressLocality":"Autre adresse"}]}],
             "hasMainRepresentation":[{"hasAnnotation":[{"credits":["Photographe"]}],"hasRelatedResource":[{"locator":["https://example.org/photo.jpg"]}]}],
             "hasRepresentation":[{"hasRelatedResource":[{"locator":["https://example.org/photo.jpg","https://example.org/second.png","https://example.org/programme.pdf"]}]}],
             "takesPlaceAt":[{"startDate":"2026-09-25","endDate":"2026-09-27","startTime":"10:00"},{"startDate":"invalid"}]}
            """.formatted(uuid);
        String cursor=DatatourismeImporter.ENDPOINT+"?crs=opaque%2Bvalue%3D";
        response.set(json.readTree("{\"objects\":["+event+"],\"meta\":{\"next\":\""+cursor+"\"}}"));
        db.update("UPDATE idee_datatourisme_state SET next_url='https://api.datatourisme.fr/v1/entertainmentAndEvent?fields=uuid',completed_at=now(),full_completed_at=now() WHERE department='67'");
        importer.tick();
        assertTrue(urls.getFirst().contains("fields=*")); assertFalse(urls.getFirst().contains("update="));
        assertEquals(1,count("idee_datatourisme_event")); assertEquals(2,count("idee_datatourisme_period"));
        assertEquals("Photographe",db.queryForObject("SELECT credit FROM idee_media WHERE is_primary",String.class));
        assertEquals(2,count("idee_media")); assertEquals(4,count("idee_datatourisme_resource"));
        assertEquals(2,count("idee_datatourisme_translation"));
        assertEquals("Exhibition",db.queryForObject("SELECT title FROM idee_datatourisme_translation WHERE language='en'",String.class));
        assertEquals(3,count("idee_datatourisme_contact")); assertEquals(3,count("idee_datatourisme_contact_channel"));
        assertEquals(2,count("idee_datatourisme_location")); assertEquals(2,count("idee_datatourisme_term"));
        assertEquals("LOCAL-42",db.queryForObject("SELECT source_identifier FROM idee_datatourisme_event",String.class));
        assertEquals("5",db.queryForObject("SELECT organization_identifier FROM idee_datatourisme_event",String.class));
        assertFalse(db.queryForObject("SELECT end_time_known FROM idee_schedule",Boolean.class));
        long outing=db.queryForObject("SELECT outing_id FROM idee_datatourisme_event",Long.class);
        assertEquals("Description française",db.queryForObject("SELECT source_description FROM idee_outing_description_job WHERE outing_id=?",String.class,outing));
        var rewritingCalls=new java.util.concurrent.atomic.AtomicInteger();
        var mistral=new MistralDescriptions(json,"fake","test-model") {
            @Override public Texts rewrite(String source) {
                rewritingCalls.incrementAndGet(); return new Texts("Présentation française","Résumé français");
            }
        };
        new OutingDescriptions(db,mistral,manager).generateFrench("datatourisme-"+uuid);
        assertEquals(0,count("idee_outing_description_job"));
        String publicPath=db.queryForObject("SELECT path FROM idee_public_route WHERE outing_id=? AND canonical AND language='fr'",String.class,outing);
        assertEquals("/bas-rhin/erstein/exposition",publicPath);
        assertEquals("67130",db.queryForObject("SELECT c.insee_code FROM idee_city c JOIN idee_place p ON p.city_id=c.id JOIN idee_outing o ON o.place_id=p.id WHERE o.id=?",String.class,outing));
        var publicDetails=sourceDetails.forOuting(outing);
        assertEquals("LOCAL-42",publicDetails.get("reference"));
        assertEquals("2026-09-24",publicDetails.get("updatedOn"));
        assertFalse(publicDetails.containsKey("payload")); assertFalse(publicDetails.containsKey("uuid"));
        assertEquals(2,((List<?>)publicDetails.get("translations")).size());
        assertEquals(1,((List<?>)publicDetails.get("resources")).size());
        assertEquals(2,((List<?>)publicDetails.get("locations")).size());
        assertEquals(1,((List<?>)publicDetails.get("terms")).size());
        for (Object contact:(List<?>)publicDetails.get("contacts")) {
            assertEquals(Set.of("role","name","channels"),((Map<?,?>)contact).keySet());
        }
        assertNull(sourceDetails.forOuting(-1L));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM idee_datatourisme_period WHERE parse_error IS NOT NULL",Long.class));
        assertEquals(cursor,db.queryForObject("SELECT next_url FROM idee_datatourisme_state WHERE department='67'",String.class));
        // A partially processed page must roll back both records and the checkpoint.
        response.set(json.readTree("{\"objects\":["+event.replace("Exposition","Changed")+",{}],\"meta\":{\"next\":null}}"));
        ready(); importer.tick();
        assertEquals("Exposition",db.queryForObject("SELECT title FROM idee_outing WHERE id=?",String.class,outing));
        assertEquals(cursor,db.queryForObject("SELECT next_url FROM idee_datatourisme_state WHERE department='67'",String.class));
        assertEquals(cursor,urls.getLast());
        assertTrue(db.queryForObject("SELECT next_request_at>now() FROM idee_datatourisme_quota",Boolean.class));
        // Replay/upsert keeps the same outing and replaces periods instead of duplicating them.
        response.set(json.readTree("{\"objects\":["+event.replace("Exposition","Updated")+"],\"meta\":{\"next\":null}}"));
        ready(); importer.tick();
        assertEquals(outing,db.queryForObject("SELECT outing_id FROM idee_datatourisme_event",Long.class));
        assertEquals(2,count("idee_datatourisme_period")); assertEquals(1,count("idee_place"));
        assertEquals(publicPath,db.queryForObject("SELECT path FROM idee_public_route WHERE outing_id=? AND canonical AND language='fr'",String.class,outing));
        assertEquals(3,count("idee_datatourisme_contact_channel")); assertEquals(2,count("idee_media"));
        assertEquals("Updated",db.queryForObject("SELECT title FROM idee_outing WHERE id=?",String.class,outing));
        assertEquals(0,count("idee_outing_description_job"));
        assertEquals(1,rewritingCalls.get());
        // The second department is visited only after the first has finished.
        response.set(json.readTree("{\"objects\":[],\"meta\":{\"next\":null}}"));
        ready(); importer.tick(); assertTrue(urls.getLast().contains("department=68"));
        // An incremental empty response is not evidence of deletion.
        db.update("UPDATE idee_datatourisme_state SET completed_at=now()-interval '2 days' WHERE department='67'");
        ready(); importer.tick(); assertTrue(urls.getLast().contains("update="));
        assertEquals("published",db.queryForObject("SELECT status FROM idee_outing WHERE id=?",String.class,outing));
        // Manual requests preserve the watermark and any in-progress cursor.
        var previousStart=db.queryForObject("SELECT started_at FROM idee_datatourisme_state WHERE department='67'",java.sql.Timestamp.class);
        assertEquals(2,importer.requestSync());
        assertEquals(0,importer.requestSync());
        assertEquals(previousStart,db.queryForObject("SELECT started_at FROM idee_datatourisme_state WHERE department='67'",java.sql.Timestamp.class));
        String newEvent=event.replace(uuid,"100c8ab9-8f93-34eb-86b0-5356e3594e7c").replace("Exposition","Nouvelle sortie");
        response.set(json.readTree("{\"objects\":["+event.replace("Exposition","Actualisée").replace("Description française","Description actualisée")+","+newEvent+"],\"meta\":{\"next\":\""+cursor+"\"}}"));
        ready(); importer.tick();
        assertTrue(urls.getLast().contains("update="+previousStart.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().minusDays(2)));
        assertEquals(2,count("idee_datatourisme_event"));
        assertEquals("Actualisée",db.queryForObject("SELECT title FROM idee_outing WHERE id=?",String.class,outing));
        assertEquals(2,count("idee_outing_description_job"));
        assertEquals("Description actualisée",db.queryForObject("SELECT source_description FROM idee_outing_description_job WHERE outing_id=?",String.class,outing));
        assertEquals(0,importer.requestSync());
        assertEquals(cursor,db.queryForObject("SELECT next_url FROM idee_datatourisme_state WHERE department='67'",String.class));
        response.set(json.readTree("{\"objects\":[],\"meta\":{\"next\":null}}"));
        ready(); importer.tick(); assertEquals(cursor,urls.getLast());
        ready(); importer.tick(); assertTrue(urls.getLast().contains("department=68"));
        // A completed full traversal can archive a missing item, preserving its source payload.
        db.update("UPDATE idee_datatourisme_state SET completed_at=now()-interval '2 days',full_completed_at=now()-interval '31 days' WHERE department='67'");
        ready(); importer.tick(); assertFalse(urls.getLast().contains("update="));
        assertEquals("archived",db.queryForObject("SELECT status FROM idee_outing WHERE id=?",String.class,outing));
        assertEquals(2,count("idee_datatourisme_event"));
        var reduced=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(event);
        reduced.remove("hasContact"); reduced.remove("hasRepresentation");
        ((com.fasterxml.jackson.databind.node.ObjectNode)reduced.get("label")).remove("@en");
        reduced.remove("hasDescription");
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s->mapper.upsert(reduced,"67",UUID.randomUUID()));
        assertEquals(3,count("idee_datatourisme_contact_channel")); assertEquals(5,count("idee_datatourisme_contact"));
        assertEquals(3,count("idee_datatourisme_translation")); assertEquals(3,count("idee_media"));
        assertEquals(5,count("idee_datatourisme_resource"));
        assertNull(db.queryForObject("SELECT website FROM idee_outing WHERE id=?",String.class,outing));
    }
    private void ready() { db.update("UPDATE idee_datatourisme_quota SET next_request_at=now()-interval '1 second'"); }
    private long count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Long.class); }
}
