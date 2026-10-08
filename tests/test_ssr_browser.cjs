// Run test_ssr.mjs first. Serve its actual SSR HTML and built assets without a listener.
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
    const attach = async context => {
      let apiCalls = 0;
      await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== base) return route.abort();
        if (url.pathname.startsWith('/api/')) {
          apiCalls++;
          if (url.pathname === '/api/categories') return route.fulfill({json: []});
          if (url.pathname === '/api/catalog') return route.fulfill({json: {items: [outing], total: 1, hasMore: false}});
          if (url.pathname === '/api/outing-by-path') return route.fulfill({json: outing});
          return route.fulfill({status: 404, json:{}});
        }
        if (url.pathname === '/' || url.pathname === outing.url)
          return route.fulfill({path: output + (url.pathname === '/' ? '/home.html' : '/detail.html'), contentType:'text/html'});
        const file = path.join(root, url.pathname);
        if (file.startsWith(root + '/') && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({path: file});
        return route.fulfill({status:404, body:''});
      });
      return () => apiCalls;
    };
    const noJs = await browser.newContext({javaScriptEnabled:false});
    const noJsCalls = await attach(noJs);
    const plain = await noJs.newPage();
    await plain.goto(base);
    assert.equal(await plain.locator('.outing-card .title-button').textContent(), outing.title);
    await plain.locator('.outing-card .title-button').click();
    assert((await plain.locator('.outing-heading').innerText()).includes('Une présentation complète rendue sur le serveur.'));
    assert.equal(noJsCalls(), 0);
    await noJs.close();

    const context = await browser.newContext({viewport:{width:390,height:844}});
    const apiCalls = await attach(context);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', msg => {if(msg.type() === 'error') errors.push(msg.text());});
    await page.addInitScript(() => {
      const observer = new MutationObserver(() => {
        const heading = document.querySelector('.outing-heading');
        if (heading && !window.serverHeading) window.serverHeading = heading;
      });
      observer.observe(document, {subtree:true, childList:true});
    });
    await page.goto(base + outing.url);
    await page.getByRole('button', {name:'Ouvrir ou fermer la navigation'}).click();
    await page.waitForFunction(() => document.querySelector('.menu-button').getAttribute('aria-expanded') === 'true');
    await page.getByRole('button', {name:'Ouvrir ou fermer la navigation'}).click();
    assert.equal(apiCalls(), 0, 'Hydration must reuse the server API response');
    assert(await page.evaluate(() => window.serverHeading === document.querySelector('.outing-heading')), 'Hydration must preserve the rendered DOM');
    assert.equal(await page.locator('link[rel="canonical"]').count(), 1);
    await page.locator('.breadcrumbs a').first().click();
    await page.locator('.outing-card .title-button').waitFor();
    assert.equal(await page.locator('link[rel="canonical"]').count(), 1);
    assert.equal(await page.locator('link[rel="canonical"]').getAttribute('href'), base + '/');
    await page.getByRole('button', {name:'Toutes les dates', exact:true}).click();
    await page.locator('.outing-card .title-button').click();
    await page.locator('.outing-heading').waitFor();
    assert.equal(await page.locator('link[rel="canonical"]').count(), 1);
    await page.setViewportSize({width:390,height:844});
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(errors, []);
    console.log('SSR browser checks passed: content without JavaScript, hydration without repeated API requests, DOM reuse, navigation, filters, canonical uniqueness and mobile.');
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
