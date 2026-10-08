// Real SSR HTML and hydration, mocked network only: no server or provider calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../idee-front/dist/idee/browser');
const output = '/tmp/idee-ssr-test';
const outing = JSON.parse(fs.readFileSync(output + '/outing.json', 'utf8'));
const base = 'https://idee.test';
(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.IDEE_CHROMIUM_PATH ? { executablePath: process.env.IDEE_CHROMIUM_PATH } : {}) });
  try {
    const context = await browser.newContext({viewport:{width:390,height:844}});
    const requests = [], errors = [];
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== base) return route.abort();
      if (url.pathname.startsWith('/api/')) {
        requests.push(url);
        if (url.pathname === '/api/categories') return route.fulfill({json:[{slug:'nature', name:'Nature & balades', icon:'trees'}]});
        if (url.pathname === '/api/catalog') return route.fulfill({json:{items:[outing],total:1,hasMore:false}});
        if (url.pathname === '/api/outing-by-path') return route.fulfill({json:outing});
        return route.fulfill({status:404,json:{}});
      }
      const match = url.pathname.match(/^\/(en|de|it|nl|es)(?=\/|$)/);
      const language = match?.[1];
      const bare = language ? url.pathname.slice(3) || '/' : url.pathname;
      if (bare === '/' || bare === outing.url || Object.values(outing.urls || {}).includes(url.pathname)) return route.fulfill({path:output + (bare === '/' ? '/home' : '/detail') + (language ? '-'+language : '') + '.html',contentType:'text/html'});
      const file = path.join(root,url.pathname);
      if (file.startsWith(root+'/') && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({path:file});
      return route.fulfill({status:404,body:''});
    });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base+'/en');
    await page.waitForFunction(() => document.querySelector('.language-picker select')?.value === 'en');
    assert.equal(await page.locator('.outing-card .title-button').textContent(), 'Würth exhibition');
    await page.locator('.period-chips button').filter({hasText:'All dates'}).click();
    await page.locator('input[name="query"]').fill('art');
    await page.waitForTimeout(350);
    await page.locator('.language-picker select').selectOption('de');
    await page.waitForURL(base+'/de');
    await page.waitForFunction(() => document.documentElement.lang === 'de');
    await page.waitForFunction(() => document.querySelector('.outing-card .title-button')?.textContent.includes('Würth-Ausstellung'));
    assert.equal(await page.locator('input[name="query"]').inputValue(),'art');
    assert(requests.some(u => u.searchParams.get('language') === 'de' && u.searchParams.get('period') === '' && u.searchParams.get('query') === 'art'));
    await page.locator('.calendar-button').click();
    await page.getByRole('dialog').waitFor();
    await page.getByRole('button',{name:'Nächster Monat'}).waitFor({state:'visible'});
    await page.locator('mat-calendar .mat-calendar-body-cell:focus').waitFor();
    await page.waitForFunction(() => !document.querySelector('.mat-datepicker-content-animating'));
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({state:'hidden'});
    await page.locator('.outing-card .title-button').click();
    await page.waitForURL(base+outing.urls.de);
    await page.waitForFunction(() => document.querySelector('.intro')?.textContent.includes('Eine vollständige deutsche Beschreibung.'));
    assert.equal(await page.locator('link[rel="canonical"]').getAttribute('href'),base+outing.urls.de);
    await page.locator('.language-picker select').selectOption('it');
    await page.waitForURL(base+outing.urls.it);
    await page.waitForFunction(() => document.querySelector('#outing-title')?.textContent.trim() === 'Mostra Würth');
    assert.equal(await page.locator('.intro').getAttribute('lang'),'it');
    assert((await page.title()).startsWith('Mostra Würth'));
    await page.locator('.language-picker select').selectOption('fr');
    await page.waitForURL(base+outing.url);
    await page.waitForFunction(title => document.querySelector('#outing-title')?.textContent.trim() === title, outing.title);
    assert.equal(await page.locator('.intro').getAttribute('lang'),'fr');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'No horizontal overflow on mobile');
    await page.screenshot({path:output+'/languages-mobile.png', fullPage:false});
    assert.deepEqual(errors,[]);
    await context.close();
    console.log('Language browser checks passed: SSR hydration, mobile picker, filters, calendar, translated cards/detail, URLs, metadata and French return.');
  } finally { await browser.close(); }
})().catch(error => {console.error(error);process.exitCode=1;});
