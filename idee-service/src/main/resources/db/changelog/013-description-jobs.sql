CREATE TABLE idee_outing_description_job (
 outing_id bigint PRIMARY KEY REFERENCES idee_outing(id) ON DELETE CASCADE,
 source_description text NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts >= 0),
 last_error text
);
CREATE INDEX idee_description_job_due ON idee_outing_description_job(next_attempt_at,outing_id);

-- Evaluate the final source at commit: DATAtourisme replaces translations with DELETE/INSERT.
-- Reimporting identical text must not reset the retry delay or generate another paid request.
CREATE FUNCTION idee_queue_outing_description(oid bigint) RETURNS void LANGUAGE plpgsql AS $$
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
   requested_at=now(),next_attempt_at=now(),attempts=0,last_error=NULL
 WHERE idee_outing_description_job.source_description IS DISTINCT FROM EXCLUDED.source_description;
END $$;

CREATE FUNCTION idee_description_outing_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM idee_queue_outing_description(NEW.id);
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_description_outing_changed AFTER INSERT OR UPDATE ON idee_outing
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_description_outing_changed();

CREATE FUNCTION idee_description_translation_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE oid bigint;
BEGIN
 IF TG_OP <> 'INSERT' AND OLD.language='fr' THEN
   SELECT outing_id INTO oid FROM idee_datatourisme_event WHERE uuid=OLD.uuid;
   IF oid IS NOT NULL THEN PERFORM idee_queue_outing_description(oid); END IF;
 END IF;
 IF TG_OP <> 'DELETE' AND NEW.language='fr' THEN
   SELECT outing_id INTO oid FROM idee_datatourisme_event WHERE uuid=NEW.uuid;
   IF oid IS NOT NULL THEN PERFORM idee_queue_outing_description(oid); END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_description_translation_changed AFTER INSERT OR UPDATE OR DELETE ON idee_datatourisme_translation
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_description_translation_changed();

CREATE FUNCTION idee_description_source_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP <> 'INSERT' THEN PERFORM idee_queue_outing_description(OLD.outing_id); END IF;
 IF TG_OP <> 'DELETE' THEN PERFORM idee_queue_outing_description(NEW.outing_id); END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_description_source_changed AFTER INSERT OR UPDATE OR DELETE ON idee_datatourisme_event
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_description_source_changed();
