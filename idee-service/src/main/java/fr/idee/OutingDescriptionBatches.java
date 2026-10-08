package fr.idee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable batch submission and collection. No database transaction is held during HTTP. */
final class OutingDescriptionBatches {
    private final JdbcTemplate db;
    private final MistralDescriptions mistral;
    private final TransactionTemplate transaction;
    private final ObjectMapper json=new ObjectMapper();

    OutingDescriptionBatches(JdbcTemplate db,MistralDescriptions mistral,PlatformTransactionManager manager) {
        this.db=db; this.mistral=mistral; transaction=new TransactionTemplate(manager);
    }

    boolean processNext() {
        try {
            return Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection -> {
                try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_try_advisory_lock(67468013)")) {
                    result.next(); if (!result.getBoolean(1)) return false;
                }
                try {
                    var rows=db.queryForList("""
                        SELECT * FROM idee_description_batch WHERE completed_at IS NULL AND next_poll_at<=now()
                        ORDER BY next_poll_at,created_at LIMIT 1
                        """);
                    Map<String,Object> batch=rows.isEmpty()?prepare(500):rows.getFirst();
                    if (batch==null) return false;
                    advance(batch); return true;
                } finally {
                    try(var statement=connection.createStatement()) { statement.execute("SELECT pg_advisory_unlock(67468013)"); }
                }
            }));
        } catch(Exception error) {
            LoggerFactory.getLogger(getClass()).warn("Traitement batch Mistral différé : {}",error.getClass().getSimpleName());
            return false;
        }
    }

    UUID submitNext(int limit) {
        return locked(() -> {
            var batch=prepare(limit);
            if(batch==null) return null;
            advance(batch);
            return (UUID)batch.get("id");
        });
    }

    void collect(UUID id) {
        locked(() -> {
            var rows=db.queryForList("SELECT * FROM idee_description_batch WHERE id=? AND completed_at IS NULL AND next_poll_at<=now()",id);
            if(!rows.isEmpty()) advance(rows.getFirst());
            return null;
        });
    }

    private <T> T locked(java.util.function.Supplier<T> action) {
        return db.execute((ConnectionCallback<T>) connection -> {
            try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_try_advisory_lock(67468013)")) {
                result.next(); if(!result.getBoolean(1)) throw new IllegalStateException("Batch worker busy");
            }
            try { return action.get(); }
            finally { try(var statement=connection.createStatement()) { statement.execute("SELECT pg_advisory_unlock(67468013)"); } }
        });
    }

    private Map<String,Object> prepare(int limit) {
        if(limit<1 || limit>500) throw new IllegalArgumentException("Invalid batch size");
        return transaction.execute(status -> {
            var candidates=db.queryForList("""
                SELECT o.id FROM idee_outing_description_job j JOIN idee_outing o ON o.id=j.outing_id
                WHERE j.batch_id IS NULL AND j.next_attempt_at<=now()
                ORDER BY j.next_attempt_at,o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                """,limit);
            var items=new ArrayList<Map<String,Object>>();
            int characters=0;
            for(var candidate:candidates) {
                Object id=candidate.get("id");
                db.queryForList("SELECT idee_queue_outing_description(?)",id);
                var current=db.queryForList("SELECT outing_id,source_description,import_run_id FROM idee_outing_description_job WHERE outing_id=? AND batch_id IS NULL AND next_attempt_at<=now()",id);
                if (current.isEmpty()) continue;
                var item=current.getFirst(); String source=(String)item.get("source_description");
                if (source.length()>50000) {
                    retry(id,source,null,"source_too_long"); continue;
                }
                if (characters+source.length()>2000000) break;
                characters+=source.length(); items.add(item);
            }
            if (items.isEmpty()) return null;
            UUID id=UUID.randomUUID();
            String payload;
            try { payload=json.writeValueAsString(mistral.batchRequest(id,items)); }
            catch(java.io.IOException e) { throw new IllegalStateException("Invalid batch request"); }
            db.update("""
                INSERT INTO idee_description_batch(id,state,model,prompt_version,payload,total)
                VALUES(?,'PREPARED',?,?,?::jsonb,?)
                """,id,mistral.model,MistralDescriptions.PROMPT_VERSION,payload,items.size());
            for(var item:items) {
                db.update("INSERT INTO idee_description_batch_item(batch_id,outing_id,source_description,import_run_id) VALUES(?,?,?,?)",id,item.get("outing_id"),item.get("source_description"),item.get("import_run_id"));
                db.update("UPDATE idee_outing_description_job SET batch_id=? WHERE outing_id=?",id,item.get("outing_id"));
            }
            return db.queryForMap("SELECT * FROM idee_description_batch WHERE id=?",id);
        });
    }

    private void advance(Map<String,Object> batch) {
        UUID id=(UUID)batch.get("id");
        String state=(String)batch.get("state");
        try {
            JsonNode remote;
            if ("PREPARED".equals(state)) {
                // Commit intent before POST. A crash/timeout is reconciled using metadata,
                // never blindly resubmitted: the provider may already have accepted it.
                db.update("UPDATE idee_description_batch SET state='SUBMITTING',next_poll_at=now()+interval '1 minute' WHERE id=?",id);
                try { remote=mistral.createBatch(batch.get("payload").toString()); }
                catch(MistralDescriptions.BatchHttpException error) {
                    if (error.rejected()) db.update("UPDATE idee_description_batch SET state='PREPARED' WHERE id=?",id);
                    throw error;
                }
                saveProvider(id,remote);
                return;
            }
            if (batch.get("provider_id")==null) {
                remote=mistral.findBatch(id);
                if (remote==null) {
                    defer(id,"submission_unknown"); return;
                }
                saveProvider(id,remote);
                return;
            }
            remote=mistral.getBatch((String)batch.get("provider_id"));
            String remoteState=remote.path("status").asText();
            if (!Set.of("QUEUED","RUNNING","CANCELLATION_REQUESTED","SUCCESS","FAILED","TIMEOUT_EXCEEDED","CANCELLED").contains(remoteState))
                throw new IllegalArgumentException("Invalid batch state");
            db.update("UPDATE idee_description_batch SET state=?,next_poll_at=now()+interval '1 minute' WHERE id=?",remoteState,id);
            if (!Set.of("SUCCESS","FAILED","TIMEOUT_EXCEEDED","CANCELLED").contains(remoteState)) {
                db.update("UPDATE idee_description_batch SET last_error=NULL,attempts=0 WHERE id=?",id);
                return;
            }
            var results=new HashMap<String,JsonNode>();
            var duplicates=new HashSet<String>();
            List<JsonNode> outputs;
            try { outputs=mistral.batchOutputs(remote); }
            catch(MistralDescriptions.BatchHttpException error) {
                // A confirmed terminal batch may have expired output files. Retry its
                // remaining requests instead of polling a missing file indefinitely.
                if(error.status!=404) throw error;
                outputs=List.of();
            }
            for (var output:outputs) {
                String custom=output.path("custom_id").asText();
                if(results.putIfAbsent(custom,output)!=null) duplicates.add(custom);
            }
            for(var item:db.queryForList("SELECT * FROM idee_description_batch_item WHERE batch_id=? AND outcome='pending' ORDER BY outing_id",id)) {
                String custom=item.get("outing_id").toString();
                JsonNode output=duplicates.contains(custom)?null:results.get(custom);
                MistralDescriptions.Texts texts=null;
                if(output!=null && output.path("response").path("status_code").asInt()==200) {
                    try { texts=mistral.parse(output.path("response").path("body")); }
                    catch(java.io.IOException | IllegalArgumentException ignored) { /* retry only this request */ }
                }
                apply(batch,item,texts);
            }
            db.update("""
                UPDATE idee_description_batch SET completed_at=now(),payload='{}'::jsonb,last_error=NULL,attempts=0,
                  succeeded=(SELECT count(*) FROM idee_description_batch_item WHERE batch_id=? AND outcome IN ('applied','reused')),
                  failed=(SELECT count(*) FROM idee_description_batch_item WHERE batch_id=? AND outcome='failed')
                WHERE id=? AND NOT EXISTS(SELECT 1 FROM idee_description_batch_item WHERE batch_id=? AND outcome='pending')
                """,id,id,id,id);
        } catch(Exception error) {
            defer(id,error instanceof MistralDescriptions.BatchHttpException http?"batch_http_"+http.status:error.getClass().getSimpleName());
        }
    }

    private void saveProvider(UUID id,JsonNode remote) {
        String provider=remote.path("id").asText();
        if (!provider.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("Missing batch ID");
        db.update("UPDATE idee_description_batch SET provider_id=?,state='QUEUED',last_error=NULL,attempts=0,next_poll_at=now()+interval '1 minute' WHERE id=?",provider,id);
    }

    private void defer(UUID id,String reason) {
        db.update("""
            UPDATE idee_description_batch SET attempts=attempts+1,last_error=?,
            next_poll_at=now()+make_interval(secs => LEAST(3600,60*power(2,LEAST(attempts,6)))::int) WHERE id=?
            """,reason,id);
    }

    private void retry(Object outing,String source,UUID batch,String reason) {
        db.update("""
            UPDATE idee_outing_description_job SET batch_id=NULL,attempts=attempts+1,last_error=?,
              next_attempt_at=now()+make_interval(secs => LEAST(3600,30*power(2,LEAST(attempts,7)))::int)
            WHERE outing_id=? AND source_description=? AND batch_id IS NOT DISTINCT FROM ?::uuid
            """,reason,outing,source,batch);
    }

    private void apply(Map<String,Object> batch,Map<String,Object> item,MistralDescriptions.Texts texts) {
        UUID batchId=(UUID)batch.get("id"); Object outing=item.get("outing_id");
        String source=(String)item.get("source_description");
        try {
            transaction.executeWithoutResult(status -> {
                db.queryForObject("SELECT set_config('idee.import_run_id',?,true)",String.class,
                    item.get("import_run_id")==null?"":item.get("import_run_id").toString());
                var locked=db.queryForList("SELECT status,is_demo FROM idee_outing WHERE id=? FOR UPDATE NOWAIT",outing);
                if(locked.isEmpty()) return;
                var sources=db.queryForList("SELECT description FROM idee_outing_description_source WHERE outing_id=? AND language='fr'",outing);
                boolean current="published".equals(locked.getFirst().get("status")) && !Boolean.TRUE.equals(locked.getFirst().get("is_demo"))
                    && !sources.isEmpty() && source.equals(sources.getFirst().get("description"));
                String outcome="stale";
                if(current) {
                    boolean exists=db.queryForObject("SELECT EXISTS(SELECT 1 FROM idee_outing_description WHERE outing_id=? AND language='fr' AND source_description=?)",Boolean.class,outing,source);
                    if(exists) outcome="reused";
                    else if(texts!=null) {
                        db.update("""
                            INSERT INTO idee_outing_description(outing_id,language,source_description,description_longue,description_courte,model,prompt_version)
                            VALUES(?,'fr',?,?,?,?,?) ON CONFLICT(outing_id,language) DO UPDATE SET
                            source_description=excluded.source_description,description_longue=excluded.description_longue,
                            description_courte=excluded.description_courte,model=excluded.model,prompt_version=excluded.prompt_version,generated_at=now()
                            """,outing,source,texts.description_longue(),texts.description_courte(),batch.get("model"),batch.get("prompt_version"));
                        outcome="applied";
                    } else {
                        retry(outing,source,batchId,"batch_request_failed"); outcome="failed";
                    }
                }
                if(!outcome.equals("failed")) db.update("DELETE FROM idee_outing_description_job WHERE outing_id=? AND source_description=? AND batch_id=?",outing,source,batchId);
                db.update("UPDATE idee_description_batch_item SET outcome=? WHERE batch_id=? AND outing_id=?",outcome,batchId,outing);
            });
        } catch(CannotAcquireLockException ignored) { /* collect this item on the next poll */ }
    }
}
