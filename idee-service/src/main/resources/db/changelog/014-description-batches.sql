CREATE TABLE idee_description_batch (
 id uuid PRIMARY KEY,
 provider_id text UNIQUE,
 state text NOT NULL,
 model text NOT NULL,
 prompt_version integer NOT NULL,
 payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 next_poll_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 attempts integer NOT NULL DEFAULT 0,
 last_error text,
 total integer NOT NULL,
 succeeded integer NOT NULL DEFAULT 0,
 failed integer NOT NULL DEFAULT 0
);
CREATE INDEX idee_description_batch_due ON idee_description_batch(next_poll_at) WHERE completed_at IS NULL;
CREATE TABLE idee_description_batch_item (
 batch_id uuid NOT NULL REFERENCES idee_description_batch(id) ON DELETE CASCADE,
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 source_description text NOT NULL,
 outcome text NOT NULL DEFAULT 'pending',
 PRIMARY KEY(batch_id,outing_id)
);
ALTER TABLE idee_outing_description_job ADD COLUMN batch_id uuid REFERENCES idee_description_batch(id);
CREATE INDEX idee_description_job_batch ON idee_outing_description_job(batch_id);

-- A source change releases the new job, while the old batch keeps its own snapshot.
CREATE OR REPLACE FUNCTION idee_queue_outing_description(oid bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE source text;
BEGIN
 SELECT s.description INTO source FROM idee_outing_description_source s
 JOIN idee_outing o ON o.id=s.outing_id
 WHERE s.outing_id=oid AND s.language='fr' AND o.status='published' AND NOT o.is_demo;
 IF source IS NULL OR EXISTS (
   SELECT 1 FROM idee_outing_description WHERE outing_id=oid AND language='fr' AND source_description=source
 ) THEN
   DELETE FROM idee_outing_description_job WHERE outing_id=oid;
   RETURN;
 END IF;
 INSERT INTO idee_outing_description_job(outing_id,source_description) VALUES(oid,source)
 ON CONFLICT(outing_id) DO UPDATE SET source_description=EXCLUDED.source_description,
   requested_at=now(),next_attempt_at=now(),attempts=0,last_error=NULL,batch_id=NULL
 WHERE idee_outing_description_job.source_description IS DISTINCT FROM EXCLUDED.source_description;
END $$;
