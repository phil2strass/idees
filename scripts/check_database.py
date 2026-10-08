#!/usr/bin/env python3
"""Integration checks on an initialized demo database. Constraint probes roll back."""
import json
import os
import subprocess
from urllib.request import urlopen
from project_calendar import psql


def rejects(statement):
    try:
        psql('BEGIN;'+statement+'ROLLBACK;')
    except subprocess.CalledProcessError as e:
        assert 'violates check constraint' in e.stderr, e.stderr
    else:
        raise AssertionError('Invalid row was accepted: '+statement)

rejects("INSERT INTO idee_place(name,city,department) VALUES('Test','Paris','75');")
rejects("INSERT INTO idee_schedule(outing_id,label,starts_local,ends_local) SELECT id,'Test','2026-01-01 18:00','2026-01-01 10:00' FROM idee_outing LIMIT 1;")
rejects("INSERT INTO idee_price(outing_id,label,price_type,amount) SELECT id,'Test','free',NULL FROM idee_outing LIMIT 1;")
rejects("INSERT INTO idee_schedule_exception(schedule_id,original_start_local,action) SELECT id,'2026-01-01 10:00','override' FROM idee_schedule LIMIT 1;")
base=os.environ.get('API_URL','http://127.0.0.1:8087')
with urlopen(base+'/api/outings') as r:
    rows=json.load(r)
assert len(rows)>=6
assert all(o['isDemo'] for o in rows)
market=next(o for o in rows if o['slug']=='marche-createurs')
assert market['occurrences']
assert all(1<=int(x['startsAt'][8:10])<=7 for x in market['occurrences'])
with urlopen(base+'/api/outings/marche-createurs') as r:
    assert json.load(r)['id']==market['id']
with urlopen(base+'/api/calendar') as r:
    assert 'until' in json.load(r)
count=psql('SELECT count(*) FROM idee_occurrence;')
print(f'Integration passed: 4 constraints, catalogue, detail, calendar. {count} occurrences.')
