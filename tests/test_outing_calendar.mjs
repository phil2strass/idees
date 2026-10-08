import assert from 'node:assert/strict';
import { test } from 'node:test';
import { localDay, occurrenceRange, shiftDay, weekStart } from '../idee-front/src/app/outing-calendar.ts';

test('weeks start on Monday, including year boundaries and Sundays', () => {
  assert.equal(weekStart('2026-01-01'), '2025-12-29');
  assert.equal(weekStart('2026-10-11'), '2026-10-05');
  assert.equal(weekStart('2026-10-12'), '2026-10-12');
  assert.equal(shiftDay('2026-02-28', 1), '2026-03-01');
});
test('today follows the outing timezone rather than the browser timezone', () => {
  const now = new Date('2026-10-11T22:30:00Z');
  assert.equal(localDay(now, 'Europe/Paris'), '2026-10-12');
  assert.equal(localDay(now, 'America/New_York'), '2026-10-11');
});
test('multi-day sessions exclude their midnight end across daylight saving changes', () => {
  assert.deepEqual(occurrenceRange({ startsAt: '2026-03-28T23:00:00Z', endsAt: '2026-03-30T22:00:00Z', timezone: 'Europe/Paris' }), ['2026-03-29','2026-03-30']);
  assert.deepEqual(occurrenceRange({ startsAt: '2026-10-25T22:00:00Z', endsAt: '2026-10-26T02:00:00Z', timezone: 'Europe/Paris' }), ['2026-10-25','2026-10-26']);
});
