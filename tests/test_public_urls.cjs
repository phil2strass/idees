// Serve built assets through Playwright interception: no application server or database changes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../idee-front/dist/idee/browser');
const base = 'http://idee.test';
const canonical = '/bas-rhin/strasbourg/exposition-wurth';
const outing = {
  id: 1, slug: 'technical-id', url: canonical, title: 'Exposition Würth', summary: 'Une exposition à découvrir.',
  description: 'Une exposition à découvrir.', kind: 'permanent', city: 'Strasbourg', department: '67',
  placeName: 'Musée', environment: 'indoor', isDemo: false,
  categories: [], images: [], prices: [], schedules: [], occurrences: [],
};
(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.IDEE_CHROMIUM_PATH ? { executablePath: process.env.IDEE_CHROMIUM_PATH } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    let legacyApi = false;
    let legacyDetailCalls = 0;
    const oldOuting = { ...outing };
    delete oldOuting.url;
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== base) return route.abort();
      if (url.pathname === '/api/categories') return route.fulfill({ json: [] });
      if (url.pathname === '/api/catalog') return route.fulfill({ json: { items: [legacyApi ? oldOuting : outing], total: 1, hasMore: false } });
      if (url.pathname === '/api/outings/technical-id') {
        legacyDetailCalls++;
        return route.fulfill({ json: oldOuting });
      }
      if (url.pathname === '/api/outing-by-path') {
        if (legacyApi) return route.fulfill({ status: 404, json: {} });
        return [canonical, '/sorties/technical-id', '/bas-rhin/strasbourg/ancien-titre'].includes(url.searchParams.get('path'))
          ? route.fulfill({ json: outing }) : route.fulfill({ status: 404, json: {} });
      }
      const file = path.join(root, url.pathname);
      if (file.startsWith(root + '/') && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({ path: file });
      return route.fulfill({ path: path.join(root, 'index.csr.html'), contentType: 'text/html' });
    });
    await page.goto(base);
    const link = page.locator('.outing-card .title-button');
    await link.waitFor();
    assert.equal(await link.getAttribute('href'), canonical);
    await link.click();
    await page.waitForURL(base + canonical);
    await page.locator('.outing-heading').waitFor();
    assert.equal(await page.locator('link[rel="canonical"]').getAttribute('href'), base + canonical);
    assert.equal(await page.locator('meta[property="og:url"]').getAttribute('content'), base + canonical);
    for (const legacy of ['/sorties/technical-id', '/bas-rhin/strasbourg/ancien-titre']) {
      await page.goto(base + legacy + '?source=test#dates');
      await page.waitForURL(base + canonical + '?source=test#dates');
      await page.locator('.outing-heading').waitFor();
      assert.equal(await page.locator('link[rel="canonical"]').getAttribute('href'), base + canonical);
    }
    await page.reload();
    await page.locator('.outing-heading').waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.goto(base + '/haut-rhin/strasbourg/exposition-wurth');
    await page.getByText('Cette sortie est introuvable.', { exact: true }).waitFor();
    assert.equal(await page.locator('link[rel="canonical"]').count(), 0);
    await page.goto(base + '/de/bas-rhin/strassburg/ausstellung-wurth');
    await page.getByText('Cette page a pris un autre chemin.', { exact: true }).waitFor();
    legacyApi = true;
    await page.goto(base);
    await link.waitFor();
    assert.equal(await link.getAttribute('href'), '/sorties/technical-id');
    const cardLinks = await page.locator('.outing-card a').evaluateAll(nodes => nodes.map(node => node.getAttribute('href')));
    assert.equal(cardLinks.length, 3);
    assert(cardLinks.every(href => href === '/sorties/technical-id'));
    // The image, title and action must all open a working detail with an older API.
    for (let i = 0; i < cardLinks.length; i++) {
      await page.goto(base);
      await page.locator('.outing-card a').nth(i).click();
      await page.locator('.outing-heading').waitFor();
      assert.equal(new URL(page.url()).pathname, '/sorties/technical-id');
      assert.equal(await page.locator('link[rel="canonical"]').getAttribute('href'), base + '/sorties/technical-id');
      assert.equal(await page.locator('meta[property="og:url"]').getAttribute('content'), base + '/sorties/technical-id');
    }
    assert.equal(legacyDetailCalls, 3);
    await page.goto(base + '/haut-rhin/strasbourg/exposition-wurth');
    await page.getByText('Cette sortie est introuvable.', { exact: true }).waitFor();
    assert.equal(legacyDetailCalls, 3, 'A wrong geographic path must not fall back to slug lookup');
    assert.deepEqual(errors, []);
    console.log('URL browser checks passed: catalogue link, canonical/share metadata, direct access, aliases, query/fragment, reload, wrong geography, unpublished language mobile and all three card links with an older API.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
