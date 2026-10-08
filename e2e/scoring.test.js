// Unit tests for the Lighthouse gate's scoring rule (issue #41). Run: npm test
const test = require('node:test');
const assert = require('node:assert/strict');
const { median, decide } = require('./scoring');

test('median of an odd number of runs is the middle score', () => {
  assert.equal(median([1, 0.73, 1]), 1);
  assert.equal(median([0.7, 0.95, 0.75]), 0.75);
});

test('median of an even number of runs is the mean of the middle two', () => {
  assert.equal(median([0.8, 1, 0.9, 0.95]), 0.925);
});

test('median needs at least one run', () => {
  assert.throws(() => median([]), /at least one/);
});

test('one noisy run does not fail the gate', () => {
  // The CI flake from PR #40: mobile Performance 73 once, 100 when re-run.
  const results = decide({ Performance: [0.73, 1, 1], Accessibility: [1, 1, 1] }, 0.9);
  assert.deepEqual(results, [
    { category: 'Performance', scores: [0.73, 1, 1], median: 1, pass: true },
    { category: 'Accessibility', scores: [1, 1, 1], median: 1, pass: true },
  ]);
});

test('a real regression still fails: most runs below the threshold', () => {
  const [performance] = decide({ Performance: [0.71, 0.95, 0.74] }, 0.9);
  assert.equal(performance.median, 0.74);
  assert.equal(performance.pass, false);
});

test('a score exactly at the threshold passes', () => {
  assert.equal(decide({ SEO: [0.9, 0.9, 0.89] }, 0.9)[0].pass, true);
});
