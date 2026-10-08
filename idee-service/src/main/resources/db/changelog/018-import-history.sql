CREATE TABLE idee_import_run (
 id uuid PRIMARY KEY,
 source text NOT NULL DEFAULT 'datatourisme',
 department char(2) NOT NULL,
 mode text NOT NULL CHECK(mode IN ('full','update')),
 started_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 last_processed_at timestamptz,
 state text NOT NULL DEFAULT 'running' CHECK(state IN ('running','retrying','completed')),
 pages bigint NOT NULL DEFAULT 0,
 objects bigint NOT NULL DEFAULT 0,
 new_outings bigint NOT NULL DEFAULT 0,
 updated_outings bigint NOT NULL DEFAULT 0,
 mistral_outings bigint NOT NULL DEFAULT 0,
 description_translation_outings bigint NOT NULL DEFAULT 0,
 title_translation_outings bigint NOT NULL DEFAULT 0,
 last_error text
);
CREATE INDEX idee_import_run_started ON idee_import_run(started_at DESC,id);

-- Preserve the history even if an outing is later deleted. One count per outing/run.
CREATE TABLE idee_import_run_outing (
 run_id uuid NOT NULL REFERENCES idee_import_run(id) ON DELETE CASCADE,
 outing_id bigint NOT NULL,
 new_outing boolean NOT NULL,
 updated_outing boolean NOT NULL,
 mistral boolean NOT NULL DEFAULT false,
 description_translation boolean NOT NULL DEFAULT false,
 title_translation boolean NOT NULL DEFAULT false,
 PRIMARY KEY(run_id,outing_id)
);
CREATE FUNCTION idee_import_run_count_outing() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE idee_import_run SET
   new_outings=new_outings+NEW.new_outing::int-CASE WHEN TG_OP='UPDATE' THEN OLD.new_outing::int ELSE 0 END,
   updated_outings=updated_outings+NEW.updated_outing::int-CASE WHEN TG_OP='UPDATE' THEN OLD.updated_outing::int ELSE 0 END,
   mistral_outings=mistral_outings+NEW.mistral::int-CASE WHEN TG_OP='UPDATE' THEN OLD.mistral::int ELSE 0 END,
   description_translation_outings=description_translation_outings+NEW.description_translation::int-CASE WHEN TG_OP='UPDATE' THEN OLD.description_translation::int ELSE 0 END,
   title_translation_outings=title_translation_outings+NEW.title_translation::int-CASE WHEN TG_OP='UPDATE' THEN OLD.title_translation::int ELSE 0 END
 WHERE id=NEW.run_id;
 RETURN NULL;
END $$;
CREATE TRIGGER idee_import_run_count_outing AFTER INSERT OR UPDATE ON idee_import_run_outing
 FOR EACH ROW EXECUTE FUNCTION idee_import_run_count_outing();

ALTER TABLE idee_outing_description_job ADD COLUMN import_run_id uuid REFERENCES idee_import_run(id) ON DELETE SET NULL;
ALTER TABLE idee_description_batch_item ADD COLUMN import_run_id uuid REFERENCES idee_import_run(id) ON DELETE SET NULL;
ALTER TABLE idee_outing_translation_job ADD COLUMN import_run_id uuid REFERENCES idee_import_run(id) ON DELETE SET NULL;

CREATE FUNCTION idee_import_track_mistral() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND NEW.source_description IS NOT DISTINCT FROM OLD.source_description THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' AND EXISTS(SELECT 1 FROM idee_outing_description_job WHERE outing_id=NEW.outing_id AND source_description=NEW.source_description) THEN RETURN NEW; END IF;
 NEW.import_run_id=NULLIF(current_setting('idee.import_run_id',true),'')::uuid;
 IF NEW.import_run_id IS NOT NULL THEN
   UPDATE idee_import_run_outing SET mistral=true WHERE run_id=NEW.import_run_id AND outing_id=NEW.outing_id AND NOT mistral;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER idee_import_track_mistral BEFORE INSERT OR UPDATE ON idee_outing_description_job
 FOR EACH ROW EXECUTE FUNCTION idee_import_track_mistral();

CREATE FUNCTION idee_import_track_translation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE descriptions_needed boolean;
BEGIN
 IF TG_OP='UPDATE' AND NEW.source_hash IS NOT DISTINCT FROM OLD.source_hash THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' AND EXISTS(SELECT 1 FROM idee_outing_translation_job WHERE outing_id=NEW.outing_id AND language=NEW.language AND source_hash=NEW.source_hash) THEN RETURN NEW; END IF;
 NEW.import_run_id=NULLIF(current_setting('idee.import_run_id',true),'')::uuid;
 IF NEW.import_run_id IS NOT NULL THEN
   SELECT NOT EXISTS (
     SELECT 1 FROM idee_outing_translation t WHERE t.outing_id=NEW.outing_id AND t.language=NEW.language
       AND t.source->'description_longue'=NEW.source->'description_longue'
       AND t.source->'description_courte'=NEW.source->'description_courte'
   ) INTO descriptions_needed;
   UPDATE idee_import_run_outing SET title_translation=true,
     description_translation=description_translation OR descriptions_needed
   WHERE run_id=NEW.import_run_id AND outing_id=NEW.outing_id
     AND (NOT title_translation OR (descriptions_needed AND NOT description_translation));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER idee_import_track_translation BEFORE INSERT OR UPDATE ON idee_outing_translation_job
 FOR EACH ROW EXECUTE FUNCTION idee_import_track_translation();
