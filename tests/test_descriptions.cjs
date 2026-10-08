// Uses the live API, or an explicit persisted DB fixture to test the new UI before API restart.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.IDEE_BROWSER_URL || 'http://127.0.0.1:4401';
const slug = 'datatourisme-0d8c9292-f0d7-349a-b90b-00c4987cd936';

(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.IDEE_CHROMIUM_PATH ? { executablePath: process.env.IDEE_CHROMIUM_PATH } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    let outing;
    if (process.env.IDEE_DESCRIPTION_FIXTURE) {
      outing = JSON.parse(fs.readFileSync(process.env.IDEE_DESCRIPTION_FIXTURE, 'utf8'));
      outing.url ||= '/sorties/' + slug;
      await page.route('**/api/outing-by-path?**', route => route.fulfill({ json: outing }));
    } else {
      const response = await page.request.get(base + '/api/outings/' + slug);
      assert.equal(response.status(), 200);
      outing = await response.json();
    }
    const french = outing.descriptions.find(t => t.language === 'fr');
    assert(french.description_longue);
    assert([...french.description_courte].length <= 300);
    // Render a catalogue containing the selected real outing, independent of today's date.
    await page.route('**/api/catalog?**', route => route.fulfill({
      json: { items: [outing], total: 1, hasMore: false },
    }));
    await page.goto(base);
    await page.locator('.outing-card .summary').waitFor();
    assert.equal((await page.locator('.outing-card .summary').textContent()).trim(), french.description_courte);
    await page.locator('.outing-card').screenshot({ path: '/tmp/idee-description-card.png' });
    await page.locator('.outing-card .title-button').click();
    await page.locator('.outing-heading .intro').waitFor();
    assert.equal((await page.locator('.outing-heading .intro').textContent()).trim(), french.description_longue);
    assert.equal(await page.locator('#description-language, .outing-body #presentation').count(), 0);
    assert.equal(await page.getByRole('heading', { name: 'Au programme', exact: true }).count(), 0);
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: '/tmp/idee-mistral-mobile.png', fullPage: true });
    assert.deepEqual(errors, []);
    console.log('Descriptions : vignette courte, texte long français en haut, absence de doublon et de sélecteur de langue, affichage mobile vérifiés.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
