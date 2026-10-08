package fr.idee;

import org.springframework.jdbc.core.JdbcTemplate;

/** One driver for the daily import and both persistent provider queues. */
final class DailyImportJob {
    static final String IMPORTS = "SELECT count(*) FROM idee_datatourisme_state WHERE sync_requested OR next_url IS NOT NULL OR completed_at IS NULL";
    static final String DESCRIPTIONS = "SELECT (SELECT count(*) FROM idee_outing_description_job) + (SELECT count(*) FROM idee_description_batch WHERE completed_at IS NULL)";
    static final String TRANSLATIONS = "SELECT (SELECT count(*) FROM idee_outing_translation_job) + (SELECT count(*) FROM idee_translation_batch WHERE completed_at IS NULL)";

    private final DatatourismeImporter importer;
    private final OutingDescriptionWorker descriptions;
    private final OutingTranslationWorker translations;
    private final JdbcTemplate db;

    DailyImportJob(DatatourismeImporter importer, OutingDescriptionWorker descriptions,
                   OutingTranslationWorker translations, JdbcTemplate db) {
        this.importer = importer;
        this.descriptions = descriptions;
        this.translations = translations;
        this.db = db;
    }

    Progress step() {
        importer.tick();
        descriptions.processNext();
        translations.processNext();
        return new Progress(db.queryForObject(IMPORTS, Long.class),
                db.queryForObject(DESCRIPTIONS, Long.class), db.queryForObject(TRANSLATIONS, Long.class));
    }

    // Counts include queued jobs and unfinished batches, including deferred retries.
    // They describe remaining work units, not distinct outings.
    record Progress(long imports, long descriptions, long translations) {
        boolean complete() { return imports == 0 && descriptions == 0 && translations == 0; }
    }
}
