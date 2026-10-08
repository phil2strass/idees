// Render the compiled Angular server directly; no listener, external API or database writes.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
process.env.API_ORIGIN = 'http://api.test';
process.env.PUBLIC_ORIGIN = 'https://idee.test';
const canonical = '/bas-rhin/strasbourg/exposition-wurth';
const outing = {
  id: 1, slug: 'technical-id', url: canonical, title: 'Exposition Würth',
  summary: 'Une exposition à découvrir.', description: 'Description source.',
  descriptions: [{ language: 'fr', description_longue: 'Une présentation complète rendue sur le serveur.', description_courte: 'Résumé de la sortie.' }],
  description_courte: 'Résumé de la sortie.', kind: 'permanent', city: 'Strasbourg', department: '67',
  placeName: 'Musée', environment: 'indoor', isDemo: false,
  translations: [
    ['en', 'Würth exhibition', 'A complete English description.', 'English summary.'],
    ['de', 'Würth-Ausstellung', 'Eine vollständige deutsche Beschreibung.', 'Deutsche Zusammenfassung.'],
    ['it', 'Mostra Würth', 'Una descrizione completa in italiano.', 'Riassunto italiano.'],
    ['nl', 'Würth-tentoonstelling', 'Een volledige Nederlandse beschrijving.', 'Nederlandse samenvatting.'],
    ['es', 'Exposición Würth', 'Una descripción completa en español.', 'Resumen en español.'],
  ].map(([language, title, description_longue, description_courte]) => ({language, title, description_longue, description_courte})),
  categories: [], images: [], prices: [], schedules: [], occurrences: [],
};
outing.urls = {fr:canonical};
for (const translation of outing.translations) {
  const slug = translation.title.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
  outing.urls[translation.language] = `/${translation.language}/bas-rhin/strasbourg/${slug}`;
}
const registered = new Set([canonical, '/sorties/technical-id', '/bas-rhin/strasbourg/ancien-titre', ...Object.values(outing.urls)]);
for (const {language} of outing.translations) {
  registered.add('/'+language+canonical);
  registered.add('/'+language+'/sorties/technical-id');
}
let unavailable = false;
let legacy = false;
globalThis.fetch = async input => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  assert.equal(url.origin, 'http://api.test', 'Only the configured API origin may be fetched');
  if (unavailable) return Response.json({}, { status: 503 });
  const value = legacy ? { ...outing, url: undefined, urls: undefined } : outing;
  if (url.pathname === '/api/categories') return Response.json([]);
  if (url.pathname === '/api/catalog') return Response.json({ items: [value], total: 1, hasMore: false });
  if (url.pathname === '/api/outings/technical-id' && legacy) return Response.json(value);
  if (url.pathname === '/api/outing-by-path' && !legacy && registered.has(url.searchParams.get('path')))
    return Response.json(value);
  return Response.json({}, { status: 404 });
};
const { renderRequest } = await import(process.env.IDEE_SSR_BUNDLE || '../idee-front/dist/idee/server/server.mjs');
const render = path => renderRequest(new Request('https://idee.test' + path));
const output = '/tmp/idee-ssr-test';
await mkdir(output, { recursive: true });
await writeFile(output + '/outing.json', JSON.stringify(outing));
const home = await render('/');
assert.equal(home.status, 200);
const homeHtml = await home.text();
assert(homeHtml.includes('Exposition Würth'), 'Home HTML must contain the catalogue');
assert(homeHtml.includes(`href="${canonical}"`));
await writeFile(output + '/home.html', homeHtml);
const detail = await render(canonical);
assert.equal(detail.status, 200);
const detailHtml = await detail.text();
assert(detailHtml.includes('Une présentation complète rendue sur le serveur.'));
assert.match(detailHtml, /<title>Exposition Würth · Idées de sorties en Alsace<\/title>/);
assert.match(detailHtml, /<link[^>]+rel="canonical"[^>]+href="https:\/\/idee.test\/bas-rhin\/strasbourg\/exposition-wurth"/);
assert.match(detailHtml, /<meta[^>]+property="og:url"[^>]+content="https:\/\/idee.test\/bas-rhin\/strasbourg\/exposition-wurth"/);
assert(detailHtml.includes('Résumé de la sortie.'));
assert(detailHtml.includes('ngh='), 'Hydration annotations must be present');
assert(!detailHtml.includes('http://api.test'), 'Internal API origin must not leak to the HTML');
await writeFile(output + '/detail.html', detailHtml);
// Language must be correct in the initial HTML, including simultaneous requests.
for (const translation of outing.translations) {
  const {language, title, description_longue, description_courte} = translation;
  const [homeResponse, detailResponse] = await Promise.all([render('/'+language), render(outing.urls[language])]);
  assert.equal(homeResponse.status, 200, language);
  assert.equal(detailResponse.status, 200, language);
  const homeContent = await homeResponse.text(), content = await detailResponse.text();
  assert.match(homeContent, new RegExp(`<html[^>]*lang="${language}"`));
  assert(homeContent.includes(title));
  assert(homeContent.includes(description_courte));
  assert(homeContent.includes(`href="${outing.urls[language]}"`));
  assert(content.includes(description_longue));
  assert(content.includes(`<title>${title} · `));
  assert.match(content, new RegExp(`rel="canonical"[^>]+href="https://idee.test${outing.urls[language]}"`));
  assert(content.includes('hreflang="x-default"'));
  assert(content.includes('hreflang="fr"'));
  assert(!content.includes('t(t('));
  const oldPrefix = await render('/'+language+canonical);
  assert.equal(oldPrefix.status,301);
  assert.equal(oldPrefix.headers.get('location'),outing.urls[language]);
  const alias = await render('/'+language+'/sorties/technical-id?from=test');
  assert.equal(alias.status, 301);
  assert.equal(alias.headers.get('location'), outing.urls[language]+'?from=test');
  await writeFile(output + '/home-'+language+'.html', homeContent);
  await writeFile(output + '/detail-'+language+'.html', content);
}
const available = outing.translations;
outing.translations = [];
const fallbackResponse = await render(outing.urls.de);
assert.equal(fallbackResponse.status,200);
const fallbackContent = await fallbackResponse.text();
assert.match(fallbackContent, /<html[^>]*lang="de"/);
assert(fallbackContent.includes('class="intro" lang="fr"'));
assert(fallbackContent.includes('Une présentation complète rendue sur le serveur.'));
outing.translations = available;
for (const path of ['/sorties/technical-id', '/bas-rhin/strasbourg/ancien-titre', canonical + '/']) {
  const response = await render(path);
  assert.equal(response.status, 301, path);
  assert.equal(response.headers.get('location'), canonical);
}
for (const path of ['/missing', '/haut-rhin/strasbourg/exposition-wurth', '/de/bas-rhin/strassburg/ausstellung']) {
  const response = await render(path);
  assert.equal(response.status, 404, path);
  const html = await response.text();
  assert(!html.includes('rel="canonical"'));
}
const spoof = await renderRequest(new Request('https://untrusted.test' + canonical, { headers: {'X-Forwarded-Host': 'evil.test'} }));
assert((await spoof.text()).includes('https://idee.test' + canonical));
const localImage = '/api/media/images/' + 'a'.repeat(64) + '.png';
outing.images = [{url:localImage,alt:'Photo locale',credit:'Photographe'}];
const localImageHtml = await (await render(canonical)).text();
assert(localImageHtml.includes(`src="${localImage}"`));
assert.match(localImageHtml, new RegExp(`property="og:image"[^>]+content="https://idee.test${localImage}"`));
const namedImage='/api/media/images/exposition-wurth-strasbourg-'+'a'.repeat(16)+'.png';
outing.images=[{url:namedImage,alt:outing.title,credit:'Photographe'}];
const namedImageHtml=await (await render(canonical)).text();
assert(namedImageHtml.includes(`src="${namedImage}"`));
assert.match(namedImageHtml,new RegExp(`property="og:image"[^>]+content="https://idee.test${namedImage}"`));
const localizedImageHtml=await (await render(outing.urls.en)).text();
assert(localizedImageHtml.includes(`src="${namedImage}"`));
assert(localizedImageHtml.includes('alt="Würth exhibition"'));
outing.images[0].alt='Vue de la façade';
assert((await (await render(outing.urls.en)).text()).includes('alt="Vue de la façade"'));
outing.images = [];
// Attribution must name the original producer, even when presentation texts were rewritten.
outing.sourceDetails = {
  updatedOn:'2026-09-24', updatedAt:'2026-09-25T08:00:00Z',
  contacts:[{role:'creator',name:'Office de tourisme du Test',channels:[]}],
  translations:[],terms:[],locations:[],resources:[],
};
const attributed = await (await render(canonical)).text();
assert(attributed.includes('class="source-attribution"'));
assert(attributed.includes('Office de tourisme du Test'));
assert(attributed.includes('datetime="2026-09-24"'));
assert(attributed.includes('24 septembre 2026'));
assert(attributed.includes('Textes adaptés et, selon la langue, traduits pour ce site.'));
assert(!attributed.includes('À propos de cette fiche'));
const englishAttribution = await (await render(outing.urls.en)).text();
assert(englishAttribution.includes('Source last updated:'));
assert(englishAttribution.includes('September 24, 2026'));
outing.sourceDetails.updatedOn = null;
const fallbackAttribution = await (await render(canonical)).text();
assert(fallbackAttribution.includes('datetime="2026-09-25"'));
outing.sourceDetails.updatedAt = null;
const undatedAttribution = await (await render(canonical)).text();
assert(!undatedAttribution.includes('Dernière mise à jour de la source :'));
delete outing.sourceDetails;
assert(!(await (await render(canonical)).text()).includes('class="source-attribution"'));
unavailable = true;
for (const path of ['/', canonical]) {
  const response = await render(path);
  assert.equal(response.status, 503, path);
}
unavailable = false;
legacy = true;
const old = await render('/sorties/technical-id');
assert.equal(old.status, 200);
assert((await old.text()).includes('Une présentation complète rendue sur le serveur.'));
assert.equal((await render(canonical)).status, 404);
assert.equal((await renderRequest(new Request('https://idee.test/', {method:'POST'}))).status, 405);
assert.equal((await render('/assets/idee-icon.svg')).status, 200);
assert.equal((await render('/assets/missing.svg')).status, 404);
assert.equal((await render('/assets/%2e%2e%2f%2e%2e%2fserver/server.mjs')).status, 404);
console.log('SSR HTML checks passed: catalogue, content, metadata, hydration state, 301/404/503, legacy API and trusted public origin.');
