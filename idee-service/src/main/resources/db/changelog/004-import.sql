ALTER TABLE idee_outing ADD COLUMN source_name varchar(100);
ALTER TABLE idee_outing ADD COLUMN external_id varchar(200);
ALTER TABLE idee_outing ADD COLUMN import_hash varchar(64);
ALTER TABLE idee_outing ADD CONSTRAINT idee_outing_source_pair CHECK ((source_name IS NULL) = (external_id IS NULL));
CREATE UNIQUE INDEX idee_outing_source_unique ON idee_outing(source_name,external_id) WHERE source_name IS NOT NULL;
