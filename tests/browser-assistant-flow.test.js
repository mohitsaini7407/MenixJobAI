const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const main = fs.readFileSync(path.join(root, 'main.js'), 'utf8');
const frontend = fs.readFileSync(path.join(root, 'src', 'features', 'job-assistant', 'job-assistant.js'), 'utf8');
const index = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

test('portal popups stay inside the attached embedded browser', () => {
  const handler = main.match(/guest\.setWindowOpenHandler\(\(\{ url \}\) => \{([\s\S]*?)\n    \}\);/)?.[1];
  assert.ok(handler, 'webview popup handler should be registered');
  assert.match(handler, /guest\.loadURL\(safe\.href\)/);
  assert.doesNotMatch(handler, /openApplicationBrowser/);
});

test('one AutoApply workspace owns job applications and form controls', () => {
  assert.match(frontend, /async function applyToJob\(id,button\)[\s\S]*?startBrowserAssistantApplication/);
  assert.match(frontend, /async function prepareBrowserAssistantJob\(job\)[\s\S]*?startBrowserAssistantApplication\(job\)/);
  assert.match(frontend, /return state\.page==='browser'\?state\.browserAssistant\.autoApplyTab:null/);
  assert.match(frontend, /id="application-inspect"/);
  assert.match(frontend, /id="application-autofill"/);
  assert.match(frontend, /id="application-submit-button"/);
  assert.match(index, /data-page="browser" title="AutoApply"/);
  assert.equal((index.match(/data-page="browser"/g) || []).length, 1);
  assert.doesNotMatch(index, /Browser Assistant/);
  assert.doesNotMatch(index, /data-page="autoapply"/);
  assert.doesNotMatch(frontend, /async function renderAutoApply\(/);
  assert.match(frontend, /<h2>AutoApply<\/h2>/);
});

test('form autofill uses saved profile and approved memory, with a current rescan and permission gate', () => {
  const start = frontend.indexOf('async function fillAutoApplyForm()');
  const end = frontend.indexOf('\nasync function reviewAutoApplyForm()', start);
  const fill = frontend.slice(start, end);
  assert.ok(start >= 0 && end > start);
  assert.doesNotMatch(fill, /if\(!resumeName\)/);
  assert.match(fill, /application-site-permission/);
  assert.match(fill, /applicationProposal\(field\)/);
  assert.match(fill, /automaticReuseApprovedAnswers/);
  assert.match(frontend, /async function suggestMissingAnswers/);
  assert.match(frontend, /field\.aiSuggestion=suggestion/);
  assert.ok(fill.indexOf('const scan=await scanAutoApplyPage(tab)') < fill.indexOf('fillScript(safeValues)'));
  assert.ok(fill.indexOf('const current=await tab.view.executeJavaScript') < fill.indexOf('fillScript(safeValues)'));
});

test('field review reflects fresh validation, explicit answer approval and safe submission gates', () => {
  assert.match(frontend, /requiredMissing=fields\.filter\(field=>field\.required&&field\.blank\)\.length\+unsupported\.filter\(field=>field\.required\)\.length/);
  assert.match(frontend, /data-answer-accept/);
  assert.match(frontend, /data-answer-reject/);
  assert.match(frontend, /data-answer-save/);
  assert.match(frontend, /async function reviewAutoApplyForm\(\)[\s\S]*?scanAutoApplyPage\(tab\)/);
  assert.match(frontend, /if\(!finalGate\.allowed\)/);
  assert.match(frontend, /application-suggest-missing/);
  assert.match(frontend, /application-view-memory/);
});

test('AI drafts are consent-gated, non-sensitive only, suggestions-only, and failures are visible', () => {
  const start = frontend.indexOf('async function suggestMissingAnswers()');
  const end = frontend.indexOf('\nasync function scanAutoApplyPage', start);
  const suggest = frontend.slice(start, end);
  assert.ok(start >= 0 && end > start);
  assert.match(suggest, /!field\.sensitive/);
  assert.ok(suggest.indexOf('window.confirm(') < suggest.indexOf("api('/resumes/application-draft'"));
  assert.match(suggest, /field\.aiSuggestion=suggestion/);
  assert.doesNotMatch(suggest, /fillScript/);
  assert.match(suggest, /AI suggestions failed:/);
});

test('withdrawing hides the record from the active tracker and routes back to jobs', () => {
  assert.match(frontend, /!?\['DECLINED','SHORTLISTED','DISCOVERED','WITHDRAWN','NOT_COMPLETED_YET'\]/);
  assert.match(frontend, /\['WITHDRAWN','NOT_COMPLETED_YET'\][\s\S]*?go\('jobs'\)/);
  assert.match(frontend, /Withdrawn history is preserved/);
  assert.match(frontend, /!?\['SHORTLISTED','DISCOVERED','WITHDRAWN','NOT_COMPLETED_YET'\]/);
});

test('not completed applications leave Applied Jobs and can be restarted without losing history', () => {
  assert.match(frontend, /NOT_COMPLETED_YET/);
  assert.match(frontend, /Not Completed Yet/);
  assert.match(frontend, /NOT_COMPLETED_YET'\]\.includes/);
  assert.match(frontend, /data-app-edit-slot="\$\{a\.id\}"/);
  assert.match(frontend, /document\.querySelector\(`\[data-app-edit-slot="\$\{id\}"\]`\)/);
  assert.match(frontend, /document\.querySelectorAll\('\.application-edit-slot'\)\.forEach\(slot=>slot\.replaceChildren\(\)\)/);
  assert.match(frontend, /application-edit-card/);
});

test('AutoApply keeps clear review tabs and a truthful required-field progress indicator', () => {
  assert.match(frontend, /data-browser-tab="prepare">Detect &amp; Fill/);
  assert.match(frontend, /data-browser-tab="review">Review &amp; Submit/);
  assert.match(frontend, /data-browser-tab="history">Answer History/);
  assert.match(frontend, /role="progressbar" aria-label="Required application fields completed"/);
  assert.match(frontend, /requiredCompleted=fields\.filter/);
});

test('two-finger horizontal swipe-back handling remains enabled', () => {
  assert.match(frontend, /window\.addEventListener\('wheel',event=>/);
  assert.match(frontend, /swipeDistance>=90/);
  assert.match(frontend, /window\.history\.back\(\)/);
});
