-- Keep the French snapshot to distinguish a title edit from a description edit.
ALTER TABLE idee_outing_translation ADD COLUMN source jsonb;
UPDATE idee_outing_translation t SET source=s.source
 FROM idee_translation_source s WHERE s.outing_id=t.outing_id AND s.source_hash=t.source_hash;
ALTER TABLE idee_translation_batch_item ADD COLUMN reused_descriptions jsonb;

CREATE FUNCTION idee_queue_outing_translations(oid bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE lang text;
BEGIN
 FOREACH lang IN ARRAY ARRAY['en','de','it','nl','es'] LOOP
   PERFORM idee_queue_outing_translation(oid,lang);
 END LOOP;
END $$;

-- Evaluate the final state after an import's DELETE/INSERT of French source rows.
-- No backfill here: only changed outings and completed French generation enqueue work.
CREATE FUNCTION idee_automatic_translation_outing_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM idee_queue_outing_translations(NEW.id);
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_automatic_translation_outing_changed AFTER INSERT OR UPDATE ON idee_outing
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_automatic_translation_outing_changed();

CREATE FUNCTION idee_automatic_translation_description_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP <> 'INSERT' AND OLD.language='fr' THEN PERFORM idee_queue_outing_translations(OLD.outing_id); END IF;
 IF TG_OP <> 'DELETE' AND NEW.language='fr' THEN PERFORM idee_queue_outing_translations(NEW.outing_id); END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_automatic_translation_description_changed AFTER INSERT OR UPDATE OR DELETE ON idee_outing_description
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_automatic_translation_description_changed();

CREATE FUNCTION idee_automatic_translation_import_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE oid bigint;
BEGIN
 IF TG_OP <> 'INSERT' AND OLD.language='fr' THEN
   SELECT outing_id INTO oid FROM idee_datatourisme_event WHERE uuid=OLD.uuid;
   IF oid IS NOT NULL THEN PERFORM idee_queue_outing_translations(oid); END IF;
 END IF;
 IF TG_OP <> 'DELETE' AND NEW.language='fr' THEN
   SELECT outing_id INTO oid FROM idee_datatourisme_event WHERE uuid=NEW.uuid;
   IF oid IS NOT NULL THEN PERFORM idee_queue_outing_translations(oid); END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_automatic_translation_import_changed AFTER INSERT OR UPDATE OR DELETE ON idee_datatourisme_translation
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_automatic_translation_import_changed();

CREATE FUNCTION idee_automatic_translation_source_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP <> 'INSERT' THEN PERFORM idee_queue_outing_translations(OLD.outing_id); END IF;
 IF TG_OP <> 'DELETE' THEN PERFORM idee_queue_outing_translations(NEW.outing_id); END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idee_automatic_translation_source_changed AFTER INSERT OR UPDATE OR DELETE ON idee_datatourisme_event
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION idee_automatic_translation_source_changed();
