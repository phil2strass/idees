import unittest
from datetime import date, datetime
from project_calendar import expand

class CalendarTest(unittest.TestCase):
    def schedule(self,rule=None,start='2026-01-03T10:00:00',end='2026-01-03T18:00:00'):
        return dict(id=1,timezone='Europe/Paris',starts_local=start,ends_local=end,rrule=rule,place_id=None)
    def test_first_saturday(self):
        rows=expand(self.schedule('FREQ=MONTHLY;BYDAY=1SA'),[],date(2026,1,1),date(2026,4,1))
        self.assertEqual([r['original_start_local'][:10] for r in rows],['2026-01-03','2026-02-07','2026-03-07'])
    def test_last_sunday_and_dst(self):
        rows=expand(self.schedule('FREQ=MONTHLY;BYDAY=-1SU', '2026-01-25T10:00:00','2026-01-25T18:00:00'),[],date(2026,1,1),date(2026,5,1))
        self.assertTrue(rows[1]['starts_at'].endswith('+01:00'))
        self.assertEqual(rows[2]['starts_at'],'2026-03-29T10:00:00+02:00')
    def test_multiday_overlap(self):
        rows=expand(self.schedule(None,'2026-06-01T10:00:00','2026-06-04T18:00:00'),[],date(2026,6,3),date(2026,6,4))
        self.assertEqual(len(rows),1)
    def test_half_open_boundary(self):
        rows=expand(self.schedule(None,'2026-06-01T00:00:00','2026-06-02T00:00:00'),[],date(2026,6,2),date(2026,6,3))
        self.assertEqual(rows,[])
    def test_cancellation_exclusion_and_extra(self):
        s=self.schedule('FREQ=DAILY;COUNT=3')
        e=[dict(original_start_local='2026-01-03T10:00:00',action='exclude'),dict(original_start_local='2026-01-04T10:00:00',action='cancel'),dict(original_start_local='2026-01-08T10:00:00',action='include',starts_local='2026-01-08T10:00:00',ends_local='2026-01-08T12:00:00')]
        rows=expand(s,e,date(2026,1,1),date(2026,2,1))
        self.assertEqual(len(rows),3)
        self.assertEqual(rows[0]['status'],'cancelled')
    def test_moved_from_outside_window(self):
        e=[dict(original_start_local='2026-01-03T10:00:00',action='override',starts_local='2026-06-08T10:00:00',ends_local='2026-06-08T12:00:00',place_id=2)]
        rows=expand(self.schedule(),e,date(2026,6,1),date(2026,7,1))
        self.assertEqual(len(rows),1)
        self.assertEqual(rows[0]['status'],'rescheduled')
        self.assertEqual(rows[0]['place_id'],2)
    def test_invalid_month_day(self):
        rows=expand(self.schedule('FREQ=MONTHLY;BYMONTHDAY=31','2026-01-31T10:00:00','2026-01-31T12:00:00'),[],date(2026,1,1),date(2026,4,1))
        self.assertEqual(len(rows),2)
    def test_all_day_dst(self):
        rows=expand(self.schedule(None,'2026-03-29T00:00:00','2026-03-30T00:00:00'),[],date(2026,3,1),date(2026,4,1))
        r=rows[0];self.assertEqual((datetime.fromisoformat(r['ends_at'])-datetime.fromisoformat(r['starts_at'])).total_seconds()/3600,23)
    def test_nonexistent_hour(self):
        rows=expand(self.schedule('FREQ=DAILY;COUNT=3','2026-03-28T02:30:00','2026-03-28T03:30:00'),[],date(2026,3,28),date(2026,3,31))
        self.assertEqual(len(rows),2)

if __name__=='__main__': unittest.main()
