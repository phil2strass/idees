-- Slugs are persisted, never derived from the title on reads or daily imports.
CREATE FUNCTION idee_url_slug(value text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
 SELECT trim(both '-' FROM regexp_replace(
 translate(replace(replace(replace(lower(normalize(coalesce(value,''),NFC)),'œ','oe'),'æ','ae'),'ß','ss'),
 'àáâäãåāæçćčèéêëēėęìíîïīñńòóôöõøōùúûüūýÿžźżšśřďťľĺ',
 'aaaaaaaaccceeeeeeeiiiiinnooooooouuuuuyyzzzssrdtll'), '[^a-z0-9]+','-','g'))
$$;
CREATE TABLE idee_department (code char(2) PRIMARY KEY);
INSERT INTO idee_department VALUES ('67'),('68');
CREATE TABLE idee_department_translation (
 department_code char(2) REFERENCES idee_department(code), language text NOT NULL CHECK(language ~ '^[a-z]{2,3}(-[a-zA-Z0-9]+)*$'),
 name text NOT NULL, slug text NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 PRIMARY KEY(department_code,language), UNIQUE(language,slug)
);
INSERT INTO idee_department_translation VALUES ('67','fr','Bas-Rhin','bas-rhin'),('68','fr','Haut-Rhin','haut-rhin');
CREATE TABLE idee_city (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 department_code char(2) NOT NULL REFERENCES idee_department(code),
 source_key text NOT NULL, insee_code varchar(5) UNIQUE,
 UNIQUE(department_code,source_key)
);
CREATE TABLE idee_city_translation (
 city_id bigint REFERENCES idee_city(id), language text NOT NULL CHECK(language ~ '^[a-z]{2,3}(-[a-zA-Z0-9]+)*$'), name text NOT NULL,
 slug text NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 PRIMARY KEY(city_id,language)
);
ALTER TABLE idee_place ADD COLUMN city_id bigint REFERENCES idee_city(id);
CREATE FUNCTION idee_link_place_city() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE city_key text;
BEGIN
 city_key := idee_url_slug(NEW.city);
 IF city_key = '' THEN NEW.city_id := NULL; RETURN NEW; END IF;
 INSERT INTO idee_city(department_code,source_key) VALUES(NEW.department,city_key)
 ON CONFLICT(department_code,source_key) DO UPDATE SET source_key=EXCLUDED.source_key RETURNING id INTO NEW.city_id;
 INSERT INTO idee_city_translation VALUES(NEW.city_id,'fr',NEW.city,city_key) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER idee_place_city BEFORE INSERT OR UPDATE OF city,department ON idee_place
 FOR EACH ROW EXECUTE FUNCTION idee_link_place_city();
UPDATE idee_place SET city=city;
-- Attach source INSEE codes only when the department/name match an existing commune.
-- Conflicting spellings stay separate for editorial reconciliation; imports must not fail.
CREATE FUNCTION idee_attach_city_insee(dep text, city_name text, code text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF code IS NULL OR code !~ '^[0-9]{5}$' OR left(code,2)<>dep THEN RETURN; END IF;
 UPDATE idee_city SET insee_code=code
 WHERE department_code=dep AND source_key=idee_url_slug(city_name) AND insee_code IS NULL
 AND NOT EXISTS(SELECT 1 FROM idee_city WHERE insee_code=code);
EXCEPTION WHEN unique_violation THEN RETURN;
END $$;
SELECT idee_attach_city_insee(department_code,city,insee_code)
 FROM idee_datatourisme_location WHERE insee_code IS NOT NULL ORDER BY uuid,position,address_position;
CREATE FUNCTION idee_source_city_insee() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM idee_attach_city_insee(NEW.department_code,NEW.city,NEW.insee_code);
 RETURN NEW;
END $$;
CREATE TRIGGER idee_location_city_insee AFTER INSERT OR UPDATE ON idee_datatourisme_location
 FOR EACH ROW EXECUTE FUNCTION idee_source_city_insee();
CREATE TABLE idee_outing_url (
 outing_id bigint REFERENCES idee_outing(id) ON DELETE CASCADE,
 language text NOT NULL CHECK(language ~ '^[a-z]{2,3}(-[a-zA-Z0-9]+)*$'),
 slug text NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 PRIMARY KEY(outing_id,language)
);
-- All old paths remain reserved; they cannot be reassigned to another outing.
CREATE TABLE idee_public_route (
 path text PRIMARY KEY CHECK(path ~ '^/[a-z0-9/-]+$' AND path NOT LIKE '//%'),
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 language text NOT NULL CHECK(language ~ '^[a-z]{2,3}(-[a-zA-Z0-9]+)*$'), canonical boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idee_public_route_current ON idee_public_route(outing_id,language) WHERE canonical;
CREATE INDEX idee_public_route_outing ON idee_public_route(outing_id);
-- Explicit refresh after an editorial slug/geography change keeps the previous path as an alias.
-- Non-French translations are stored now; publishing their pages is a separate rollout.
CREATE FUNCTION idee_publish_outing_url(oid bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE o record; candidate text; segment text; prefix text; suffix integer := 0;
BEGIN
 PERFORM pg_advisory_xact_lock(12012026);
 SELECT x.*,dt.slug AS department_slug,ct.slug AS city_slug INTO o
 FROM idee_outing x LEFT JOIN idee_place p ON p.id=x.place_id
 LEFT JOIN idee_city c ON c.id=p.city_id
 LEFT JOIN idee_department_translation dt ON dt.department_code=c.department_code AND dt.language='fr'
 LEFT JOIN idee_city_translation ct ON ct.city_id=c.id AND ct.language='fr' WHERE x.id=oid;
 IF NOT FOUND OR o.status<>'published' OR o.city_slug IS NULL OR o.department_slug IS NULL THEN RETURN; END IF;
 SELECT slug INTO segment FROM idee_outing_url WHERE outing_id=oid AND language='fr';
 IF segment IS NULL THEN
   segment := left(idee_url_slug(o.title),160);
   segment := rtrim(segment,'-');
   IF segment='' THEN segment := 'sortie-'||oid; END IF;
 END IF;
 prefix := '/'||o.department_slug||'/'||o.city_slug||'/';
 candidate := prefix||segment;
 WHILE EXISTS(SELECT 1 FROM idee_public_route WHERE path=candidate AND (outing_id<>oid OR language<>'fr')) LOOP
   suffix := suffix+1;
   candidate := prefix||segment||'-'||oid||CASE WHEN suffix=1 THEN '' ELSE '-'||suffix END;
 END LOOP;
 INSERT INTO idee_outing_url VALUES(oid,'fr',substring(candidate FROM length(prefix)+1))
 ON CONFLICT(outing_id,language) DO UPDATE SET slug=EXCLUDED.slug;
 UPDATE idee_public_route SET canonical=false WHERE outing_id=oid AND language='fr' AND canonical;
 INSERT INTO idee_public_route(path,outing_id,language,canonical) VALUES(candidate,oid,'fr',true)
 ON CONFLICT(path) DO UPDATE SET canonical=true;
END $$;
CREATE FUNCTION idee_ensure_outing_url() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' THEN
   INSERT INTO idee_public_route(path,outing_id,language) VALUES('/sorties/'||NEW.slug,NEW.id,'fr')
   ON CONFLICT(path) DO NOTHING;
   IF EXISTS(SELECT 1 FROM idee_public_route WHERE path='/sorties/'||NEW.slug AND outing_id<>NEW.id) THEN
     RAISE EXCEPTION 'This historical URL belongs to another outing';
   END IF;
 END IF;
 IF NEW.status='published' AND NOT EXISTS(SELECT 1 FROM idee_public_route WHERE outing_id=NEW.id AND language='fr' AND canonical) THEN
   PERFORM idee_publish_outing_url(NEW.id);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER idee_outing_url AFTER INSERT OR UPDATE ON idee_outing
 FOR EACH ROW EXECUTE FUNCTION idee_ensure_outing_url();
SELECT idee_publish_outing_url(id) FROM idee_outing ORDER BY id;

INSERT INTO idee_public_route(path,outing_id,language) SELECT '/sorties/'||slug,id,'fr' FROM idee_outing WHERE slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' ON CONFLICT DO NOTHING;
