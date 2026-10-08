// Read-only checks; the failure scenario intercepts only a browser response.
const assert = require('node:assert/strict');
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.IDEE_BROWSER_URL || 'http://127.0.0.1:4401';
(async () => {
  const browser = await chromium.launch({headless:true, ...(process.env.IDEE_CHROMIUM_PATH ? {executablePath:process.env.IDEE_CHROMIUM_PATH} : {})});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    page.setDefaultTimeout(60000);
    const errors=[]; page.on('pageerror', e=>errors.push(e.message));
    const get = async params => {
      const response=await page.request.get(base+'/api/catalog?'+new URLSearchParams(params));
      assert.equal(response.status(),200); return response.json();
    };
    const all=await get({period:'',limit:'40'});
    const legacy=await (await page.request.get(base+'/api/outings?limit=40')).json();
    assert.deepEqual(all.items.map(o=>o.id),legacy.map(o=>o.id));
    assert.equal(all.items.length,40); assert(all.total>100); assert(all.hasMore);
    const second=await get({period:'',limit:'40',offset:'40'});
    assert.equal(second.total,all.total);
    assert.equal(new Set([...all.items,...second.items].map(o=>o.id)).size,80);
    const city=await get({period:'',department:'67',city:'Strasbourg'});
    assert(city.total>4); assert(city.items.every(o=>o.city==='Strasbourg' && o.department==='67'));
    const empty=await get({period:'',city:'Strasbourg',department:'68'});
    assert.equal(empty.total,0); assert.equal(empty.hasMore,false);
    const invalid=await page.request.get(base+'/api/catalog?limit=41'); assert.equal(invalid.status(),400);
    await page.goto(base,{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>document.querySelectorAll('.outing-card').length>0);
    assert.equal(await page.getByRole('button',{name:'Aujourd’hui',exact:true}).getAttribute('aria-pressed'),'true');
    assert(await page.locator('.outing-card').count()<=40);
    await page.getByRole('button',{name:'Toutes les dates',exact:true}).click();
    await page.waitForFunction(total=>document.querySelector('.results-count')?.textContent.includes('40 sorties affichées sur '+total),all.total);
    let fail=true;
    await page.route('**/api/catalog?**',route=>{
      if(fail && new URL(route.request().url()).searchParams.get('offset')==='40') {fail=false;return route.fulfill({status:503,body:'unavailable'});}
      return route.continue();
    });
    await page.locator('.load-more').scrollIntoViewIfNeeded();
    await page.getByRole('button',{name:'Réessayer',exact:true}).waitFor();
    assert.equal(await page.locator('.outing-card').count(),40);
    await page.getByRole('button',{name:'Réessayer',exact:true}).click();
    await page.waitForFunction(()=>document.querySelectorAll('.outing-card').length===80);
    assert.equal(new Set(await page.locator('.title-button').evaluateAll(xs=>xs.map(x=>x.href))).size,80);
    await page.getByLabel('Département',{exact:true}).selectOption('67');
    await page.getByLabel('Grande ville',{exact:true}).selectOption('Strasbourg');
    await page.waitForFunction(total=>document.querySelector('.results-count')?.textContent.includes('sur '+total),city.total);
    assert((await page.locator('.outing-card .location').allTextContents()).every(t=>t.includes('Strasbourg')));
    await page.setViewportSize({width:390,height:844});
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.locator('.period-chips').scrollIntoViewIfNeeded();
    await page.screenshot({path:'/tmp/idee-catalog-periods-mobile.png'});
    const weekend=await browser.newPage();
    await weekend.clock.install({time:new Date('2026-09-26T12:00:00+02:00')});
    await weekend.goto(base,{waitUntil:'domcontentloaded'});
    await weekend.getByRole('button',{name:'La semaine prochaine',exact:true}).waitFor();
    assert.equal(await weekend.getByRole('button',{name:'Cette semaine',exact:true}).count(),0);
    const request=weekend.waitForRequest(r=>r.url().includes('/api/catalog?')&&new URL(r.url()).searchParams.get('period')==='next-week');
    await weekend.getByRole('button',{name:'La semaine prochaine',exact:true}).click(); await request;
    assert.deepEqual(errors,[]);
    console.log(`OK: full catalog (${all.total}), Strasbourg (${city.total}), 40-item pages, automatic scroll, retry, filter reset, default today, weekend label, mobile.`);
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
