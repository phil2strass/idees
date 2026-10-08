#!/usr/bin/env python3
"""Materialize an explicitly bounded calendar. Only derived occurrences are rebuilt."""
import argparse
import json
import os
import subprocess
from datetime import datetime, date, timedelta, timezone
from dateutil.rrule import rrulestr
from dateutil.tz import gettz, datetime_exists


def expand(schedule, exceptions, start, end):
    tz = gettz(schedule['timezone'])
    if tz is None:
        raise ValueError('Unknown timezone: ' + schedule['timezone'])
    first = datetime.fromisoformat(schedule['starts_local']).replace(tzinfo=tz)
    finish = datetime.fromisoformat(schedule['ends_local']).replace(tzinfo=tz)
    duration = finish.replace(tzinfo=None) - first.replace(tzinfo=None)
    if duration <= timedelta(0):
        raise ValueError('Schedule duration must be positive')
    lower = datetime.combine(start, datetime.min.time(), tz)
    upper = datetime.combine(end, datetime.min.time(), tz)
    if schedule.get('rrule'):
        rule = rrulestr(schedule['rrule'], dtstart=first)
        candidates = rule.between(lower-duration, upper, inc=True)
    else:
        candidates = [first]
    by_original = {datetime.fromisoformat(x['original_start_local']).isoformat(): x for x in exceptions}
    # Include overridden sessions even if their original date lies outside the window.
    candidates += [datetime.fromisoformat(x['original_start_local']).replace(tzinfo=tz)
                   for x in exceptions if x['action'] in ('include', 'override')]
    output = {}
    for candidate in candidates:
        key = candidate.replace(tzinfo=None).isoformat()
        exception = by_original.get(key)
        if exception and exception['action'] == 'exclude':
            continue
        begin = candidate
        finish = candidate + duration  # preserve local wall-clock span across DST
        status = 'scheduled'
        place = schedule.get('place_id')
        note = None
        if exception:
            note = exception.get('note')
            if exception['action'] == 'cancel':
                status = 'cancelled'
            if exception['action'] in ('override', 'include'):
                begin = datetime.fromisoformat(exception['starts_local']).replace(tzinfo=tz)
                finish = datetime.fromisoformat(exception['ends_local']).replace(tzinfo=tz)
                place = exception.get('place_id') or place
                status = 'rescheduled' if exception['action'] == 'override' else 'scheduled'
        # Invalid spring-forward times are skipped, never shifted silently.
        if not datetime_exists(begin) or not datetime_exists(finish):
            continue
        if finish.astimezone(timezone.utc) <= begin.astimezone(timezone.utc):
            raise ValueError('Non-positive UTC duration')
        if begin < upper and finish > lower:
            output[key] = dict(schedule_id=schedule['id'], original_start_local=key,
                starts_at=begin.isoformat(), ends_at=finish.isoformat(), status=status,
                place_id=place, note=note)
    return list(output.values())


def psql(sql):
    env = dict(os.environ)
    env['PGPASSWORD'] = env.get('DB_PASSWORD', env.get('PGPASSWORD', ''))
    return subprocess.run(['psql','-X','-v','ON_ERROR_STOP=1','-h',env.get('DB_HOST','localhost'),
        '-p',env.get('DB_PORT','5432'),'-U',env.get('DB_USER','htpweb'),'-d',env.get('DB_NAME','idee'),'-At'],
        input=sql,text=True,capture_output=True,check=True,env=env).stdout.strip()


def quote(value):
    if value is None:
        return 'NULL'
    return "'" + str(value).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--from-date', type=date.fromisoformat, default=date.today()-timedelta(days=31))
    parser.add_argument('--until-date', type=date.fromisoformat, default=date.today()+timedelta(days=550))
    args=parser.parse_args()
    if not 0 < (args.until_date-args.from_date).days <= 1096:
        parser.error('Projection window must be between 1 and 1096 days')
    # One transaction: source lock, snapshot, rebuild and metadata are atomic.
    # Data is fetched as JSON through psql; no additional PostgreSQL Python driver needed.
    schedules=json.loads(psql("SELECT COALESCE(json_agg(s ORDER BY s.id),'[]') FROM idee_schedule s WHERE enabled;"))
    exceptions=json.loads(psql("SELECT COALESCE(json_agg(e ORDER BY e.id),'[]') FROM idee_schedule_exception e;"))
    rows=[]
    for schedule in schedules:
        rows += expand(schedule,[e for e in exceptions if e['schedule_id']==schedule['id']],args.from_date,args.until_date)
    # Guard against concurrent source edits between snapshot and write.
    guard_s=quote(json.dumps(schedules, ensure_ascii=False));guard_e=quote(json.dumps(exceptions, ensure_ascii=False))
    statements=['BEGIN;', 'LOCK TABLE idee_schedule, idee_schedule_exception IN SHARE MODE;',
      'LOCK TABLE idee_occurrence, idee_calendar_projection IN EXCLUSIVE MODE;',
      f"CREATE TEMP TABLE idee_projection_guard (ok boolean CHECK(ok)); INSERT INTO idee_projection_guard SELECT (SELECT COALESCE(json_agg(s ORDER BY s.id),'[]')::jsonb FROM idee_schedule s WHERE enabled) = {guard_s}::jsonb AND (SELECT COALESCE(json_agg(e ORDER BY e.id),'[]')::jsonb FROM idee_schedule_exception e) = {guard_e}::jsonb;",
      'DELETE FROM idee_occurrence;']
    for row in rows:
        statements.append('INSERT INTO idee_occurrence ('+','.join(row)+') VALUES ('+','.join(quote(v) for v in row.values())+');')
    statements.append(f"INSERT INTO idee_calendar_projection(singleton,from_date,until_date) VALUES(true,{quote(args.from_date)},{quote(args.until_date)}) ON CONFLICT(singleton) DO UPDATE SET from_date=excluded.from_date,until_date=excluded.until_date,generated_at=now();")
    statements.append('COMMIT;')
    psql('\n'.join(statements))
    print(f'{len(rows)} occurrences projected, [{args.from_date}, {args.until_date}).')

if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr.strip()) from error
