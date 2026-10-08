package fr.idee;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Public, explicitly selected fields. Never expose the raw imported document or internal checkpoints. */
@Service
public class OutingSourceDetails {
    private final JdbcTemplate db;
    public OutingSourceDetails(JdbcTemplate db) { this.db=db; }
    public Map<String,Object> forOuting(Object outingId) {
        var events=db.queryForList("""
            SELECT uuid,source_identifier AS "reference",source_created_on::text AS "createdOn",
                source_updated_on::text AS "updatedOn",source_updated_at::text AS "updatedAt"
            FROM idee_datatourisme_event WHERE outing_id=?
            """,outingId);
        if (events.isEmpty()) return null;
        var result=events.getFirst(); Object uuid=result.remove("uuid");
        var contacts=db.queryForList("SELECT id,role,name FROM idee_datatourisme_contact WHERE uuid=? ORDER BY role,position",uuid);
        var channels=db.queryForList("""
            SELECT ch.contact_id,ch.kind,ch.value FROM idee_datatourisme_contact_channel ch
            JOIN idee_datatourisme_contact c ON c.id=ch.contact_id WHERE c.uuid=? ORDER BY ch.kind,ch.value
            """,uuid);
        for (var contact:contacts) {
            Object id=contact.remove("id");
            contact.put("channels",channels.stream().filter(ch->Objects.equals(ch.get("contact_id"),id))
                    .map(ch->Map.of("kind",ch.get("kind"),"value",ch.get("value"))).toList());
        }
        result.put("contacts",contacts);
        result.put("translations",db.queryForList("SELECT language,title,summary,description,comment FROM idee_datatourisme_translation WHERE uuid=? ORDER BY language",uuid));
        result.put("terms",db.queryForList("""
            SELECT DISTINCT property,term_key AS key,COALESCE(NULLIF(labels->>'@fr',''),NULLIF(labels->>'fr',''),term_key) AS label
            FROM idee_datatourisme_term WHERE uuid=? AND property IN
            ('hasTheme','hasGeographicReach','availableLanguage','isDedicatedTo','hasAudience','hasClientTarget','isEquippedWith','hasFacility','hasPractice')
            ORDER BY property,label
            """,uuid));
        result.put("locations",db.queryForList("""
            SELECT name,street_address AS address,postal_code AS "postalCode",city,department_code AS department,latitude,longitude
            FROM idee_datatourisme_location WHERE uuid=? ORDER BY position,address_position
            """,uuid));
        result.put("resources",db.queryForList("""
            SELECT DISTINCT url,media_type AS "mediaType",title,credit,license
            FROM idee_datatourisme_resource WHERE uuid=? AND NOT is_image ORDER BY title,url
            """,uuid));
        return result;
    }
}
