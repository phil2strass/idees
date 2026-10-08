#!/usr/bin/env python3
"""Pure JSON calendar bridge for the Java API. Never accesses PostgreSQL."""
import json
import sys
from datetime import date
from project_calendar import expand

try:
    payload = json.load(sys.stdin)
    start, end = date.fromisoformat(payload['from']), date.fromisoformat(payload['until'])
    if not 0 < (end-start).days <= 1096:
        raise ValueError('Calendar range must be between 1 and 1096 days')
    rows = []
    for schedule in payload['schedules']:
        rows.extend(expand(schedule, schedule.get('exceptions', []), start, end))
        if len(rows) > 20000:
            raise ValueError('Calendar generates too many occurrences')
    print(json.dumps(rows, ensure_ascii=False))
except (ValueError, KeyError, TypeError, OverflowError) as error:
    print(json.dumps({'error': str(error)}, ensure_ascii=False))
    sys.exit(2)
