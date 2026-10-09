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
// The Manage Token on the create result (spec 0005): 43 base64url characters, and its note.
const MANAGE_TOKEN = /^[A-Za-z0-9_-]{43}$/;
const KEEP_IT_SAFE = "Keep this safe: it's the only way to see this Link's stats, and it won't be shown again.";
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
  const requestedUrls = [];
  page.on('request', r => requestedUrls.push(r.url()));

  await page.goto(BASE + '/');
  const navAfterLoad = navigations;
  check('copy button hidden before any Link exists', await page.locator('button[data-copy]').count() === 0);

  await page.fill('input[name=url]', 'https://example.com/very/long');
  await page.click('button[type=submit]');
  const link = page.locator('[data-short-url]');
  await link.waitFor();
  check('Short URL appears after submit', SHORT_URL.test(await link.textContent()), await link.textContent());
  check('no full page reload (HTMX swap)', navigations === navAfterLoad, `navigations=${navigations - navAfterLoad}`);
  const copy = page.locator('.short-url button[data-copy]');
  check('copy button revealed by JavaScript', await copy.waitFor({ state: 'visible', timeout: 2000 }).then(() => true, () => false));
  await copy.click();
  await page.waitForFunction(() => document.querySelector('.short-url button[data-copy]').textContent.includes('Copied'));
  check('copy button confirms "Copied ✓"', (await copy.textContent()).includes('Copied ✓'));
  const clip = await page.evaluate(() => navigator.clipboard.readText());
  check('clipboard holds the Short URL', clip === await link.textContent(), clip);
  await page.waitForFunction(() => document.getElementById('status').textContent.length > 0);
  check('status region announces', (await page.textContent('#status')).length > 0, await page.textContent('#status'));

  // The Manage Token (spec 0005, story 3): shown once, copied through copy.js, never in a URL.
  const token = await page.inputValue('input[data-manage-token]');
  check('Manage Token shown in a read-only field', MANAGE_TOKEN.test(token) && await page.locator('input[data-manage-token]').getAttribute('readonly') !== null, token);
  check('keep-it-safe note shown', (await page.textContent('#manage-token-note')) === KEEP_IT_SAFE);
  const copyToken = page.locator('.manage-token button[data-copy]');
  check('Manage Token copy button revealed by JavaScript', await copyToken.waitFor({ state: 'visible', timeout: 2000 }).then(() => true, () => false));
  await copyToken.click();
  await page.waitForFunction(() => document.querySelector('.manage-token button[data-copy]').textContent.includes('Copied'));
  const tokenClip = await page.evaluate(() => navigator.clipboard.readText());
  check('clipboard holds the Manage Token', tokenClip === token, tokenClip);
  check('status region announces the Manage Token was copied', await page.waitForFunction(() =>
    document.getElementById('status').textContent === 'Manage Token copied to the clipboard.', null, { timeout: 2000 })
    .then(() => true, () => false), await page.textContent('#status'));
  check('Manage Token not in the page URL', !page.url().includes(token), page.url());
  // Only in the swapped-in result: htmx itself adds its indicator <style> to <head> at load.
  check('no inline script or style in the swapped-in result', await page.evaluate(() =>
    document.querySelectorAll('#shortener :is(script, style, [style])').length === 0));

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
  check('Manage Token in no requested URL', requestedUrls.every(u => !u.includes(token)), requestedUrls.filter(u => u.includes(token)).join(' '));
  check('no JavaScript errors', consoleErrors.length === 0, consoleErrors.join(' | '));
  check('no 404s while using the page', notFound.length === 0, JSON.stringify([...new Set(notFound)]));
  check('favicon is served', (await page.request.get(BASE + '/favicon.ico')).status() === 200);

  // The Stats page (spec 0005, stories 4, 9, 21): follow the link, post the token, see the Stats.
  await page.goto(BASE + '/');
  await page.fill('input[name=url]', 'https://example.com/for-stats');
  await page.click('button[type=submit]');
  await page.locator('[data-short-url]').waitFor();
  const statsToken = await page.inputValue('input[data-manage-token]');
  const statsCopy = page.locator('.manage-token button[data-copy]');
  await statsCopy.waitFor({ state: 'visible', timeout: 2000 });
  await statsCopy.click();
  await page.waitForFunction(() => document.querySelector('.manage-token button[data-copy]').textContent.includes('Copied'));
  const copiedStatsToken = await page.evaluate(() => navigator.clipboard.readText());
  check('Stats journey: copied token is the Manage Token', copiedStatsToken === statsToken);
  check('Stats journey: token not in the URL after create', !page.url().includes(statsToken), page.url());
  const statsLink = page.locator('a[data-stats-link]');
  const statsHref = await statsLink.getAttribute('href');
  check('See its stats link carries the Short Code only', /^\/stats\?short_code=[A-Za-z0-9]{7}$/.test(statsHref) && !statsHref.includes(statsToken), statsHref);
  await Promise.all([page.waitForNavigation(), statsLink.click()]);
  check('Stats page: Short Code pre-filled', (await page.inputValue('input[name=short_code]')).length === 7);
  check('Stats page: token field empty on arrival', (await page.inputValue('input[name=manage_token]')) === '');
  check('Stats page: token not in the URL on arrival', !page.url().includes(statsToken), page.url());
  check('Stats page: token field is a password field', await page.getAttribute('input[name=manage_token]', 'type') === 'password');
  await page.fill('input[name=manage_token]', copiedStatsToken);
  await page.click('#stats button[type=submit]');
  await page.locator('#stats-clicks').waitFor();
  check('Stats page: the Stats appear', (await page.textContent('#stats-clicks')).trim() === '0');
  check('Stats page: 30 days in the table', await page.locator('#stats-per-day tbody tr').count() === 30);
  check('Stats page: token not in the page URL', !page.url().includes(statsToken), page.url());
  check('Stats page: no inline script or style', await page.evaluate(() =>
    document.querySelectorAll('#stats :is(script, style, [style])').length === 0));
  await page.fill('input[name=manage_token]', 'not-the-token');
  await page.click('#stats button[type=submit]');
  await page.locator('#stats-error').waitFor();
  check('Stats page: a wrong token shows the not-found message via HTMX', (await page.textContent('#stats-error')) === 'No stats found for that Short Code and manage token.');
  check('Stats page: token not in the URL after a wrong token', !page.url().includes(statsToken), page.url());
  check('Stats page: token in no requested URL', requestedUrls.every(u => !u.includes(statsToken)));

  // --- JavaScript off: plain HTML path ---
  const noJs = await browser.newContext({ javaScriptEnabled: false });
  const p2 = await noJs.newPage();
  await p2.goto(BASE + '/');
  await p2.fill('input[name=url]', 'https://example.com/no-js');
  await Promise.all([p2.waitForNavigation(), p2.click('button[type=submit]')]);
  check('no-JS: form posts and shows the Short URL', /\/[A-Za-z0-9]{7}$/.test(await p2.textContent('[data-short-url]')));
  check('no-JS: copy buttons stay hidden', (await p2.locator('button[data-copy]:visible').count()) === 0);
  const noJsToken = await p2.inputValue('input[data-manage-token]');
  check('no-JS: Manage Token shown with the keep-it-safe note',
    MANAGE_TOKEN.test(noJsToken) && (await p2.textContent('#manage-token-note')) === KEEP_IT_SAFE, noJsToken);
  check('no-JS: Manage Token not in the page URL', !p2.url().includes(noJsToken), p2.url());
  // With JavaScript off the DOM is exactly the page the server sent.
  check('no-JS: the page showing the Manage Token has no inline script or style', await p2.evaluate(() =>
    document.querySelectorAll('script:not([src]), style, [style]').length === 0));

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
