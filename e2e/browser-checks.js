const { chromium } = require('playwright-core');
// Browser checks for the web page (plan 0001, P4): what JVM tests can't see, run in real Chrome.
// Usage: BASE_URL=http://localhost:8000 node browser-checks.js   (the app must already be running)
const BASE = process.env.BASE_URL || 'http://localhost:8000';
const HOST = new URL(BASE).host;
const results = [];
const check = (name, ok, extra = '') => results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  (' + extra + ')' : ''}`);
const SHORT_URL = new RegExp('^' + BASE.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/[A-Za-z0-9]{7}$');
// The Expiry under the Short URL (spec 0004), and the validation message for an invalid Lifetime.
const EXPIRY_LINE = /^Expires on (\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}) UTC$/;
const LIFETIME_MESSAGE = 'expires_in_days must be a whole number of days from 1 to 365.';
// The Expiry shown is the creation instant plus 30 days, to the minute (allowing a minute either side).
const isThirtyDaysOn = (line, createdAt) => {
  const m = EXPIRY_LINE.exec(line || '');
  if (!m) return false;
  const shown = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  return Math.abs(shown - (createdAt + 30 * 24 * 60 * 60 * 1000)) <= 2 * 60 * 1000;
};

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
  check('Short URL appears after submit', SHORT_URL.test(await link.textContent()), await link.textContent());
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

  // Expiring Links (spec 0004): a Lifetime gives an Expiry line; an invalid one a message at its field.
  await page.fill('input[name=url]', 'https://example.com/expiring');
  await page.fill('input[name=expires_in_days]', '30');
  const createdAt = Date.now();
  await page.click('button[type=submit]');
  await page.locator('[data-expiry]').waitFor();
  check('expiring Link: Short URL appears via HTMX', SHORT_URL.test(await link.textContent()), await link.textContent());
  const expiry = await page.textContent('[data-expiry]');
  check('expiring Link: "Expires on YYYY-MM-DD HH:MM UTC" shown via HTMX', EXPIRY_LINE.test(expiry), expiry);
  check('expiring Link: the Expiry is 30 days on', isThirtyDaysOn(expiry, createdAt), expiry);

  await page.fill('input[name=url]', 'https://example.com/bad-lifetime');
  await page.fill('input[name=expires_in_days]', '0');
  await page.click('button[type=submit]');
  await page.locator('#expires_in_days-error').waitFor();
  check('invalid Lifetime: message under the field via HTMX', (await page.textContent('#expires_in_days-error')) === LIFETIME_MESSAGE);
  check('invalid Lifetime: both typed values kept via HTMX',
    (await page.inputValue('input[name=url]')) === 'https://example.com/bad-lifetime' && (await page.inputValue('input[name=expires_in_days]')) === '0');
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

  await p2.fill('input[name=url]', 'https://example.com/no-js-expiring');
  await p2.fill('input[name=expires_in_days]', '30');
  const noJsCreatedAt = Date.now();
  await Promise.all([p2.waitForNavigation(), p2.click('button[type=submit]')]);
  check('no-JS: expiring Link shows the Short URL', SHORT_URL.test(await p2.textContent('[data-short-url]')), await p2.textContent('[data-short-url]'));
  const noJsExpiry = await p2.textContent('[data-expiry]');
  check('no-JS: "Expires on YYYY-MM-DD HH:MM UTC" shown', EXPIRY_LINE.test(noJsExpiry), noJsExpiry);
  check('no-JS: the Expiry is 30 days on', isThirtyDaysOn(noJsExpiry, noJsCreatedAt), noJsExpiry);

  await p2.fill('input[name=url]', 'https://example.com/no-js-bad-lifetime');
  await p2.fill('input[name=expires_in_days]', '0');
  await Promise.all([p2.waitForNavigation(), p2.click('button[type=submit]')]);
  check('no-JS: invalid Lifetime shows the message under the field', (await p2.textContent('#expires_in_days-error')) === LIFETIME_MESSAGE);
  check('no-JS: invalid Lifetime keeps both typed values',
    (await p2.inputValue('input[name=url]')) === 'https://example.com/no-js-bad-lifetime' && (await p2.inputValue('input[name=expires_in_days]')) === '0');

  await browser.close();
  console.log(results.join('\n'));
  process.exit(results.some(r => r.startsWith('FAIL')) ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
