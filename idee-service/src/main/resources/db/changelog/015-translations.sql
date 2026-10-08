-- Translations depend on the generated French texts and title, not on imported translations.
CREATE VIEW idee_translation_source AS
 SELECT o.id AS outing_id,
 jsonb_build_object('title',o.title,'description_longue',d.description_longue,'description_courte',d.description_courte) AS source,
 md5(jsonb_build_array(o.title,d.description_longue,d.description_courte)::text) AS source_hash
 FROM idee_outing o JOIN idee_outing_description d ON d.outing_id=o.id AND d.language='fr'
 JOIN idee_outing_description_source s ON s.outing_id=o.id AND s.language='fr' AND s.description=d.source_description
 WHERE o.status='published' AND NOT o.is_demo;

CREATE TABLE idee_outing_translation (
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 language text NOT NULL CHECK(language IN ('en','de','it','nl','es')),
 source_hash text NOT NULL,
 title text NOT NULL CHECK(char_length(btrim(title)) BETWEEN 1 AND 500),
 description_longue text NOT NULL CHECK(length(btrim(description_longue))>0),
 description_courte text NOT NULL CHECK(char_length(description_courte) BETWEEN 1 AND 300),
 model text NOT NULL,
 prompt_version integer NOT NULL,
 generated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(outing_id,language)
);
CREATE TABLE idee_translation_batch (
 id uuid PRIMARY KEY,
 provider_id text UNIQUE,
 input_file_id text,
 state text NOT NULL,
 model text NOT NULL,
 prompt_version integer NOT NULL,
 payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 next_poll_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 attempts integer NOT NULL DEFAULT 0,
 last_error text,
 total integer NOT NULL,
 succeeded integer NOT NULL DEFAULT 0,
 failed integer NOT NULL DEFAULT 0
);
CREATE INDEX idee_translation_batch_due ON idee_translation_batch(next_poll_at) WHERE completed_at IS NULL;
CREATE TABLE idee_translation_batch_item (
 batch_id uuid NOT NULL REFERENCES idee_translation_batch(id) ON DELETE CASCADE,
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 language text NOT NULL,
 source_hash text NOT NULL,
 source jsonb NOT NULL,
 outcome text NOT NULL DEFAULT 'pending',
 PRIMARY KEY(batch_id,outing_id,language)
);
CREATE TABLE idee_outing_translation_job (
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 language text NOT NULL CHECK(language IN ('en','de','it','nl','es')),
 source_hash text NOT NULL,
 source jsonb NOT NULL,
 batch_id uuid REFERENCES idee_translation_batch(id),
 requested_at timestamptz NOT NULL DEFAULT now(),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts integer NOT NULL DEFAULT 0,
 last_error text,
 PRIMARY KEY(outing_id,language)
);
CREATE INDEX idee_translation_job_due ON idee_outing_translation_job(next_attempt_at,outing_id);
CREATE FUNCTION idee_queue_outing_translation(oid bigint,lang text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE snapshot record;
BEGIN
 IF lang NOT IN ('en','de','it','nl','es') THEN RAISE EXCEPTION 'Unsupported translation language'; END IF;
 SELECT * INTO snapshot FROM idee_translation_source WHERE outing_id=oid;
 IF NOT FOUND THEN
   DELETE FROM idee_outing_translation_job WHERE outing_id=oid AND language=lang;
   RETURN;
 END IF;
 IF EXISTS(SELECT 1 FROM idee_outing_translation WHERE outing_id=oid AND language=lang AND source_hash=snapshot.source_hash) THEN
   DELETE FROM idee_outing_translation_job WHERE outing_id=oid AND language=lang;
   RETURN;
 END IF;
 INSERT INTO idee_outing_translation_job(outing_id,language,source_hash,source) VALUES(oid,lang,snapshot.source_hash,snapshot.source)
 ON CONFLICT(outing_id,language) DO UPDATE SET source_hash=EXCLUDED.source_hash,source=EXCLUDED.source,
 batch_id=NULL,requested_at=now(),next_attempt_at=now(),attempts=0,last_error=NULL
 WHERE idee_outing_translation_job.source_hash IS DISTINCT FROM EXCLUDED.source_hash;
END $$;
-- Deliberately no backfill or automatic enqueue trigger: only an explicit admin request starts translations.
