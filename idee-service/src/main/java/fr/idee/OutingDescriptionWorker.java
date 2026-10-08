package fr.idee;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutingDescriptionWorker {
    private final JdbcTemplate db;
    private final OutingDescriptionBatches batches;
    private final MistralDescriptions mistral;
    private final TransactionTemplate transaction;
    private final boolean automatic;

    public OutingDescriptionWorker(JdbcTemplate db,OutingDescriptions descriptions,MistralDescriptions mistral,
            PlatformTransactionManager manager,@Value("${idee.mistral.auto-enabled:true}") boolean automatic) {
        this.db=db; this.batches=new OutingDescriptionBatches(db,mistral,manager); this.mistral=mistral;
        this.transaction=new TransactionTemplate(manager); this.automatic=automatic;
    }

    public boolean isEnabled() { return automatic && mistral.isConfigured(); }

    @Scheduled(initialDelay=60000, fixedDelay=2000)
    public void tick() { processNext(); }

    public boolean processNext() {
        return isEnabled() && batches.processNext();
    }

    public long readyCount() {
        return db.queryForObject("SELECT count(*) FROM idee_outing_description_job WHERE batch_id IS NULL AND next_attempt_at<=now()",Long.class);
    }

    public Map<String,Object> enqueueMissingFrench(int limit) {
        if (limit<1 || limit>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La limite doit être comprise entre 1 et 500.");
        if (!isEnabled())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Le traitement automatique Mistral doit être activé avec une clé configurée.");
        return transaction.execute(status -> {
            // Separate from the generation lock: accepting a batch never waits for Mistral.
            if (!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(67468014)",Boolean.class)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Une demande de descriptions est en cours. Réessayez plus tard.");
            var added=db.queryForList("""
                WITH candidates AS (
                    SELECT o.id,s.description FROM idee_outing o
                    JOIN idee_outing_description_source s ON s.outing_id=o.id AND s.language='fr'
                    WHERE o.status='published' AND NOT o.is_demo
                      AND NOT EXISTS (SELECT 1 FROM idee_outing_description d
                          WHERE d.outing_id=o.id AND d.language='fr' AND d.source_description=s.description)
                      AND NOT EXISTS (SELECT 1 FROM idee_outing_description_job j WHERE j.outing_id=o.id)
                    ORDER BY o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                )
                INSERT INTO idee_outing_description_job(outing_id,source_description)
                SELECT id,description FROM candidates
                ON CONFLICT(outing_id) DO NOTHING RETURNING outing_id
                """,limit);
            return Map.<String,Object>of("accepted",added.size(),"limit",limit,"language","fr",
                "statusUrl","/api/admin/descriptions/status");
        });
    }

    public Map<String,Object> status() {
        var row=db.queryForMap("""
            SELECT count(*) AS pending,count(*) FILTER(WHERE batch_id IS NULL AND next_attempt_at<=now()) AS ready,
            count(*) FILTER(WHERE batch_id IS NOT NULL) AS "inBatch",
            count(*) FILTER(WHERE attempts>0) AS retrying,min(next_attempt_at) FILTER(WHERE batch_id IS NULL) AS "nextAttemptAt"
            FROM idee_outing_description_job
            """);
        row.put("mode","batch");
        row.put("activeBatches",db.queryForObject("SELECT count(*) FROM idee_description_batch WHERE completed_at IS NULL",Long.class));
        row.put("uncertainSubmissions",db.queryForObject("SELECT count(*) FROM idee_description_batch WHERE completed_at IS NULL AND provider_id IS NULL AND state='SUBMITTING'",Long.class));
        row.put("batches",db.queryForList("""
            SELECT id,provider_id AS "providerId",state,total,succeeded,failed,
              created_at AS "createdAt",completed_at AS "completedAt",next_poll_at AS "nextPollAt",last_error AS "lastError"
            FROM idee_description_batch ORDER BY created_at DESC LIMIT 20
            """));
        row.put("enabled",isEnabled()); row.put("configured",mistral.isConfigured());
        return row;
    }
}
