package fr.idee;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST", matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false", "idee.mistral.auto-enabled=false",
        "idee.datatourisme.enabled=false", "idee.jobs.scheduled-enabled=false", "idee.calendar-refresh-enabled=false"})
class DailyImportJobDatabaseTest {
    @Autowired JdbcTemplate db;

    @Test void waitsForUnfinishedBatchesEvenWhenThereAreNoQueuedOutings() {
        assertTrue(db.queryForObject("SELECT current_schema()", String.class).startsWith("idee_datatourisme_test_"));
        var job = new DailyImportJob(mock(DatatourismeImporter.class), mock(OutingDescriptionWorker.class),
                mock(OutingTranslationWorker.class), db);
        db.update("UPDATE idee_datatourisme_state SET sync_requested=false,next_url=NULL,completed_at=now()");
        assertTrue(job.step().complete());
        UUID french = UUID.randomUUID(), translated = UUID.randomUUID();
        db.update("INSERT INTO idee_description_batch(id,state,model,prompt_version,payload,total,next_poll_at) VALUES (?,'running','test',1,'[]',1,now()+interval '1 day')", french);
        assertFalse(job.step().complete());
        assertEquals(1, job.step().descriptions());
        db.update("UPDATE idee_description_batch SET completed_at=now() WHERE id=?", french);
        db.update("INSERT INTO idee_translation_batch(id,state,model,prompt_version,payload,total,next_poll_at) VALUES (?,'running','test',1,'test',1,now()+interval '1 day')", translated);
        assertFalse(job.step().complete());
        assertEquals(1, job.step().translations());
        db.update("UPDATE idee_translation_batch SET completed_at=now() WHERE id=?", translated);
        assertTrue(job.step().complete());
    }
}
