package fr.idee;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="IDEE_DATATOURISME_DB_TEST",matches="isolated")
@SpringBootTest(properties={"idee.openai.translations-enabled=false","idee.mistral.auto-enabled=false","idee.datatourisme.enabled=false","idee.calendar-refresh-enabled=false"})
class OutingDatesDatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired CatalogController catalog;

    @Test void detailIncludesTheCurrentWeekAndCatalogueKeepsOnlyUpcomingSessions() {
        assertTrue(db.queryForObject("SELECT current_schema()",String.class).startsWith("idee_datatourisme_test_"));
        long outing=db.queryForObject("INSERT INTO idee_outing(slug,title,summary,description,kind,status) VALUES('dates-week','Sortie','Résumé','Texte','event','published') RETURNING id",Long.class);
        String zone="Europe/Paris";
        long schedule=db.queryForObject("""
            INSERT INTO idee_schedule(outing_id,label,timezone,starts_local,ends_local)
            VALUES(?,'Dates',?,now() AT TIME ZONE ?,(now() AT TIME ZONE ?)+interval '1 hour') RETURNING id
            """,Long.class,outing,zone,zone,zone);
        db.update("""
            INSERT INTO idee_occurrence(schedule_id,original_start_local,starts_at,ends_at,status)
            SELECT ?,start AT TIME ZONE ?,start,start+interval '1 minute','scheduled' FROM (
              SELECT (date_trunc('week',now() AT TIME ZONE ?) AT TIME ZONE ?)-interval '1 hour' AS start
              UNION ALL SELECT now()-interval '2 minutes'
              UNION ALL SELECT now()+interval '1 day'
            ) sessions
            """,schedule,zone,zone,zone);
        var detail=(List<?>)catalog.outing("dates-week").get("occurrences");
        assertEquals(2,detail.size(),"Detail retains this week's ended session and the upcoming session");
        var listing=catalog.outings(null,false,false,100,0).stream()
            .filter(row -> ((Number)row.get("id")).longValue()==outing).findFirst().orElseThrow();
        assertEquals(1,((List<?>)listing.get("occurrences")).size(),"Catalogue still excludes ended sessions");
    }
}
