"""Run ONLY against an isolated, initially empty installation (creates test outings)."""
import copy
import json
import os
import time
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = os.environ.get('IDEE_API_URL', 'http://127.0.0.1:9082')
TOKEN = os.environ['IDEE_IMPORT_TOKEN']

def request(path, body=None, token=None):
    # Keep integration tests below the production proxy write rate limit.
    time.sleep(.55 if body is not None else .22)
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    try:
        with urlopen(req,timeout=40) as r:return r.status,json.load(r)
    except HTTPError as e:return e.code,json.load(e)

def outing(ref='api-monthly'):
    return dict(sourceName='integration-test',externalId=ref,sourceUrl='https://example.org/verified-test',
        slug=ref,title='Sortie de test isolée',summary='Test uniquement',description='Ne doit jamais être chargé sur le serveur public.',
        kind='event',place=dict(name='Lieu de test',city='Strasbourg',department='67'),categorySlugs=['culture'],
        prices=[dict(label='Entrée',type='free',amount=0)],
        schedules=[dict(label='Premier samedi',startsLocal='2027-01-02T10:00:00',endsLocal='2027-01-02T18:00:00',rrule='FREQ=MONTHLY;BYDAY=1SA')])

class ApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get('IDEE_ALLOW_TEST_WRITES')!='isolated':
            raise RuntimeError('Set IDEE_ALLOW_TEST_WRITES=isolated for the disposable database only.')
    def test_01_empty_install(self):
        code,rows=request('/api/outings');self.assertEqual(code,200);self.assertEqual(rows,[])
        code,rows=request('/api/categories');self.assertEqual(len(rows),6)
        self.assertEqual(request('/api/outings?date=2027-01-02')[1],[])
    def test_02_auth(self):
        self.assertEqual(request('/api/admin/outings',outing())[0],401)
        self.assertEqual(request('/api/admin/outings',outing(),token='wrong')[0],401)
        self.assertIn(request('/api/admin;ignored/outings',outing())[0],(401,404))
    def test_03_create_retry_conflict(self):
        code,result=request('/api/admin/outings',outing(),TOKEN);self.assertEqual(code,201,result)
        code,again=request('/api/admin/outings',outing(),TOKEN);self.assertEqual(code,200,again);self.assertFalse(again['created']);self.assertEqual(again['id'],result['id'])
        changed=outing();changed['title']='Contenu différent'
        self.assertEqual(request('/api/admin/outings',changed,TOKEN)[0],409)
    def test_04_daily_and_far_future(self):
        code,rows=request('/api/outings?date=2027-02-06');self.assertEqual(code,200,rows);self.assertEqual(len(rows),1)
        self.assertEqual(rows[0]['occurrences'][0]['startsAt'],'2027-02-06T10:00:00+01:00')
        self.assertEqual(request('/api/outings?date=2027-02-07')[1],[])
        # Beyond the materialized horizon: must still resolve the monthly recurrence.
        self.assertEqual(len(request('/api/outings?date=2030-01-05')[1]),1)
        self.assertEqual(request('/api/outings?date=oops')[0],400)
        self.assertEqual(request('/api/outings?limit=999')[0],400)
    def test_05_exceptions_and_overnight(self):
        data=outing('api-exceptions')
        data['schedules'][0]['exceptions']=[dict(originalStartLocal='2027-02-06T10:00:00',action='cancel'),dict(originalStartLocal='2027-03-06T10:00:00',action='override',startsLocal='2027-03-07T10:00:00',endsLocal='2027-03-08T02:00:00',place=dict(name='Autre lieu',city='Colmar',department='68'))]
        code,r=request('/api/admin/outings',data,TOKEN);self.assertEqual(code,201,r)
        self.assertEqual(len(request('/api/outings?date=2027-02-06')[1]),1)
        cancelled=request('/api/outings?date=2027-02-06&includeCancelled=true')[1];self.assertEqual(len(cancelled),2)
        self.assertEqual(next(r for r in cancelled if r['slug']=='api-exceptions')['occurrences'][0]['status'],'cancelled')
        moved=request('/api/outings?date=2027-03-08')[1];self.assertEqual(len(moved),1)
        self.assertEqual(moved[0]['occurrences'][0]['city'],'Colmar')
        self.assertEqual(request('/api/outings?date=2027-03-09')[1],[])
    def test_06_invalid_rollback(self):
        data=outing('api-invalid');data['schedules'][0]['rrule']='FREQ=MONTHLY;BYDAY=NOPE'
        code,r=request('/api/admin/outings',data,TOKEN);self.assertEqual(code,400,r)
        self.assertEqual(request('/api/outings/api-invalid')[0],404)
        data['schedules'][0]['rrule']=None;data['categorySlugs']=['inconnue']
        self.assertEqual(request('/api/admin/outings',data,TOKEN)[0],400)
        data=outing('api-invalid-price');data['prices'][0]['amount']=None
        self.assertEqual(request('/api/admin/outings',data,TOKEN)[0],400)
        data=outing('api-invalid-url');data['sourceUrl']='not-a-url'
        self.assertEqual(request('/api/admin/outings',data,TOKEN)[0],400)
    def test_07_multiday_permanent_draft(self):
        data=outing('api-multiday');data['schedules']=[dict(label='Trois journées entières',startsLocal='2027-06-01T00:00:00',endsLocal='2027-06-04T00:00:00',allDay=True)]
        self.assertEqual(request('/api/admin/outings',data,TOKEN)[0],201)
        self.assertEqual(len(request('/api/outings?date=2027-06-03')[1]),1)
        self.assertEqual(request('/api/outings?date=2027-06-04')[1],[])
        data=outing('api-permanent');data['kind']='permanent';data['schedules']=[]
        self.assertEqual(request('/api/admin/outings',data,TOKEN)[0],201)
        rows=request('/api/outings?date=2027-06-04&includePermanent=true')[1];self.assertEqual(len(rows),1);self.assertEqual(rows[0]['availability'],'to_confirm')
        data=outing('api-draft');data['status']='draft'
        self.assertEqual(request('/api/admin/outings',data,TOKEN)[0],201)
        self.assertEqual(request('/api/outings/api-draft')[0],404)
    def test_08_images_and_primary_cover(self):
        data=outing('api-images')
        data['images']=[
            dict(url='https://example.org/cover.jpg',alt='Affiche de la sortie',credit='Organisateur',primary=True),
            dict(url='https://example.org/gallery.jpg',alt='Vue du lieu',license='CC BY 4.0',primary=False),
        ]
        code,result=request('/api/admin/outings',data,TOKEN);self.assertEqual(code,201,result)
        code,detail=request('/api/outings/api-images');self.assertEqual(code,200,detail)
        self.assertEqual(len(detail['images']),2)
        self.assertTrue(detail['images'][0]['primary'])
        invalid=outing('api-images-invalid')
        invalid['images']=[dict(url='https://example.org/a.jpg',alt='A',primary=True),dict(url='https://example.org/b.jpg',alt='B',primary=True)]
        self.assertEqual(request('/api/admin/outings',invalid,TOKEN)[0],400)
        insecure=outing('api-images-http')
        insecure['images']=[dict(url='http://example.org/a.jpg',alt='A',primary=True)]
        self.assertEqual(request('/api/admin/outings',insecure,TOKEN)[0],400)
    def test_09_add_images_to_existing_outing(self):
        payload={'images':[dict(url='https://example.org/added-cover.jpg',alt='Image ajoutée',credit='Organisateur',primary=True)]}
        code,result=request('/api/admin/outings/api-monthly/images',payload,TOKEN);self.assertEqual(code,201,result);self.assertEqual(result['added'],1)
        code,retry=request('/api/admin/outings/api-monthly/images',payload,TOKEN);self.assertEqual(code,200,retry);self.assertEqual(retry['added'],0)
        self.assertTrue(request('/api/outings/api-monthly')[1]['images'][0]['primary'])
        extra={'images':[dict(url='https://example.org/second-primary.jpg',alt='Autre principale',primary=True)]}
        self.assertEqual(request('/api/admin/outings/api-monthly/images',extra,TOKEN)[0],400)

if __name__=='__main__':unittest.main(verbosity=2)
