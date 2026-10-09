// Lighthouse for the web page (ADR 0006 quality bar: >= 90 in every category), mobile and desktop.
// Usage: BASE_URL=http://localhost:8000 node lighthouse.js   (the app must already be running)
//
// Robust on shared CI runners (issue #41): the app is warmed up first, each form factor is audited
// RUNS times (default 3), and each category is gated on the MEDIAN score. Every run's scores are
// printed. Reports for every run, plus a summary, are written to e2e/reports/.
const fs = require('fs');
const path = require('path');
const { decide } = require('./scoring');

const BASE = process.env.BASE_URL || 'http://localhost:8000';
const RUNS = Number(process.env.RUNS || 3);
const MIN_SCORE = 0.9;
const WARM_UP_ROUNDS = 5;
const ROUTES = ['/', '/stats'];

// Request the page and every asset it references, so the first audit doesn't pay for a cold JVM.
async function warmUp() {
  for (let round = 0; round < WARM_UP_ROUNDS; round++) {
    const html = await (await fetch(BASE + '/')).text() + await (await fetch(BASE + '/stats')).text();
    const assets = [...html.matchAll(/(?:href|src)="(\/[^"]+)"/g)].map((m) => m[1]);
    await Promise.all(assets.map((asset) => fetch(BASE + asset).then((r) => r.arrayBuffer())));
  }
}

(async () => {
  const { default: lighthouse, desktopConfig } = await import('lighthouse');
  const chromeLauncher = await import('chrome-launcher');
  const outDir = path.join(__dirname, 'reports');
  fs.mkdirSync(outDir, { recursive: true });

  const started = Date.now();
  await warmUp();
  const chrome = await chromeLauncher.launch({ chromeFlags: ['--headless=new', '--no-sandbox'] });
  let failed = false;
  const summary = {};
  try {
    for (const [formFactor, config, route] of ['mobile', 'desktop'].flatMap((f) => ROUTES.map((r) => [f, f === 'mobile' ? undefined : desktopConfig, r]))) {
      const label = route === '/' ? formFactor : `${formFactor}${route}`;
      const scores = {};
      for (let run = 1; run <= RUNS; run++) {
        const result = await lighthouse(
          BASE + route,
          { port: chrome.port, output: ['html', 'json'], logLevel: 'error' },
          config);
        const [html, json] = result.report;
        fs.writeFileSync(path.join(outDir, `lighthouse-${label.replace('/', '-')}-${run}.html`), html);
        fs.writeFileSync(path.join(outDir, `lighthouse-${label.replace('/', '-')}-${run}.json`), json);
        const line = Object.values(result.lhr.categories).map((c) => {
          (scores[c.title] ||= []).push(c.score);
          return `${c.title} ${Math.round(c.score * 100)}`;
        });
        console.log(`run ${run}/${RUNS}  ${label.padEnd(13)} ${line.join(' · ')}`);
      }
      summary[label] = decide(scores, MIN_SCORE);
      for (const r of summary[label]) {
        failed ||= !r.pass;
        const runs = r.scores.map((s) => Math.round(s * 100)).join(', ');
        console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${label.padEnd(13)} ${r.category.padEnd(18)} ${Math.round(r.median * 100)}  (median of ${runs})`);
      }
    }
  } finally {
    await chrome.kill();
  }
  fs.writeFileSync(
    path.join(outDir, 'lighthouse-summary.json'),
    JSON.stringify({ runs: RUNS, minScore: MIN_SCORE, seconds: (Date.now() - started) / 1000, summary }, null, 2));
  console.log(`Lighthouse: ${RUNS} runs per form factor in ${((Date.now() - started) / 1000).toFixed(1)}s`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
