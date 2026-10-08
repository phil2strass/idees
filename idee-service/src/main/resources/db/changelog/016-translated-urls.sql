-- Reserve the old prefixed French paths before publishing translated titles.
INSERT INTO idee_public_route(path,outing_id,language)
 SELECT '/'||l.language||r.path,r.outing_id,l.language
 FROM idee_public_route r CROSS JOIN unnest(ARRAY['en','de','it','nl','es']) l(language)
 WHERE r.language='fr' ON CONFLICT(path) DO NOTHING;

CREATE FUNCTION idee_publish_translated_outing_url(oid bigint, lang text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE o record; segment text; prefix text; candidate text; suffix integer := 0;
BEGIN
 IF lang NOT IN ('en','de','it','nl','es') THEN RAISE EXCEPTION 'Unsupported public language'; END IF;
 PERFORM pg_advisory_xact_lock(12012026);
 SELECT t.title,coalesce(fr.path,'/sorties/'||x.slug) AS french_path,
   coalesce(dt.slug,split_part(fr.path,'/',2)) AS department_slug,
   coalesce(ct.slug,split_part(fr.path,'/',3)) AS city_slug
 INTO o FROM idee_outing_translation t
 JOIN idee_translation_source s ON s.outing_id=t.outing_id AND s.source_hash=t.source_hash
 JOIN idee_outing x ON x.id=t.outing_id
 LEFT JOIN idee_place p ON p.id=x.place_id
 LEFT JOIN idee_department_translation dt ON dt.department_code=p.department AND dt.language=lang
 LEFT JOIN idee_city_translation ct ON ct.city_id=p.city_id AND ct.language=lang
 LEFT JOIN idee_public_route fr ON fr.outing_id=t.outing_id AND fr.language='fr' AND fr.canonical
 WHERE t.outing_id=oid AND t.language=lang;
 IF NOT FOUND THEN RETURN; END IF;
 SELECT slug INTO segment FROM idee_outing_url WHERE outing_id=oid AND language=lang;
 IF segment IS NULL THEN
   segment := rtrim(left(idee_url_slug(o.title),160),'-');
   IF segment='' THEN segment := 'outing-'||oid; END IF;
 END IF;
 prefix := '/'||lang||'/'||CASE WHEN o.french_path LIKE '/sorties/%' THEN 'sorties/'
   ELSE o.department_slug||'/'||o.city_slug||'/' END;
 candidate := prefix||segment;
 WHILE EXISTS(SELECT 1 FROM idee_public_route WHERE path=candidate AND (outing_id<>oid OR language<>lang)) LOOP
   suffix := suffix+1;
   candidate := prefix||segment||'-'||oid||CASE WHEN suffix=1 THEN '' ELSE '-'||suffix END;
 END LOOP;
 INSERT INTO idee_outing_url VALUES(oid,lang,substring(candidate FROM length(prefix)+1))
 ON CONFLICT(outing_id,language) DO UPDATE SET slug=EXCLUDED.slug;
 UPDATE idee_public_route SET canonical=false WHERE outing_id=oid AND language=lang AND canonical;
 INSERT INTO idee_public_route(path,outing_id,language,canonical) VALUES(candidate,oid,lang,true)
 ON CONFLICT(path) DO UPDATE SET canonical=true;
END $$;

CREATE FUNCTION idee_translation_public_url() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM idee_public_route WHERE outing_id=NEW.outing_id AND language=NEW.language AND canonical) THEN
   PERFORM idee_publish_translated_outing_url(NEW.outing_id,NEW.language);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER idee_translation_public_url AFTER INSERT OR UPDATE ON idee_outing_translation
 FOR EACH ROW EXECUTE FUNCTION idee_translation_public_url();

CREATE FUNCTION idee_french_route_language_aliases() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lang text;
BEGIN
 IF NEW.language<>'fr' THEN RETURN NEW; END IF;
 FOREACH lang IN ARRAY ARRAY['en','de','it','nl','es'] LOOP
   INSERT INTO idee_public_route(path,outing_id,language) VALUES('/'||lang||NEW.path,NEW.outing_id,lang)
   ON CONFLICT(path) DO NOTHING;
   IF EXISTS(SELECT 1 FROM idee_public_route WHERE path='/'||lang||NEW.path AND outing_id<>NEW.outing_id) THEN
     RAISE EXCEPTION 'This language alias belongs to another outing';
   END IF;
   IF NEW.canonical THEN PERFORM idee_publish_translated_outing_url(NEW.outing_id,lang); END IF;
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER idee_french_route_language_aliases AFTER INSERT OR UPDATE OF canonical ON idee_public_route
 FOR EACH ROW EXECUTE FUNCTION idee_french_route_language_aliases();

-- A future French title must not take a translated URL already reserved for another outing.
CREATE OR REPLACE FUNCTION idee_publish_outing_url(oid bigint) RETURNS void LANGUAGE plpgsql AS $$
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
 WHILE EXISTS(SELECT 1 FROM idee_public_route WHERE outing_id<>oid AND (path=candidate OR (language IN ('en','de','it','nl','es') AND path='/'||language||candidate))) LOOP
   suffix := suffix+1;
   candidate := prefix||segment||'-'||oid||CASE WHEN suffix=1 THEN '' ELSE '-'||suffix END;
 END LOOP;
 INSERT INTO idee_outing_url VALUES(oid,'fr',substring(candidate FROM length(prefix)+1))
 ON CONFLICT(outing_id,language) DO UPDATE SET slug=EXCLUDED.slug;
 UPDATE idee_public_route SET canonical=false WHERE outing_id=oid AND language='fr' AND canonical;
 INSERT INTO idee_public_route(path,outing_id,language,canonical) VALUES(candidate,oid,'fr',true)
 ON CONFLICT(path) DO UPDATE SET canonical=true;
END $$;

SELECT idee_publish_translated_outing_url(t.outing_id,t.language)
 FROM idee_outing_translation t ORDER BY t.outing_id,t.language;
