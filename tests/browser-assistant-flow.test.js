const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const main = fs.readFileSync(path.join(root, 'main.js'), 'utf8');
const frontend = fs.readFileSync(path.join(root, 'src', 'features', 'job-assistant', 'job-assistant.js'), 'utf8');
const index = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

test('portal popups stay inside the attached embedded browser', () => {
  const handler = main.match(/guest\.setWindowOpenHandler\(\(\{ url \}\) => \{([\s\S]*?)\n    \}\);/)?.[1];
  assert.ok(handler, 'webview popup handler should be registered');
  assert.match(handler, /owner\.send\('embedded-browser-popup', safe\.href\)/);
  assert.match(handler, /guest\.loadURL\(safe\.href\)/);
  assert.doesNotMatch(handler, /openApplicationBrowser/);
  const preload = fs.readFileSync(path.join(root, 'preload.js'), 'utf8');
  assert.match(preload, /onEmbeddedBrowserPopup/);
  assert.match(preload, /ipcRenderer\.removeListener\('embedded-browser-popup', listener\)/);
});

test('one AutoApply workspace owns job applications and form controls', () => {
  assert.match(frontend, /async function applyToJob\(id,button\)[\s\S]*?startBrowserAssistantApplication/);
  assert.match(frontend, /async function prepareBrowserAssistantJob\(job\)[\s\S]*?startBrowserAssistantApplication\(job(?:,existing\|\|null)?\)/);
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
  assert.match(frontend, /getElementById\('browser-open-portal'\)\?\.addEventListener/);
});

test('form autofill uses saved profile and approved memory, with a current rescan and no extra permission gate', () => {
  const start = frontend.indexOf('async function fillAutoApplyForm()');
  const end = frontend.indexOf('\nasync function reviewAutoApplyForm()', start);
  const fill = frontend.slice(start, end);
  assert.ok(start >= 0 && end > start);
  assert.doesNotMatch(fill, /if\(!resumeName\)/);
  assert.doesNotMatch(fill, /application-site-permission|Confirm that the employer permits assisted autofill/);
  assert.match(fill, /applicationProposal\(field\)/);
  assert.match(fill, /automaticReuseApprovedAnswers/);
  assert.match(fill, /stableSignature/);
  assert.match(frontend, /async function suggestMissingAnswers/);
  assert.match(frontend, /field\.aiSuggestion=suggestion/);
  assert.ok(fill.indexOf('const scan=await scanAutoApplyPage(tab)') < fill.indexOf('fillScript(safeValues)'));
  assert.ok(fill.indexOf('const current=await tab.view.executeJavaScript') < fill.indexOf('fillScript(safeValues)'));
});

test('AutoApply restores the selected application, resolves fresh job details, and scans after page load', () => {
  assert.match(frontend, /state\.jobs=\[job,\.\.\.state\.jobs\.filter\(item=>Number\(item\.id\)!==Number\(job\.id\)\)\]/);
  assert.match(frontend, /function persistAutoApplyContext/);
  assert.match(frontend, /function discardInactiveAutoApplyContext/);
  assert.match(frontend, /async function restoreAutoApplyContext/);
  assert.match(frontend, /if\(state\.page==='browser'\)\{\s*if\(state\.browserAssistant\.suppressAutoApplyRestore\)[\s\S]*?else await restoreAutoApplyContext\(\);/);
  assert.match(frontend, /did-finish-load',\(\)=>\{[\s\S]*?inspectAutoApplyForm\(\)/);
  assert.doesNotMatch(frontend, /application-site-permission/);
  assert.doesNotMatch(frontend, /The portal opens in an isolated browser profile/);
  assert.doesNotMatch(frontend, /The portal opens in Menix’s embedded browser/);
});

test('Naukri search and imports require an explicit user action and only read rendered listings', () => {
  assert.match(frontend, /function getBrowserAssistantUrl\(\)/);
  assert.match(frontend, /Naukri:'https:\/\/www\.naukri\.com\/mnjuser\/recommendedjobs\?src=gnbOpportunities'/);
  assert.match(frontend, /function buildNaukriSearchUrl\(keyword,location,experience='fresher'\)/);
  assert.match(frontend, /function extractVisibleNaukriResultsFromPage\(\)/);
  assert.match(frontend, /document\.querySelectorAll\('\.cust-job-tuple'\)/);
  assert.match(frontend, /async function importVisibleNaukriJobs\(\)/);
  assert.match(frontend, /\/jobs\/import\/naukri-visible/);
  assert.match(frontend, /id="browser-stop-search"/);
  assert.match(frontend, /id="browser-search-naukri"/);
  assert.match(frontend, /id="browser-import-visible-jobs"/);
  assert.match(frontend, /LinkedIn:'https:\/\/www\.linkedin\.com\/jobs\/'/);
  assert.match(frontend, /Google Jobs':'https:\/\/www\.google\.com\/search\?ibp=htl;jobs'/);
  assert.match(frontend, /function extractCurrentJobFromPage\(\)/);
  assert.match(frontend, /script\[type="application\/ld\+json"\]/);
  assert.match(frontend, /async function saveCurrentJob\(\)/);
  assert.match(frontend, /id="browser-save-current-job"/);
  assert.match(frontend, /executeJavaScript\(`\(\$\{extractCurrentJobFromPage\.toString\(\)\}\)\(\)`/);
  assert.match(frontend, /api\('\/jobs'/);
  assert.match(frontend, /button\.hidden=\s*!\(await view\.executeJavaScript/);
  assert.match(frontend, /function isCurrentJobPage\(\)/);
  assert.match(frontend, /function reviewVisibleJobDetails\(job\)/);
  assert.match(frontend, /Confirm and save/);
  assert.match(frontend, /Attach this link to the saved job as its Apply link\?/);
  assert.match(frontend, /api\(`\/jobs\/\$\{job\.id\}\/application-url`/);
  assert.match(frontend, /Full Stack Developer/);
  assert.match(frontend, /Menix never clicks Apply or submits while discovering jobs/);
  assert.match(frontend, /applicationUrlVerified/);
  assert.match(frontend, /Confirm current page as Apply link/);
  const importStart = frontend.indexOf('async function importVisibleNaukriJobs()');
  const importEnd = frontend.indexOf('\nasync function verifyCurrentApplyDestination()', importStart);
  assert.ok(importStart >= 0 && importEnd > importStart);
  assert.doesNotMatch(frontend.slice(importStart, importEnd), /\.click\(|\/jobs\/\d+\/apply|submit/i);
  assert.doesNotMatch(frontend, /setInterval\([^)]*(?:Naukri|naukri)|autoApply.*click\(/i);
  assert.doesNotMatch(frontend, /render\(\)\.then\(runAutomaticJobSync\)/);
});

test('Naukri search URL uses the configured terms and only the known fresher filter', () => {
  const helper = frontend.match(/function buildNaukriSearchUrl\([\s\S]*?\r?\n\}/)?.[0];
  assert.ok(helper, 'Naukri search URL builder should exist');
  const build = vm.runInNewContext(`(${helper})`, { URL });
  const url = new URL(build('Full Stack Developer', 'Pune', 'fresher'));
  assert.equal(url.origin, 'https://www.naukri.com');
  assert.equal(url.searchParams.get('keyword'), 'Full Stack Developer fresher');
  assert.equal(url.searchParams.get('location'), 'Pune');
  assert.equal(url.searchParams.get('experience'), '0');
  const anyExperience = new URL(build('Remote Developer', '', 'any'));
  assert.equal(anyExperience.searchParams.get('keyword'), 'Remote Developer');
  assert.equal(anyExperience.searchParams.has('experience'), false);
  assert.throws(() => build('', '', 'fresher'), /Enter at least one job keyword/);
});

test('dashboard explains empty results with source diagnostics and a generic no-source state', () => {
  assert.match(frontend, /needsJobDiagnostics\?api\('\/jobs\/diagnostics'\)/);
  assert.match(frontend, /No sources configured\. Add sources in Sources\./);
  assert.match(frontend, /function dashboardDiagnosticsMarkup\(diagnostics=\{\}\)/);
  assert.match(frontend, /jobsFailingVerificationWindow/);
  assert.match(frontend, /jobsExcludedRetiredSource/);
  assert.match(frontend, /No employer boards configured: add companies in Sources\./);
  const sourceStart=frontend.indexOf('async function renderSources()');
  const sourceEnd=frontend.indexOf('\nfunction ',sourceStart+1);
  const sources=frontend.slice(sourceStart,sourceEnd);
  assert.ok(sourceStart>=0&&sourceEnd>sourceStart);
  assert.doesNotMatch(sources,/name:'(?:Remotive|Arbeitnow|Jobicy)'/);
});

test('dashboard and jobs label source and provenance across all integrated sources', () => {
  assert.match(frontend, /function jobOriginLabel\(job\)/);
  assert.match(frontend, /return 'From email alert'/);
  assert.match(frontend, /return job\.lastVerifiedAt\?'Verified by feed':'Feed not yet verified'/);
  assert.match(frontend, /return 'Saved by me'/);
  assert.match(frontend, /job-origin-badge/);
  assert.match(frontend, /activeOnly:\['jobs','saved'\]\.includes\(page\)\?'false':'true'/);
  assert.match(frontend, /function dashboardSourceRows\(sources=\[\]\)/);
  for(const source of ['Company boards','Adzuna','Email alerts','Career pages','Manual'])assert.ok(frontend.includes(`name:'${source}'`),`missing dashboard source ${source}`);
});

test('Sources groups providers by type and supports manual Run now actions', () => {
  const sourceStart=frontend.indexOf('async function renderSources()');
  const sourceEnd=frontend.indexOf('\nfunction profileCompleteness',sourceStart);
  const sources=frontend.slice(sourceStart,sourceEnd);
  for(const source of ['Company boards','Adzuna','Email alerts','Career pages','Manual'])assert.ok(sources.includes(`name:'${source}'`),`missing source card for ${source}`);
  assert.match(sources, /data-run-source=/);
  assert.match(sources, /Run now/);
  assert.match(sources, /source-type-grid/);
  assert.match(sources, /lastSuccessfulSync/);
  assert.match(sources, /deduplicated/);
});

test('Daily automatic sync settings persist schedule, app-open selection, and source toggles', () => {
  assert.match(frontend, /api\('\/settings\/automatic-sync'\)/);
  assert.match(frontend, /function dailySyncSettingsMarkup\(settings\)/);
  assert.match(frontend, /Daily automatic sync/);
  assert.match(frontend, /name="syncTime" type="time"/);
  assert.match(frontend, /name="runOnOpen"/);
  assert.match(frontend, /companyBoardsEnabled/);
  assert.match(frontend, /adzunaEnabled/);
  assert.match(frontend, /emailAlertsEnabled/);
  assert.match(frontend, /careerPagesEnabled/);
  assert.match(frontend, /\/jobs\/sync\/automatic/);
});

test('company-board settings test suggestions before enabling and keep candidates unverified', () => {
  assert.match(frontend, /api\('\/sources\/company-boards'\)/);
  assert.match(frontend, /data-board-test=/);
  assert.match(frontend, /Test &amp; enable/);
  assert.match(frontend, /ATS type:/);
  assert.match(frontend, /'unverified'/);
  assert.match(frontend, /successful test is required/);
  assert.match(frontend, /jobsFound/);
  assert.match(frontend, /PUBLIC_JOB_FEEDS_ENABLED=true/);
  assert.match(frontend, /ASHBY[\s\S]*WORKABLE[\s\S]*SMARTRECRUITERS/);
  const suggestions=frontend.match(/\['[^']+','(?:ASHBY|WORKABLE|SMARTRECRUITERS)'\]/g)||[];
  assert.ok(suggestions.length>=40,`expected at least 40 unverified company suggestions, found ${suggestions.length}`);
});

test('Adzuna source settings expose persisted profiles and daily request usage', () => {
  assert.match(frontend, /api\('\/settings\/adzuna-search-profiles'\)/);
  assert.match(frontend, /Adzuna search profiles/);
  assert.match(frontend, /ADZUNA_APP_ID and ADZUNA_APP_KEY/);
  assert.match(frontend, /callsToday/);
  assert.match(frontend, /dailyCallCap/);
  assert.match(frontend, /Not configured/);
});

test('dashboard and New Jobs include configured providers with an optional source filter', () => {
  assert.doesNotMatch(frontend,/params\.set\('source','Naukri'\)/);
  assert.match(frontend,/id="job-source"/);
  assert.match(frontend,/All sources/);
  assert.match(frontend,/Configured employer-board feeds sync through their public APIs/);
});

test('Naukri importer extracts visible cards only and leaves application destination unverified', () => {
  const extractor = frontend.match(/function extractVisibleNaukriResultsFromPage\(\)[\s\S]*?\r?\n\}/)?.[0];
  assert.ok(extractor, 'visible result extractor should exist');
  const titleAnchor = {
    href: 'https://www.naukri.com/job-listings-full-stack-engineer-78123456?src=search',
    innerText: 'Full Stack Engineer',
    getAttribute: () => null
  };
  const card = {
    getClientRects: () => [1],
    getAttribute: name => name === 'data-job-id' ? '78123456' : null,
    querySelector: selector => {
      if (selector === 'a.title, a[href*="job-listings"]') return titleAnchor;
      if (selector.includes('comp-name')) return { innerText: 'Example Labs' };
      if (selector === '.loc, [class*="location"]') return { innerText: 'Pune' };
      if (selector === '.exp, [class*="experience"]') return { innerText: '0-2 Yrs' };
      if (selector.includes('job-desc') || selector.includes('description')) return { innerText: 'Build frontend and backend products.' };
      if (selector.includes('salary')) return { innerText: 'Not disclosed' };
      return null;
    },
    querySelectorAll: selector => selector === 'ul.tags-gt li, [class*="tag"]'
      ? [{ innerText: 'React' }, { innerText: 'Node.js' }]
      : []
  };
  const hiddenCard = { ...card, getClientRects: () => [] };
  const document = { querySelectorAll: selector => selector === '.cust-job-tuple' ? [card, hiddenCard] : [] };
  const result = vm.runInNewContext(`(${extractor})()`, {
    URL,
    document,
    location: new URL('https://www.naukri.com/jobs-in-india?keyword=fullstack'),
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' })
  });
  assert.equal(result.scanned, 2);
  assert.equal(result.jobs.length, 1);
  assert.equal(result.jobs[0].title, 'Full Stack Engineer');
  assert.equal(result.jobs[0].sourceJobId, '78123456');
  assert.equal(result.jobs[0].url, 'https://www.naukri.com/job-listings-full-stack-engineer-78123456');
  assert.equal(result.jobs[0].company, 'Example Labs');
  assert.equal(result.jobs[0].requiredSkills, 'React, Node.js');
  assert.equal(result.jobs[0].applyUrl, '');
  assert.equal(Object.hasOwn(result.jobs[0], 'publishedAt'), false);
});

test('current-page extraction reads one structured job and refuses multi-job result pages', () => {
  const extractor = frontend.match(/function extractCurrentJobFromPage\(\)\{[\s\S]*?\r?\n\}\r?\nfunction sourceForJobUrl/)?.[0]
    ?.replace(/\r?\nfunction sourceForJobUrl$/, '');
  assert.ok(extractor, 'current-page extractor should be available');
  const runExtractor = (data, url) => {
    const document = {
      querySelectorAll: () => [{ textContent: JSON.stringify(data) }],
      querySelector: () => null,
      createElement: () => {
        let content = '';
        return {
          set innerHTML(value) { content = String(value).replace(/<[^>]*>/g, ' '); },
          get innerText() { return content; },
          get textContent() { return content; }
        };
      }
    };
    return vm.runInNewContext(`(${extractor})()`, { URL, document, location: new URL(url) });
  };
  const posting = {
    '@type': 'JobPosting',
    title: 'Software Engineer',
    url: 'https://www.naukri.com/job-listings-software-engineer-123',
    hiringOrganization: { name: 'Example Co' },
    jobLocation: { address: { addressLocality: 'Pune', addressRegion: 'Maharashtra' } },
    jobLocationType: 'TELECOMMUTE',
    description: '<p>Build software applications.</p>',
    datePosted: '2026-10-22'
  };
  const extracted = runExtractor(posting, posting.url);
  assert.equal(extracted.title, posting.title);
  assert.equal(extracted.company, 'Example Co');
  assert.equal(extracted.location, 'Pune, Maharashtra');
  assert.equal(extracted.workMode, 'Remote');
  assert.equal(extracted.description, 'Build software applications.');
  assert.equal(extracted.publishedAt, '2026-10-22');
  assert.equal(extracted.structured, true);

  const singleSearchResult = runExtractor(posting, 'https://www.naukri.com/jobs-in-india');
  assert.match(singleSearchResult.error, /does not save search result lists/);

  const resultPage = runExtractor({
    '@type': 'ItemList',
    itemListElement: [
      { '@type': 'ListItem', item: { ...posting, url: 'https://www.naukri.com/job-listings-first-1' } },
      { '@type': 'ListItem', item: { ...posting, url: 'https://www.naukri.com/job-listings-second-2' } }
    ]
  }, 'https://www.naukri.com/jobs-in-india');
  assert.match(resultPage.error, /multiple job listings/);
});

test('visible-text job fallback reads only visible fields and requires manual review', () => {
  const extractor = frontend.match(/function extractCurrentJobFromPage\(\)\{[\s\S]*?\r?\n\}\r?\nfunction sourceForJobUrl/)?.[0]
    ?.replace(/\r?\nfunction sourceForJobUrl$/, '');
  const nodes = {
    'h1': [{ innerText: 'Visible Software Engineer', textContent: 'Visible Software Engineer', getClientRects: () => [1] }],
    '#jobDescriptionText': [{ innerText: 'Hidden description', textContent: 'Hidden description', getClientRects: () => [] }],
    '.jd-description': [{ innerText: 'Visible job description', textContent: 'Visible job description', getClientRects: () => [1] }]
  };
  const document = {
    querySelectorAll: selector => selector === 'script[type="application/ld+json"]' ? [] : (nodes[selector] || []),
    createElement: () => ({ set innerHTML(value) { this.textContent = String(value); }, get innerText() { return this.textContent; } })
  };
  const extracted = vm.runInNewContext(`(${extractor})()`, {
    URL, document, location: new URL('https://careers.example.com/jobs/view/123'),
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' })
  });
  assert.equal(extracted.title, 'Visible Software Engineer');
  assert.equal(extracted.description, 'Visible job description');
  assert.equal(extracted.structured, false);
  assert.equal(extracted.requiredSkills, '');
  assert.match(frontend, /function reviewVisibleJobDetails\(job\)/);
  assert.match(frontend, /const confirmed=structured\?jobDetails:await reviewVisibleJobDetails\(jobDetails\)/);
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
  assert.match(frontend, /const active=state\.applications\.filter\(a=>!\['DECLINED','SHORTLISTED','DISCOVERED','WITHDRAWN','NOT_COMPLETED_YET'\]/);
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
