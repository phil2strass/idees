package fr.idee;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Service
public class CalendarProjection {
 @org.springframework.beans.factory.annotation.Value("${idee.calendar-refresh-enabled:true}")
 private boolean refreshEnabled=true;
 private final JdbcTemplate db;
 private final CalendarEngine calendar;
 public CalendarProjection(JdbcTemplate db,CalendarEngine calendar){this.db=db;this.calendar=calendar;}
 @EventListener(ApplicationReadyEvent.class)
 @Scheduled(cron="0 15 3 * * *",zone="Europe/Paris")
 @Transactional
 public void refresh(){
  if (!refreshEnabled) return;
  // Consistent lock order with inserts and the optional command-line projector.
  db.execute("LOCK TABLE idee_schedule, idee_schedule_exception IN SHARE MODE");
  db.execute("LOCK TABLE idee_occurrence, idee_calendar_projection IN EXCLUSIVE MODE");
  var schedules=db.queryForList("SELECT id,timezone,starts_local::text,ends_local::text,rrule,place_id FROM idee_schedule WHERE enabled ORDER BY id");
  Map<Long,Map<String,Object>> byId=new HashMap<>();
  for(var s:schedules){byId.put(((Number)s.get("id")).longValue(),s);s.put("exceptions",new ArrayList<Map<String,Object>>());}
  for(var e:db.queryForList("SELECT schedule_id,original_start_local::text,action,starts_local::text,ends_local::text,place_id,note FROM idee_schedule_exception ORDER BY id")){
   var s=byId.get(((Number)e.get("schedule_id")).longValue());
   if(s!=null){@SuppressWarnings("unchecked") var list=(List<Map<String,Object>>)s.get("exceptions");list.add(e);}
  }
  LocalDate today=LocalDate.now(ZoneId.of("Europe/Paris")),from=today.minusDays(31),until=today.plusDays(550);
  var rows=calendar.expand(schedules,from,until);
  db.update("DELETE FROM idee_occurrence");
  db.batchUpdate("INSERT INTO idee_occurrence(schedule_id,original_start_local,starts_at,ends_at,status,place_id,note) VALUES(?,?,?,?,?,?,?)",rows,1000,(ps,o)->{
    ps.setObject(1,o.get("schedule_id"));ps.setTimestamp(2,Timestamp.valueOf(LocalDateTime.parse((String)o.get("original_start_local"))));
    ps.setObject(3,OffsetDateTime.parse((String)o.get("starts_at")));ps.setObject(4,OffsetDateTime.parse((String)o.get("ends_at")));
    ps.setObject(5,o.get("status"));ps.setObject(6,o.get("place_id"));ps.setObject(7,o.get("note"));
  });
  db.update("INSERT INTO idee_calendar_projection(singleton,from_date,until_date) VALUES(true,?,?) ON CONFLICT(singleton) DO UPDATE SET from_date=excluded.from_date,until_date=excluded.until_date,generated_at=now()",from,until);
 }
}
