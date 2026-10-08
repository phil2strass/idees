package fr.idee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false","idee.mistral.auto-enabled=false","idee.datatourisme.enabled=false","idee.calendar-refresh-enabled=false"})
class OutingDescriptionsDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired CatalogController catalog;
    @Test void sourceTranslationsSurviveAndOnlySelectedOutingIsGenerated() {
        assertTrue(db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_"));
        long id=db.queryForObject("INSERT INTO idee_outing(slug,title,summary,description,kind,status) VALUES('mistral-test','Titre','Résumé','Original','event','published') RETURNING id",Long.class);
        db.update("INSERT INTO idee_outing(slug,title,summary,description,kind,status) VALUES('untouched','Autre','Résumé','Autre description','event','published')");
        UUID uuid=UUID.randomUUID();
        db.update("INSERT INTO idee_datatourisme_event(uuid,outing_id,payload) VALUES(?,?,?::jsonb)",uuid,id,"{}");
        db.update("INSERT INTO idee_datatourisme_translation(uuid,language,description) VALUES(?,'fr','Texte français'),(?,'en','English source')",uuid,uuid);
        var calls=new AtomicInteger();
        var client=new MistralDescriptions(new ObjectMapper(),"fake","mistral-small-2603") {
            @Override public Texts rewrite(String source) {
                assertTrue(source.startsWith("Texte français")); calls.incrementAndGet();
                return new Texts("Présentation réécrite en français.","Résumé réécrit.");
            }
        };
        var service=new OutingDescriptions(db,client,manager);
        assertEquals(false,service.generateFrench("mistral-test").get("reused"));
        assertEquals(true,service.generateFrench("mistral-test").get("reused"));
        assertEquals(1,calls.get());
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM idee_outing_description",Long.class));
        assertEquals("Original",db.queryForObject("SELECT description FROM idee_outing WHERE id=?",String.class,id));
        assertEquals(2,service.forOuting(id).size());
        assertNull(service.forOuting(id).getFirst().get("description_longue")); // English has no generated text.
        assertEquals("Résumé réécrit.",catalog.outing("mistral-test").get("description_courte"));
        assertNotNull(catalog.outing("mistral-test").get("descriptions"));
        db.update("UPDATE idee_datatourisme_translation SET description='Texte français modifié' WHERE uuid=? AND language='fr'",uuid);
        assertNull(catalog.outing("mistral-test").get("description_courte"));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM idee_outing_description",Long.class));
        service.generateFrench("mistral-test"); assertEquals(2,calls.get());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("UPDATE idee_outing_description SET description_courte=? WHERE outing_id=?","a".repeat(301),id));
        db.update("DELETE FROM idee_datatourisme_translation WHERE uuid=? AND language='fr'",uuid);
        assertThrows(ResponseStatusException.class,()->service.generateFrench("mistral-test"));
        assertEquals(2,calls.get());
    }
}
