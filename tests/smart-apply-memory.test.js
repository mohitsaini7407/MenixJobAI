const assert = require('node:assert/strict');
const test = require('node:test');
const memory = require('../src/features/job-assistant/smart-apply-memory.js');

test('matches exact and semantically similar approved questions across applications', () => {
  const saved = [{ id: 8, question: 'What technologies have you used in projects?', answer: 'Java and PostgreSQL', approvedAt: '2026-10-20T12:00:00Z' }];
  const exact = memory.matchQuestion('What technologies have you used in projects?', saved);
  assert.equal(exact.status, 'approved-match');
  assert.equal(exact.confidence, 1);
  for (const question of ['Which technologies have you used?', 'Tell us which technologies you have used']) {
    const match = memory.matchQuestion(question, saved);
    assert.equal(match.answer, 'Java and PostgreSQL');
    assert.equal(match.source, 'Previously Approved');
  }
});

test('leaves low-confidence questions blank and applies configured thresholds', () => {
  const saved = [{ id: 3, question: 'What technologies have you used in projects?', answer: 'Java and SQL' }];
  const result = memory.matchQuestion('What is your favorite movie?', saved);
  assert.equal(result.status, 'blank');
  assert.equal(result.answer, '');
  const medium = memory.matchQuestion('Which technologies have you used?', saved, { mediumConfidenceThreshold: 0.5, highConfidenceThreshold: 0.99 });
  assert.equal(medium.status, 'suggestion');
});

test('sensitive approved answers require confirmation and never become automatic matches', () => {
  const saved = [{ id: 2, question: 'Are you authorized to work in this country?', answer: 'Yes' }];
  const result = memory.matchQuestion('Are you authorized to work in this country?', saved, { automaticReuseApprovedAnswers: true });
  assert.equal(result.status, 'confirmation-required');
  assert.equal(result.answer, 'Yes');
});

test('conflicting similar answers are reported rather than selected', () => {
  const saved = [
    { id: 1, question: 'What technologies have you used in projects?', answer: 'Java' },
    { id: 2, question: 'Which technologies have you used in projects?', answer: 'Python' }
  ];
  const result = memory.matchQuestion('Which technologies have you used in projects?', saved);
  assert.equal(result.status, 'conflict');
  assert.equal(result.answer, '');
});

test('excluded answers are ignored and correction history reduces match confidence', () => {
  const excluded = memory.matchQuestion('What technologies have you used?', [
    { question: 'What technologies have you used?', answer: 'Java', excluded: true }
  ]);
  assert.equal(excluded.status, 'blank');
  const corrected = memory.matchQuestion('What technologies have you used?', [
    { question: 'What technologies have you used?', answer: 'Java', correctionCount: 3 }
  ], { highConfidenceThreshold: 0.85 });
  assert.equal(corrected.status, 'suggestion');
  assert.ok(corrected.confidence < 0.85);
});
