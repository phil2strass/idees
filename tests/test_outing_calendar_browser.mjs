// Real SSR and hydration with simulated outing data. No provider or database calls.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { localDay, shiftDay, weekStart } from '../idee-front/src/app/outing-calendar.ts';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
process.env.API_ORIGIN = 'http://api.test';
process.env.PUBLIC_ORIGIN = 'https://idee.test';
const base = 'https://idee.test';
const root = path.resolve('idee-front/dist/idee/browser');
const outing = JSON.parse(fs.readFileSync('/tmp/idee-ssr-test/outing.json','utf8'));
outing.kind = 'event';
const monday = weekStart(localDay(new Date()));
const nextMonth = new Date(monday + 'T12:00:00Z');
nextMonth.setUTCMonth(nextMonth.getUTCMonth() + 1, 1);
const later = nextMonth.toISOString().slice(0,10);
const occurrence = (day, hour = '08', status = 'scheduled') => ({ startsAt: `${day}T${hour}:00:00Z`, endsAt: `${day}T${String(Number(hour)+1).padStart(2,'0')}:00:00Z`, status, allDay:false, timezone:'Europe/Paris',city:'Strasbourg',placeName:'Musée' });
outing.occurrences = [occurrence(monday),occurrence(monday,'16','cancelled'),occurrence(shiftDay(monday,6)),occurrence(shiftDay(monday,7)),occurrence(shiftDay(monday,9)),occurrence(later),occurrence(shiftDay(later,1))];
globalThis.fetch = async input => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  assert.equal(url.origin,'http://api.test');
  if (url.pathname === '/api/outing-by-path') return Response.json(outing);
  if (url.pathname === '/api/categories') return Response.json([]);
  return Response.json({}, {status:404});
};
const { renderRequest } = await import('../idee-front/dist/idee/server/server.mjs');
let html;
async function render() {
  const response = await renderRequest(new Request(base + outing.url));
  assert.equal(response.status,200);
  html = await response.text();
}
await render();
assert(html.includes('Cette semaine'));
const browser = await chromium.launch({headless:true,...(process.env.IDEE_CHROMIUM_PATH?{executablePath:process.env.IDEE_CHROMIUM_PATH}:{})});
try {
  const context = await browser.newContext({viewport:{width:1280,height:1000},timezoneId:'America/New_York'});
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== base) return route.abort();
    if (url.pathname === outing.url) return route.fulfill({body:html,contentType:'text/html'});
    if (url.pathname === '/api/outing-by-path') return route.fulfill({json:outing});
    const file = path.join(root,url.pathname);
    if (file.startsWith(root + '/') && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({path:file});
    return route.fulfill({status:404,body:''});
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror',error => errors.push(error.message));
  await page.goto(base + outing.url);
  assert.equal(await page.locator('#dates .session').count(),3);
  await page.locator('.calendar-toggle').click();
  await page.locator('#outing-calendar').waitFor();
  await page.locator(`[data-date="${monday}"]`).click();
  await page.waitForFunction(day => document.querySelector(`[data-date="${day}"]`)?.getAttribute('aria-pressed') === 'true',monday);
  assert.equal(await page.locator('#dates .session').count(),2,'All times for the selected date');
  assert.equal(await page.locator('#dates .status-tag').count(),1,'Cancelled sessions remain identified');
  assert(await page.locator('.calendar-day:disabled').count() > 0);
  await page.getByRole('button',{name:'Mois suivant',exact:true}).click();
  await page.locator(`[data-date="${later}"]`).click();
  await page.waitForFunction(day => document.querySelector(`[data-date="${day}"]`)?.getAttribute('aria-pressed') === 'true',later);
  assert.equal(await page.locator('#dates .session').count(),1);
  await page.locator('#dates').scrollIntoViewIfNeeded();
  await page.screenshot({path:'/tmp/idee-outing-calendar-desktop.png'});
  for (const width of [390,320]) {
    await page.setViewportSize({width,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),false);
  }
  await page.locator('#outing-calendar').scrollIntoViewIfNeeded();
  await page.screenshot({path:'/tmp/idee-outing-calendar-mobile.png'});
  await page.locator('.calendar-toggle').click();
  await page.locator('#outing-calendar').waitFor({state:'hidden'});
  assert.equal(await page.locator('#dates .session').count(),3);
  outing.occurrences = Array.from({length:5},(_,i) => occurrence(shiftDay(monday,7+i)));
  await render(); await page.reload();
  await page.locator('.calendar-toggle').waitFor();
  assert.equal(await page.locator('#dates .session').count(),0);
  assert((await page.locator('#dates').innerText()).includes('Aucune date prévue cette semaine'));
  await page.locator('.calendar-toggle').click();
  await page.locator('#outing-calendar').waitFor();
  assert.equal(await page.locator('#dates .session').count(),1);
  outing.occurrences = outing.occurrences.slice(0,3);
  await render(); await page.reload();
  await page.locator('#dates .session').first().waitFor();
  assert.equal(await page.locator('.calendar-toggle').count(),0);
  assert.equal(await page.locator('#dates .session').count(),3);
  assert.deepEqual(errors,[]);
  console.log('Outing calendar checks passed: SSR, current week, multiple times, cancellation, month navigation, empty week, short list, timezone and mobile layout.');
} finally { await browser.close(); }
