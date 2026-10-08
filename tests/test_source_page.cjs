// Read-only browser checks against a running local preview. Fixtures only intercept browser responses.
const assert = require('node:assert/strict');
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.IDEE_BROWSER_URL || 'http://127.0.0.1:4401';
const slug = 'datatourisme-000c8ab9-8f93-34eb-86b0-5356e3594e7c';

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.IDEE_CHROMIUM_PATH ? { executablePath: process.env.IDEE_CHROMIUM_PATH } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const response = await page.request.get(base + '/api/outings/' + slug);
    assert.equal(response.status(), 200);
    const outing = await response.json();
    assert(outing.sourceDetails.translations.length > 1);
    await page.goto(base + '/sorties/' + slug);
    await page.locator('#contacts').waitFor();
    assert(await page.locator('#contacts a[href^="tel:"]').count() > 0);
    assert.equal(await page.locator('.source-details, .source-note').count(), 0);
    const french = outing.sourceDetails.translations.find(t => t.language === 'fr');
    const rewritten = outing.descriptions?.find(t => t.language === 'fr');
    assert.equal((await page.locator('.outing-heading .intro').textContent()).trim(), rewritten?.description_longue || rewritten?.description || french.description || french.summary);
    assert.equal(await page.locator('#description-language, .outing-body #presentation').count(), 0);
    await page.screenshot({ path: '/tmp/idee-source-desktop.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('#contacts').scrollIntoViewIfNeeded();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: '/tmp/idee-source-mobile.png' });

    // Test optional sections and unsafe URLs without creating a fake outing in PostgreSQL.
    const fixture = structuredClone(outing);
    fixture.slug = 'browser-fixture'; fixture.url = '/sorties/browser-fixture';
    fixture.sourceDetails.resources = [
      { url: 'https://example.org/programme.pdf', title: 'Programme de la sortie', mediaType: 'application/pdf', credit: 'Éditeur', license: null },
      { url: 'javascript:alert(1)', title: 'Unsafe resource', mediaType: null },
    ];
    fixture.sourceDetails.locations.push({ name: 'Deuxième lieu', address: '2 rue du Test', postalCode: '68000', city: 'Colmar', department: '68', latitude: null, longitude: null });
    fixture.sourceDetails.contacts.push({ role: 'contact', name: '<script>alert(1)</script>', channels: [
      { kind: 'homepage', value: 'javascript:alert(1)' }, { kind: 'email', value: 'contact@example.org' },
      { kind: 'email', value: 'contact@example.org?bcc=other@example.org' },
    ] });
    await page.route('**/api/outing-by-path?path=**browser-fixture', route => route.fulfill({ json: fixture }));
    await page.goto(base + '/sorties/browser-fixture');
    await page.locator('#documents').waitFor();
    assert.equal(await page.locator('#documents a').count(), 1);
    assert.equal(await page.locator('a[href^="javascript:"], a[href^="unsafe:"]').count(), 0);
    assert.equal(await page.locator('#contacts script').count(), 0);
    assert.equal(await page.locator('#contacts a[href^="mailto:"]').count(), 1);
    assert.equal(await page.locator('#pratique .detail-card').count(), 2);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.locator('#documents').scrollIntoViewIfNeeded();
    await page.screenshot({ path: '/tmp/idee-source-documents-mobile.png' });

    const englishOnly = structuredClone(outing);
    englishOnly.slug = 'browser-english'; englishOnly.url = '/sorties/browser-english';
    englishOnly.sourceDetails.translations = englishOnly.sourceDetails.translations.filter(t => t.language === 'en');
    englishOnly.descriptions = (englishOnly.descriptions || []).filter(t => t.language === 'en');
    await page.route('**/api/outing-by-path?path=**browser-english', route => route.fulfill({ json: englishOnly }));
    await page.goto(base + '/sorties/browser-english');
    await page.locator('.outing-heading .intro').waitFor();
    assert.equal((await page.locator('.outing-heading .intro').textContent()).trim(), 'Description en français non disponible.');

    const empty = structuredClone(outing);
    empty.slug = 'browser-empty'; empty.url = '/sorties/browser-empty'; empty.sourceDetails = null; empty.sourceUrl = null;
    await page.route('**/api/outing-by-path?path=**browser-empty', route => route.fulfill({ json: empty }));
    await page.goto(base + '/sorties/browser-empty');
    await page.locator('#presentation').waitFor();
    assert.equal(await page.locator('#contacts, #documents, .source-details, #description-language').count(), 0);
    assert((await page.locator('.outing-heading .intro').innerText()).length > 0);
    assert.deepEqual(errors, []);
    console.log('Browser checks passed: real page, French description, contacts, documents, multiple locations, mobile layout, safe links and missing data.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
