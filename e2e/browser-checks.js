const { chromium } = require('playwright-core');
// Browser checks for the web page (plan 0001, P4): what JVM tests can't see, run in real Chrome.
// Usage: BASE_URL=http://localhost:8000 node browser-checks.js   (the app must already be running)
const BASE = process.env.BASE_URL || 'http://localhost:8000';
const HOST = new URL(BASE).host;
const results = [];
const check = (name, ok, extra = '') => results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  (' + extra + ')' : ''}`);

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  console.log('Browser checks against ' + BASE + ' with ' + browser.version());

  // --- JavaScript on: HTMX path ---
  const ctx = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('pageerror', e => consoleErrors.push(e.message));
  const notFound = [];
  page.on('response', r => { if (r.status() === 404) notFound.push(new URL(r.url()).pathname); });
  let navigations = 0;
  page.on('framenavigated', f => { if (f === page.mainFrame()) navigations++; });

  await page.goto(BASE + '/');
  const navAfterLoad = navigations;
  check('copy button hidden before any Link exists', await page.locator('button[data-copy]').count() === 0);

  await page.fill('input[name=url]', 'https://example.com/very/long');
  await page.click('button[type=submit]');
  const link = page.locator('[data-short-url]');
  await link.waitFor();
  check('Short URL appears after submit', new RegExp('^' + BASE.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/[A-Za-z0-9]{7}$').test(await link.textContent()), await link.textContent());
  check('no full page reload (HTMX swap)', navigations === navAfterLoad, `navigations=${navigations - navAfterLoad}`);
  const copy = page.locator('button[data-copy]');
  check('copy button revealed by JavaScript', await copy.waitFor({ state: 'visible', timeout: 2000 }).then(() => true, () => false));
  await copy.click();
  await page.waitForFunction(() => document.querySelector('button[data-copy]').textContent.includes('Copied'));
  check('copy button confirms "Copied ✓"', (await copy.textContent()).includes('Copied ✓'));
  const clip = await page.evaluate(() => navigator.clipboard.readText());
  check('clipboard holds the Short URL', clip === await link.textContent(), clip);
  await page.waitForFunction(() => document.getElementById('status').textContent.length > 0);
  check('status region announces', (await page.textContent('#status')).length > 0, await page.textContent('#status'));

  await page.fill('input[name=url]', 'ftp://example.com/file');
  await page.click('button[type=submit]');
  await page.locator('#url-error').waitFor();
  check('422 Rejection Reason shown inline via HTMX', (await page.textContent('#url-error')) === 'Only http:// and https:// web addresses can be shortened.');
  check('typed value preserved after rejection', (await page.inputValue('input[name=url]')) === 'ftp://example.com/file');
  check('focus returns to the field after rejection', await page.evaluate(() => document.activeElement && document.activeElement.name === 'url'));
  check('still no full page reload', navigations === navAfterLoad);
  check('no JavaScript errors', consoleErrors.length === 0, consoleErrors.join(' | '));
  check('no 404s while using the page', notFound.length === 0, JSON.stringify([...new Set(notFound)]));
  check('favicon is served', (await page.request.get(BASE + '/favicon.ico')).status() === 200);

  // --- JavaScript off: plain HTML path ---
  const noJs = await browser.newContext({ javaScriptEnabled: false });
  const p2 = await noJs.newPage();
  await p2.goto(BASE + '/');
  await p2.fill('input[name=url]', 'https://example.com/no-js');
  await Promise.all([p2.waitForNavigation(), p2.click('button[type=submit]')]);
  check('no-JS: form posts and shows the Short URL', /\/[A-Za-z0-9]{7}$/.test(await p2.textContent('[data-short-url]')));
  check('no-JS: copy button stays hidden', !(await p2.locator('button[data-copy]').isVisible()));

  await browser.close();
  console.log(results.join('\n'));
  process.exit(results.some(r => r.startsWith('FAIL')) ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
