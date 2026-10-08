// The Lighthouse gate's scoring rule (issue #41): each category passes on the MEDIAN of several
// runs, so one noisy sample on a shared CI runner can't fail it, while a real regression still does.

function median(scores) {
  if (scores.length === 0) throw new Error('median needs at least one score');
  const sorted = [...scores].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

// scoresByCategory: { 'Performance': [0.73, 1, 1], ... } -> one result per category, in order
function decide(scoresByCategory, minScore) {
  return Object.entries(scoresByCategory).map(([category, scores]) => {
    const m = median(scores);
    return { category, scores, median: m, pass: m >= minScore };
  });
}

module.exports = { median, decide };
