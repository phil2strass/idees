package fr.idee;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyImportJobTest {
    private final DatatourismeImporter importer = mock(DatatourismeImporter.class);
    private final OutingDescriptionWorker descriptions = mock(OutingDescriptionWorker.class);
    private final OutingTranslationWorker translations = mock(OutingTranslationWorker.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final DailyImportJob job = new DailyImportJob(importer, descriptions, translations, db);

    private void remaining(long imports, long french, long translated) {
        when(db.queryForObject(DailyImportJob.IMPORTS, Long.class)).thenReturn(imports);
        when(db.queryForObject(DailyImportJob.DESCRIPTIONS, Long.class)).thenReturn(french);
        when(db.queryForObject(DailyImportJob.TRANSLATIONS, Long.class)).thenReturn(translated);
    }

    @Test void completedImportDoesNotStopPendingProviderWork() {
        remaining(0, 1, 0);
        assertFalse(job.step().complete());
        remaining(0, 0, 5);
        assertFalse(job.step().complete());
        remaining(1, 0, 0);
        assertFalse(job.step().complete());
        remaining(0, 0, 0);
        assertTrue(job.step().complete());
    }

    @Test void collectsFrenchResultsBeforeProcessingTranslations() {
        remaining(0, 0, 0);
        job.step();
        var order = inOrder(importer, descriptions, translations, db);
        order.verify(importer).tick();
        order.verify(descriptions).processNext();
        order.verify(translations).processNext();
        order.verify(db).queryForObject(DailyImportJob.IMPORTS, Long.class);
    }

    @Test void dailyDriverDoesNotStartAnotherImportOnEachStep() {
        remaining(1, 0, 0);
        job.step();
        job.step();
        verify(importer, never()).requestSync();
    }
}
