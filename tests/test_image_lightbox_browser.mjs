// Real SSR and hydration with simulated outing data. No provider or database calls.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.IDEE_PLAYWRIGHT_MODULE || 'playwright');
process.env.API_ORIGIN = 'http://api.test';
process.env.PUBLIC_ORIGIN = 'https://idee.test';
const base = 'https://idee.test';
const root = path.resolve('idee-front/dist/idee/browser');
const outing = JSON.parse(fs.readFileSync('/tmp/idee-ssr-test/outing.json','utf8'));

outing.images = [
  {url:'/api/media/images/'+'a'.repeat(64)+'.png',alt:'Vue du musée',credit:'Photographe test',license:'Licence test'},
  {url:'/api/media/images/'+'b'.repeat(64)+'.png',alt:'Deuxième vue'},
];
globalThis.fetch = async input => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  assert.equal(url.origin,'http://api.test');
  if (url.pathname === '/api/outing-by-path') return Response.json(outing);
  return Response.json({}, {status:404});
};
const {renderRequest} = await import('../idee-front/dist/idee/server/server.mjs');
const html = await (await renderRequest(new Request(base + outing.url))).text();
assert(html.includes('Agrandir l’image'));
const browser = await chromium.launch({headless:true,...(process.env.IDEE_CHROMIUM_PATH?{executablePath:process.env.IDEE_CHROMIUM_PATH}:{})});
try {
  const context = await browser.newContext({viewport:{width:1280,height:900}});
  await context.route('**/*',async route => {
    const url = new URL(route.request().url());
    if(url.origin!==base) return route.abort();
    if(url.pathname===outing.url) return route.fulfill({body:html,contentType:'text/html'});
    if(url.pathname==='/api/outing-by-path') return route.fulfill({json:outing});
    if(url.pathname.startsWith('/api/media/images/')) return route.fulfill({body:'<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="1200" height="800" fill="#718960"/><text x="100" y="400" font-size="60" fill="white">Photo de test</text></svg>',contentType:'image/svg+xml'});
    const file=path.join(root,url.pathname);
    if(file.startsWith(root+'/') && fs.existsSync(file) && fs.statSync(file).isFile()) return route.fulfill({path:file});
    return route.fulfill({status:404,body:''});
  });
  const page=await context.newPage();
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(base+outing.url);
  const cover=page.locator('.cover .image-zoom');
  await cover.click();
  const dialog=page.locator('dialog[open]');
  await dialog.waitFor();
  assert.equal(await dialog.locator('img').getAttribute('alt'),'Vue du musée');
  assert((await dialog.locator('figcaption').innerText()).includes('Photographe test'));
  assert.equal(await page.evaluate(()=>document.body.style.overflow),'hidden');
  assert(await dialog.evaluate(el=>el===document.activeElement),'Opening the image does not focus the close button');
  assert.equal(await page.locator('.zoom-hint').count(),0);
  await page.keyboard.press('Tab');
  assert(await dialog.evaluate(el=>el.contains(document.activeElement)),'Focus stays inside the modal');
  await page.screenshot({path:'/tmp/idee-image-lightbox-desktop.png'});
  await page.keyboard.press('Escape');
  await dialog.waitFor({state:'hidden'});
  assert(await cover.evaluate(el=>el===document.activeElement),'Return focus to the clicked image');
  assert.equal(await page.evaluate(()=>document.body.style.overflow),'');
  await cover.focus();await page.keyboard.press('Enter');
  await dialog.waitFor();await page.mouse.click(5,100);
  await dialog.waitFor({state:'hidden'});
  for(const width of [390,320]) {
    await page.setViewportSize({width,height:844});
    await page.locator('.gallery .image-zoom').click();
    await dialog.waitFor();
    assert.equal(await dialog.locator('img').getAttribute('alt'),'Deuxième vue');
    const box=await dialog.locator('img').boundingBox();
    assert(box.x>=0 && box.y>=0 && box.x+box.width<=width && box.y+box.height<=844,'Image fits the viewport');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    if(width===390) await page.screenshot({path:'/tmp/idee-image-lightbox-mobile.png'});
    await dialog.locator('.lightbox-close').click();
    await dialog.waitFor({state:'hidden'});
    assert.equal(await page.evaluate(()=>document.body.style.overflow),'');
  }
  assert.deepEqual(errors,[]);
  console.log('Image lightbox: SSR/hydration, cover/gallery, credits, keyboard, focus, Escape/backdrop/button, scroll restoration and mobile OK.');
} finally {await browser.close();}
