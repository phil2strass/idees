-- Generated editorial text is independent from the source translations replaced by imports.
CREATE TABLE idee_outing_description (
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 language text NOT NULL CHECK (language ~ '^[a-z]{2,3}(-[a-zA-Z0-9]+)*$'),
 source_description text NOT NULL,
 description_longue text NOT NULL CHECK (length(btrim(description_longue)) > 0),
 description_courte text NOT NULL CHECK (char_length(description_courte) BETWEEN 1 AND 300),
 model text NOT NULL,
 prompt_version integer NOT NULL,
 generated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (outing_id, language)
);

-- A view keeps the original translations current, without duplicating imported data.
CREATE VIEW idee_outing_description_source AS
 SELECT e.outing_id,t.language,t.description
 FROM idee_datatourisme_event e JOIN idee_datatourisme_translation t ON t.uuid=e.uuid
 WHERE t.description IS NOT NULL AND btrim(t.description)<>''
 UNION ALL
 SELECT o.id,'fr',o.description FROM idee_outing o
 WHERE btrim(o.description)<>'' AND NOT EXISTS
 (SELECT 1 FROM idee_datatourisme_event e WHERE e.outing_id=o.id);
