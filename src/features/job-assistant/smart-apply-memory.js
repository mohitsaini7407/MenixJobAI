(function exposeSmartApplyMemory(root) {
  const stopWords = new Set(['a', 'an', 'the', 'to', 'of', 'for', 'in', 'on', 'at', 'is', 'are', 'do', 'does', 'did', 'you', 'your', 'what', 'which', 'how', 'please', 'would', 'could', 'can', 'tell', 'me', 'us', 'about', 'and', 'or', 'with', 'be', 'have']);
  const aliases = new Map([
    ['technology', 'skills'], ['technologies', 'skills'], ['tools', 'skills'], ['programming', 'skills'], ['languages', 'skills'],
    ['used', 'experience'], ['worked', 'experience'], ['experienced', 'experience'], ['familiar', 'experience'], ['knowledge', 'experience'],
    ['degree', 'education'], ['major', 'education'], ['qualification', 'education'], ['graduation', 'education'],
    ['city', 'location'], ['country', 'location'], ['located', 'location'],
    ['internship', 'experience'], ['internships', 'experience']
  ]);
  const sensitivePattern = /\b(work authorization|authorized to work|visa|sponsor(ship)?|salary|compensation|gender|sex|ethnicity|race|disabilit(y|ies)|veteran|citizenship|date of birth|birth ?date|age|social security|ssn|tax id|criminal|conviction|background check|security clearance|referral|legally authorized|have you ever)\b/i;

  function normalizeQuestion(value) {
    return String(value || '').toLowerCase()
      .replace(/[^a-z0-9]+/g, ' ')
      .trim()
      .split(/\s+/)
      .filter(token => token && !stopWords.has(token))
      .map(token => aliases.get(token) || token)
      .join(' ');
  }

  function similarity(left, right) {
    const a = new Set(normalizeQuestion(left).split(' ').filter(Boolean));
    const b = new Set(normalizeQuestion(right).split(' ').filter(Boolean));
    if (!a.size || !b.size) return 0;
    let overlap = 0;
    for (const token of a) if (b.has(token)) overlap++;
    if (!overlap) return 0;
    if (a.size === b.size && overlap === a.size) return 1;
    const jaccard = overlap / (a.size + b.size - overlap);
    const containment = overlap / Math.min(a.size, b.size);
    return Math.min(0.94, 0.65 * jaccard + 0.35 * containment);
  }

  function matchQuestion(question, answers, preferences = {}) {
    const sensitive = sensitivePattern.test(question);
    const candidates = (answers || [])
      .filter(item => item && !item.excluded && String(item.answer || '').trim() && String(item.question || '').trim())
      .map(item => ({
        item,
        confidence: similarity(question, item.question) * Math.max(0.5, 1 - Number(item.correctionCount || 0) * 0.1)
      }))
      .sort((a, b) => b.confidence - a.confidence);
    const best = candidates[0];
    const minimum = Number(preferences.mediumConfidenceThreshold ?? 0.55);
    if (!best || best.confidence < minimum) {
      return { status: 'blank', source: '', confidence: best?.confidence || 0, answer: '', reason: 'No sufficiently similar approved answer is saved.' };
    }
    const conflict = candidates.find(candidate =>
      candidate.item.answer !== best.item.answer && best.confidence - candidate.confidence < 0.08
    );
    if (conflict) return { status: 'conflict', source: 'Previously Approved', confidence: best.confidence, answer: '', reason: 'Multiple similar saved questions have conflicting answers. Resolve them in Answer Memory.' };
    if (sensitive) return { status: 'confirmation-required', source: 'Previously Approved', confidence: best.confidence, answer: best.item.answer, memoryId: best.item.id, reason: 'Saved response matches, but this sensitive answer requires your explicit confirmation.' };
    const high = Number(preferences.highConfidenceThreshold ?? 0.85);
    return {
      status: best.confidence >= high ? 'approved-match' : 'suggestion',
      source: 'Previously Approved',
      confidence: best.confidence,
      answer: best.item.answer,
      memoryId: best.item.id,
      reason: best.confidence >= high ? 'Strong match to an approved saved answer.' : 'Similar approved answer found; review before using it.'
    };
  }

  const api = { normalizeQuestion, similarity, matchQuestion };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MenixSmartApplyMemory = api;
})(typeof window === 'undefined' ? globalThis : window);
