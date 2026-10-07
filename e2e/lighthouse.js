// Lighthouse for the web page (ADR 0006 quality bar: >= 90 in every category), mobile and desktop.
// Usage: BASE_URL=http://localhost:8000 node lighthouse.js   (the app must already be running)
// Reports are written to e2e/reports/ (HTML + JSON) so a reviewer can open them.
const fs = require('fs');
const path = require('path');

const BASE = process.env.BASE_URL || 'http://localhost:8000';
const MIN_SCORE = 0.9;

(async () => {
  const { default: lighthouse, desktopConfig } = await import('lighthouse');
  const chromeLauncher = await import('chrome-launcher');
  const outDir = path.join(__dirname, 'reports');
  fs.mkdirSync(outDir, { recursive: true });

  const chrome = await chromeLauncher.launch({ chromeFlags: ['--headless=new', '--no-sandbox'] });
  let failed = false;
  try {
    for (const [formFactor, config] of [['mobile', undefined], ['desktop', desktopConfig]]) {
      const result = await lighthouse(
        BASE + '/',
        { port: chrome.port, output: ['html', 'json'], logLevel: 'error' },
        config);
      const [html, json] = result.report;
      fs.writeFileSync(path.join(outDir, `lighthouse-${formFactor}.html`), html);
      fs.writeFileSync(path.join(outDir, `lighthouse-${formFactor}.json`), json);
      for (const category of Object.values(result.lhr.categories)) {
        const ok = category.score >= MIN_SCORE;
        failed ||= !ok;
        console.log(`${ok ? 'PASS' : 'FAIL'}  ${formFactor.padEnd(7)} ${category.title.padEnd(18)} ${Math.round(category.score * 100)}`);
      }
    }
  } finally {
    await chrome.kill();
  }
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
