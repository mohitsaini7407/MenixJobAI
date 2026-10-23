const API_BASE = 'http://localhost:8080/api';
const GET_CACHE_TTL_MS = 60000;
const AUTO_APPLY_CONTEXT_KEY = 'menix-auto-apply-context';
const getCache = new Map();
const COMPANY_BOARD_PROVIDERS = [
  { key: 'ASHBY', name: 'Ashby' },
  { key: 'WORKABLE', name: 'Workable' },
  { key: 'SMARTRECRUITERS', name: 'SmartRecruiters' }
];
const COMPANY_BOARD_SUGGESTIONS = [
  ['Razorpay','ASHBY'],['Swiggy','ASHBY'],['Zepto','ASHBY'],['CRED','ASHBY'],['Groww','ASHBY'],
  ['Postman','ASHBY'],['BrowserStack','ASHBY'],['Meesho','ASHBY'],['Juspay','ASHBY'],['ShareChat','ASHBY'],
  ['Dream11','ASHBY'],['Freshworks','ASHBY'],['Zoho','ASHBY'],['PhonePe','ASHBY'],['Zomato','ASHBY'],
  ['Urban Company','WORKABLE'],['Lenskart','WORKABLE'],['Nykaa','WORKABLE'],['Delhivery','WORKABLE'],['Cars24','WORKABLE'],
  ['Spinny','WORKABLE'],['Policybazaar','WORKABLE'],['ACKO','WORKABLE'],['MakeMyTrip','WORKABLE'],['OYO','WORKABLE'],
  ['Unacademy','WORKABLE'],['upGrad','WORKABLE'],['Vedantu','WORKABLE'],['InMobi','WORKABLE'],['Myntra','WORKABLE'],
  ['Infosys','SMARTRECRUITERS'],['Tata Consultancy Services','SMARTRECRUITERS'],['Wipro','SMARTRECRUITERS'],['HCLTech','SMARTRECRUITERS'],['Tech Mahindra','SMARTRECRUITERS'],
  ['Persistent Systems','SMARTRECRUITERS'],['LTIMindtree','SMARTRECRUITERS'],['Capgemini India','SMARTRECRUITERS'],['Accenture India','SMARTRECRUITERS'],['IBM India','SMARTRECRUITERS'],
  ['Microsoft India','SMARTRECRUITERS'],['Amazon India','SMARTRECRUITERS'],['Google India','SMARTRECRUITERS'],['Flipkart','SMARTRECRUITERS'],['Reliance Jio','SMARTRECRUITERS']
].map(([company, provider]) => ({
  company,
  provider,
  identifier: company.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
}));
const state = { page: 'dashboard', jobs: [], applications: [], outreach: [], portalCapabilities: [], profile: null, smartApplyAnswers: [], smartApplyPreferences: { automaticReuseApprovedAnswers: false, learningEnabled: true, highConfidenceThreshold: 0.85, mediumConfidenceThreshold: 0.55 }, sources: [], stats: {}, jobDiagnostics: null, aiStatus: { enabled: false }, selectedJob: null, detailReturnPage: null, query: localStorage.getItem('menix-job-filter-query') || 'Full Stack Developer', location: localStorage.getItem('menix-job-filter-location') || '', careerStage: localStorage.getItem('menix-career-stage') || 'fresher', freshness: localStorage.getItem('menix-freshness') || '24h', dashboardFreshness: localStorage.getItem('menix-dashboard-freshness') || '24h', minScore: Number(localStorage.getItem('menix-min-score') || 80), jobMinScore: Number(localStorage.getItem('menix-job-min-score') || 0), sort: 'match', outreachTab: 'drafts', activeOutreachId: null, autoApply: { selectedJobId: '', selectedResume: localStorage.getItem('menix-selected-resume') || '', resumeOptions: [], fields: [], uploads: [], submitCandidates: [], sessions: [], status: 'Ready' }, browserAssistant: { portal: 'Naukri', status: 'Not Started', hostname: '', url: '', location: '', experience: 'fresher', workMode: 'Any', otherUrl: '', savedJobId: null, selectedTab: 'details', match: null, autoApplyTab: null, sessionConsent: false, applicationInspectionEnabled: false, suppressAutoApplyRestore: false, importController: null, importStatus: 'Ready', applyDestinationCandidate: '', lastImportAt: '' }, automaticSyncStarted: false };
state.sourceFilter=localStorage.getItem('menix-job-source-filter')||'all';
const content = document.getElementById('content');
const accountAvatarStorageKey = 'menix-account-avatar';

if (window.menixDesktop) {
  document.body.classList.add('desktop-app');
  document.getElementById('window-close')?.addEventListener('click', () => window.menixDesktop.closeWindow());
  document.getElementById('window-minimize')?.addEventListener('click', () => window.menixDesktop.minimizeWindow());
  document.getElementById('window-maximize')?.addEventListener('click', () => window.menixDesktop.toggleMaximizeWindow());
}

async function api(path, options = {}) {
  const method=(options.method||'GET').toUpperCase();
  const cacheKey=`${method}:${path}`;
  if(method==='GET'){
    const cached=getCache.get(cacheKey);
    if(cached&&Date.now()-cached.savedAt<GET_CACHE_TTL_MS)return cached.data;
  }else getCache.clear();
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: { ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) }
  });
  content.querySelectorAll('[data-career-settings-open]').forEach(button=>button.onclick=()=>go('settings'));
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try { const data = await response.json(); message = data.message || data.error || message; } catch {}
    if(response.status===405&&path==='/resumes/application-draft'){
      message='The backend currently running is outdated and does not support Gemini form autofill. Restart the Spring Boot backend from the project’s backend folder, then try again.';
    }
    if(response.status===404&&path.startsWith('/autofill/'))message='The backend currently running does not include the AutoApply API. Restart Spring Boot from the project’s backend folder, then retry.';
    if(response.status===404&&path.startsWith('/smart-apply/'))message='The backend currently running does not include Smart Apply Memory. Restart Spring Boot from this project’s backend folder, then retry.';
    const error=new Error(message);
    error.status=response.status;
    error.path=path;
    throw error;
  }
  if (response.status === 204) return null;
  const data=await response.json();
  if(method==='GET')getCache.set(cacheKey,{savedAt:Date.now(),data});
  return data;
}
function esc(value='') { return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function toast(message) { const el = document.getElementById('toast'); el.textContent = message; el.classList.add('show'); setTimeout(() => el.classList.remove('show'), 2800); }
function setConnection(ok) {
  document.getElementById('connection').textContent = ok ? 'Backend connected' : 'Backend offline';
  const indicator = document.querySelector('.status-dot');
  indicator.classList.toggle('is-online', ok);
  indicator.classList.toggle('is-offline', !ok);
}
function initials(name='Company') { return name.trim().split(/\s+/).slice(0,2).map(x=>x[0]||'').join('').toUpperCase(); }
function money(value) { return value ? esc(value) : ''; }
function known(value, fallback='') { const v=String(value||'').trim(); return !v||/^not specified$/i.test(v)||/^unknown$/i.test(v)?fallback:esc(v); }
function jobRow(job) {
 const posted=job.postedAt?new Date(job.postedAt).toLocaleDateString():job.publishedAt?new Date(job.publishedAt).toLocaleDateString():'';
 const discovered=job.retrievedAt?`Discovered ${new Date(job.retrievedAt).toLocaleDateString()}`:'Discovery date unavailable';
 const verified=job.lastVerifiedAt?`${job.importMethod==='BROWSER_SEARCH_RESULTS'||job.importMethod==='BROWSER_PAGE'?'Last observed':'Verified'} ${new Date(job.lastVerifiedAt).toLocaleDateString()}`:!job.availabilityStatus||job.availabilityStatus==='UNVERIFIED'?'Availability unverified':'Not recently verified';
 const meta=[known(job.location,'Location not specified'),known(job.workMode),posted?`Posted ${posted}`:'Posting date unverified',discovered,verified].filter(Boolean).join(' · ');
 const bucket=careerBucket(job); const score=Number(job.matchScore ?? job.score ?? 0);
 const skills=(job.matchedSkills||String(job.requiredSkills||'').split(/[,;|]/)).filter(Boolean).slice(0,3);
 const saved=state.applications.some(a=>Number(a.jobId)===Number(job.id)&&a.status==='SHORTLISTED');
 const description=String(job.description||'').replace(/\s+/g,' ').trim();
 const origin=jobOriginLabel(job);
 const emailAlert=origin==='From email alert';
 const providerBadge=job.providerBadge||(['Greenhouse','Lever','Ashby','Workable','SmartRecruiters','Adzuna','Career pages'].includes(job.source)?'Direct API':'External Link');
 const applyVerified=Boolean(job.applicationUrlVerified);
 return `<article class="job-card">
  <div class="job-card-top">
   <div class="company-icon">${esc(initials(job.company))}</div>
   <div class="job-card-title"><strong>${esc(job.title||'Untitled role')}</strong><span>${esc(job.company||'Company not specified')}</span><span class="provider-badge ${providerBadge==='Direct API'?'badge-direct':'badge-external'}">${esc(job.source||'Source not specified')}</span><span class="job-origin-badge ${emailAlert?'badge-email':origin==='Verified by feed'?'badge-direct':'badge-external'}">${esc(origin)}</span></div>
   <button class="bookmark-button ${saved?'is-saved':''}" data-save-job="${job.id}" aria-label="${saved?'Remove saved job':'Save job'}" aria-pressed="${saved}">${saved?'◆':'◇'}</button>
   <div class="score-badge ${score>=80?'score-high':score>=60?'score-mid':'score-low'}"><strong>${score}%</strong></div>
  </div>
  <div class="job-meta"><span>⌖ ${meta}</span><span>${esc(job.source||'Source not specified')}</span></div>
  <div class="job-tags">${job.employmentType?`<span class="tag">${esc(job.employmentType)}</span>`:''}${job.workMode?`<span class="tag">${esc(job.workMode)}</span>`:''}${emailAlert?'<span class="tag">Summary only · unverified</span>':''}${skills.map(t=>`<span class="tag good">${esc(t)}</span>`).join('')}${skills.length===0&&bucket==='entry'?'<span class="tag good">Entry level</span>':''}</div>
  ${description?`<p class="job-card-summary" title="${esc(description)}">${esc(description.slice(0,150))}${description.length>150?'…':''}</p>`:'<p class="job-card-summary muted">Description not provided.</p>'}
  <div class="job-card-actions"><button class="button button-primary" data-apply-job="${job.id}" title="${applyVerified?'Open the verified application destination':'Application destination unverified; open the original listing'}">➤ ${applyVerified?'Apply':'Open listing'}</button><button class="button button-danger" data-decline-job="${job.id}">× Reject</button><button class="button button-quiet" data-job="${job.id}" aria-label="View full job description" title="View full job description">Details</button></div>
  ${applyVerified?'':'<small class="job-card-apply-note">Application destination unverified; Open listing goes to the original job page.</small>'}
 </article>`;
}
function jobOriginLabel(job){
 const source=String(job.source||'');
 if(/ alert$/i.test(source))return 'From email alert';
 const importMethod=String(job.importMethod||'').toUpperCase();
 if(['MANUAL','BROWSER_PAGE','BROWSER_SEARCH_RESULTS'].includes(importMethod)||/manual/i.test(source))return 'Saved by me';
 const feedSources=['Greenhouse','Lever','Ashby','Workable','SmartRecruiters','Adzuna','Career pages'];
 if(feedSources.includes(source))return job.lastVerifiedAt?'Verified by feed':'Feed not yet verified';
 return 'Saved by me';
}
function stat(label,value,foot) { return `<div class="card"><div class="stat-label">${label}</div><div class="stat-value">${value}</div><div class="stat-foot">${foot}</div></div>`; }
async function refreshData() {
  const page=state.page;
  const needsJobs=['dashboard','jobs','saved','interview','browser'].includes(page)||(page==='messages'&&state.jobs.length===0);
  const needsApplications=['dashboard','jobs','applications','saved','browser'].includes(page);
  const needsSources=['dashboard','jobs','sources'].includes(page);
  const needsProfile=['dashboard','profile','jobs','interview','browser','settings'].includes(page)||(page==='messages'&&!state.profile);
  const needsStats=page==='dashboard';
  const needsJobDiagnostics=page==='dashboard';
  const needsAiStatus=['dashboard','jobs','job-detail','browser'].includes(page);
  const needsSmartApply=page==='browser'||page==='settings';
  const selectedFreshness=page==='dashboard'?state.dashboardFreshness:page==='saved'||page==='browser'?'all':state.freshness;
  const params=new URLSearchParams({freshness:selectedFreshness,minScore:page==='dashboard'||page==='saved'||page==='browser'?'0':String(page==='jobs'?state.jobMinScore:state.minScore),sort:page==='dashboard'?'match':state.sort,activeOnly:['jobs','saved'].includes(page)?'false':'true'});
  if(page==='dashboard'){params.set('limit','50');params.set('activeOnly','true');}
  if(page==='jobs'&&state.sourceFilter!=='all')params.set('source',state.sourceFilter);
  if(state.query&&(page==='jobs'||page==='dashboard')) params.set('q',state.query);
  if(state.location&&(page==='jobs'||page==='dashboard')) params.set('location',state.location);
  const [profile,jobs,apps,sources,stats,jobDiagnostics,aiStatus,smartApplyAnswers,smartApplyPreferences] = await Promise.all([
    needsProfile?api('/profile'):Promise.resolve(null),
    needsJobs?api(`/jobs?${params}`):Promise.resolve(null),
    needsApplications?api('/applications'):Promise.resolve(null),
    needsSources?api('/sources'):Promise.resolve(null),
    needsStats?api(`/stats?includeStrongMatches=true&freshness=${encodeURIComponent(selectedFreshness)}&minScore=${state.minScore}&q=${encodeURIComponent(state.query)}&location=${encodeURIComponent(state.location)}`):Promise.resolve(null),
    needsJobDiagnostics?api('/jobs/diagnostics'):Promise.resolve(null),
    needsAiStatus?api('/ai/status'):Promise.resolve(null),
    needsSmartApply?api('/smart-apply/answers'):Promise.resolve(null),
    needsSmartApply?api('/smart-apply/preferences'):Promise.resolve(null)
  ]);
  if(profile)state.profile=profile;
  if(jobs)state.jobs=jobs.map(j=>({...j,matchScore:j.score}));
  if(apps){
    state.applications=apps;
    discardInactiveAutoApplyContext();
  }
  if(sources)state.sources=sources.sources||[];
  if(stats)state.stats=stats;
  if(jobDiagnostics)state.jobDiagnostics=jobDiagnostics;
  if(aiStatus)state.aiStatus=aiStatus;
  if(smartApplyAnswers)state.smartApplyAnswers=smartApplyAnswers;
  if(smartApplyPreferences)state.smartApplyPreferences=smartApplyPreferences;
  const accountName=document.getElementById('account-name');
  const accountAvatar=document.getElementById('account-avatar');
  const accountAvatarImage=document.getElementById('account-avatar-image');
  const activeProfile=state.profile||{};
  if(accountName) accountName.textContent=activeProfile.fullName||'Candidate';
  if(accountAvatar) accountAvatar.textContent=initials(activeProfile.fullName||'Candidate').slice(0,1);
  if(accountAvatarImage){
    const savedAvatar=localStorage.getItem(accountAvatarStorageKey);
    accountAvatarImage.hidden=!savedAvatar;
    if(savedAvatar) accountAvatarImage.src=savedAvatar;
    const dashboardAvatarImage=document.getElementById('dashboard-avatar-image');
    const dashboardAvatarFallback=document.getElementById('dashboard-avatar-fallback');
    if(dashboardAvatarImage){
      dashboardAvatarImage.hidden=!savedAvatar;
      if(savedAvatar)dashboardAvatarImage.src=savedAvatar;
      if(dashboardAvatarFallback)dashboardAvatarFallback.hidden=Boolean(savedAvatar);
    }
  }
}
function go(page) {
  if(page==='autoapply')page='browser';
  if (page === state.page) return;
  state.page = page;
  window.history.pushState({page,selectedJob:state.selectedJob,detailReturnPage:state.detailReturnPage},'');
  updateNavigation(page);
  render().catch(showError);
}
function updateNavigation(page){
  const normalizedPage=page==='autoapply'?'browser':page;
  document.body.classList.toggle('dashboard-page',normalizedPage==='dashboard');
  document.body.classList.toggle('browser-assistant-active',normalizedPage==='browser');
  const activePage=normalizedPage==='job-detail'?state.detailReturnPage:normalizedPage;
  document.querySelectorAll('.nav-item').forEach(el => el.classList.toggle('active', el.dataset.page === activePage));
}
function showError(error) {
  const staleOutreachBackend=error.status===404&&['/outreach','/portal-capabilities'].includes(error.path);
  const message=staleOutreachBackend
    ?'The backend currently running does not include the Messages & Outreach API. Restart the backend from this project, then retry.'
    :error.message||error;
  content.innerHTML = `<div class="card error"><h2>Something went wrong</h2><p>${esc(message)}</p><p class="muted">${staleOutreachBackend?'The updated backend provides this endpoint at http://localhost:8080.':'Make sure the Java backend is running at http://localhost:8080.'}</p><button class="button" id="retry">Try again</button></div>`;
  document.getElementById('retry')?.addEventListener('click', () => render().catch(showError));
}
async function render() {
  if(state.page==='autoapply')state.page='browser';
  if(state.page!=='browser'&&state.browserAssistant.popupSubscription){
   state.browserAssistant.popupSubscription();
   state.browserAssistant.popupSubscription=null;
  }
  document.body.classList.toggle('dashboard-page',state.page==='dashboard');
  try { await refreshData(); setConnection(true); }
  catch (e) { setConnection(false); throw e; }
  if(state.page==='browser'){
   if(state.browserAssistant.suppressAutoApplyRestore)state.browserAssistant.suppressAutoApplyRestore=false;
   else await restoreAutoApplyContext();
  }
  if (state.page === 'dashboard') renderDashboard();
  else if (state.page === 'profile') renderProfile();
  else if (state.page === 'jobs') renderJobs();
  else if (state.page === 'browser') renderBrowserAssistant();
  else if (state.page === 'job-detail') renderJobDescriptionPage();
  else if (state.page === 'sources') await renderSources();
  else if (state.page === 'applications') renderApplications();
  else if (state.page === 'messages') await renderOutreach();
  else if (state.page === 'capabilities') await renderPortalCapabilities();
  else if (state.page === 'saved') renderSaved();
  else if (state.page === 'settings') await renderSettings();
  else renderInterview();
}

function renderBrowserAssistant() {
 const portals=[['Naukri','Naukri.com'],['LinkedIn','LinkedIn'],['Google Jobs','Google Jobs'],['Indeed','Indeed'],['Glassdoor','Glassdoor'],['Other Site','Other Site']];
 content.innerHTML=`<section class="browser-assistant-page">
  <header class="browser-page-heading"><div><h2>AutoApply</h2><p>Search Naukri, import visible listings, and prepare an application only when you choose.</p></div><div class="browser-supervision"><span class="browser-supervision-check">✓</span><div><strong>Supervised Mode</strong><small>Search and import are user-triggered; Menix never clicks Apply or submits while discovering jobs.</small></div><button class="browser-learn-more" id="browser-learn-more">Learn more</button></div></header>
  <div class="browser-portal-row">${portals.map(([value,label])=>`<button type="button" class="browser-portal-button ${state.browserAssistant.portal===value?'active':''}" data-browser-portal="${esc(value)}"><span class="browser-portal-mark mark-${value.toLowerCase().replaceAll(' ','-')}">${value==='Naukri'?'◉':value==='LinkedIn'?'in':value==='Indeed'?'i':value==='Glassdoor'?'G':value==='Google Jobs'?'G':'↗'}</span>${esc(label)}</button>`).join('')}</div>
  <div class="browser-search-row">
   ${state.browserAssistant.portal==='Naukri'?`<div class="browser-search-controls"><label class="browser-control"><span>Keywords</span><input id="browser-search-keyword" value="${esc(state.query)}" placeholder="Full Stack Developer, Software Engineer"></label><label class="browser-control"><span>Location</span><input id="browser-search-location" value="${esc(state.location)}" placeholder="Remote, India, city"></label><label class="browser-control"><span>Experience</span><select id="browser-search-experience"><option value="fresher" ${state.browserAssistant.experience==='fresher'?'selected':''}>Fresher</option><option value="any" ${state.browserAssistant.experience==='any'?'selected':''}>Any experience</option></select></label><label class="browser-control"><span>Work mode</span><select id="browser-search-work-mode"><option ${state.browserAssistant.workMode==='Any'?'selected':''}>Any</option><option ${state.browserAssistant.workMode==='Remote'?'selected':''}>Remote</option><option ${state.browserAssistant.workMode==='On-site'?'selected':''}>On-site</option><option ${state.browserAssistant.workMode==='Hybrid'?'selected':''}>Hybrid</option></select></label><button type="button" class="browser-open-button" id="browser-search-naukri">Search Naukri</button><button type="button" class="browser-open-button browser-stop-search" id="browser-stop-search" hidden>Stop Search</button></div><small class="browser-browse-hint">Work-mode filtering is not added to Naukri's URL; apply it on Naukri if that filter is available.</small>`:`<span class="browser-browse-hint">Search and navigate on the website as you normally would.</span><button type="button" class="browser-open-button" id="browser-open-portal">Open ${esc(state.browserAssistant.portal==='Other Site'?'Website':portals.find(p=>p[0]===state.browserAssistant.portal)?.[1]||'Website')} <span aria-hidden="true">↗</span></button>`}
  </div>
  ${state.browserAssistant.portal==='Other Site'?`<label class="browser-other-site">Other site HTTPS address<input id="browser-other-url" type="url" placeholder="https://careers.example.com" value="${esc(state.browserAssistant.otherUrl)}"></label>`:''}
  <div class="browser-workspace">
   <section class="browser-window" aria-label="Job portal browser">
    <div class="browser-chrome-tab"><span class="browser-chrome-icon">${state.browserAssistant.portal==='Naukri'?'◉':'↗'}</span><span id="browser-current-tab">${esc(state.browserAssistant.hostname||portals.find(p=>p[0]===state.browserAssistant.portal)?.[1]||'Other Site')}</span><button type="button" id="browser-close-session" aria-label="Close browser tab">×</button><button type="button" id="browser-new-tab" aria-label="Open portal">+</button><span class="browser-chrome-spacer"></span><button type="button" id="browser-remove-view" aria-label="Close browser panel">×</button></div>
    <form class="browser-address-bar" id="browser-address-form"><button type="button" id="browser-back" aria-label="Back">‹</button><button type="button" id="browser-forward" aria-label="Forward">›</button><button type="button" id="browser-reload" aria-label="Reload">⟳</button><span class="browser-lock" aria-hidden="true">▣</span><input id="browser-address" aria-label="Website address" placeholder="Search the job portal manually"><button type="submit" aria-label="Open address">↵</button></form>
    <div class="browser-view-wrap" id="browser-view-wrap"><div class="browser-empty-state"><div class="browser-empty-icon">◎</div><strong>Ready to browse</strong><span>Choose a portal and open its official website. Search and sign in there as you normally would.</span><button type="button" class="browser-open-empty" id="browser-empty-open">Open ${esc(state.browserAssistant.portal==='Other Site'?'website':portals.find(p=>p[0]===state.browserAssistant.portal)?.[1]||'website')}</button></div></div>
    <div class="browser-status-line"><span class="browser-status-dot ${state.browserAssistant.status==='User Browsing'?'is-active':''}" id="browser-status-dot"></span><span id="browser-session-status">${esc(state.browserAssistant.status==='Not Started'?'Ready to Browse':state.browserAssistant.status)}</span><span id="browser-session-host">${state.browserAssistant.hostname?esc(state.browserAssistant.hostname):'Manual mode'}</span><span id="browser-discovery-status">${esc(state.browserAssistant.lastImportAt?`${state.browserAssistant.importStatus} · Last import ${state.browserAssistant.lastImportAt}`:state.browserAssistant.importStatus)}</span><button type="button" id="browser-import-visible-jobs" ${state.browserAssistant.portal!=='Naukri'?'disabled':''}>Import visible jobs</button><button type="button" id="browser-verify-apply-url" hidden>Confirm current page as Apply link</button><button type="button" class="browser-save-current-job" id="browser-save-current-job" hidden ${state.browserAssistant.applicationInspectionEnabled?'disabled':''}>Save this job</button><button type="button" id="browser-open-external">Open in system browser ↗</button></div>
   </section>
   <aside class="browser-job-panel" aria-label="Selected job details">
    <nav class="browser-detail-tabs" aria-label="AutoApply workspace tabs"><button type="button" data-browser-tab="details" class="active">Job Details</button><button type="button" data-browser-tab="ai">Job Match</button><button type="button" data-browser-tab="prepare">Detect &amp; Fill</button><button type="button" data-browser-tab="review">Review &amp; Submit</button><button type="button" data-browser-tab="history">Answer History</button></nav>
    <div id="browser-detail-content" class="browser-detail-content"></div>
   </aside>
  </div>
 </section>`;
 document.querySelectorAll('[data-browser-portal]').forEach(button=>button.addEventListener('click',()=>{state.browserAssistant.portal=button.dataset.browserPortal;state.browserAssistant.url='';state.browserAssistant.hostname='';state.browserAssistant.status='Not Started';state.browserAssistant.applicationInspectionEnabled=false;renderBrowserAssistant();}));
 document.querySelectorAll('[data-browser-tab]').forEach(button=>button.addEventListener('click',()=>{state.browserAssistant.selectedTab=button.dataset.browserTab;renderBrowserDetails();}));
 document.getElementById('browser-other-url')?.addEventListener('input',event=>{state.browserAssistant.otherUrl=event.target.value;});
 document.getElementById('browser-search-naukri')?.addEventListener('click',()=>startNaukriSearch().catch(error=>toast(`Could not search Naukri: ${error.message}`)));
 document.getElementById('browser-stop-search')?.addEventListener('click',stopNaukriSearch);
 document.getElementById('browser-import-visible-jobs')?.addEventListener('click',()=>importVisibleNaukriJobs().catch(error=>toast(`Naukri import failed: ${error.message}`)));
 document.getElementById('browser-verify-apply-url')?.addEventListener('click',()=>verifyCurrentApplyDestination().catch(error=>toast(`Could not verify Apply destination: ${error.message}`)));
 document.getElementById('browser-open-portal')?.addEventListener('click',()=>openBrowserAssistant().catch(error=>toast(`Could not open portal: ${error.message}`)));
 document.getElementById('browser-empty-open').addEventListener('click',()=>openBrowserAssistant().catch(error=>toast(`Could not open portal: ${error.message}`)));
 document.getElementById('browser-new-tab').addEventListener('click',()=>openBrowserAssistant().catch(error=>toast(`Could not open portal: ${error.message}`)));
 document.getElementById('browser-close-session').addEventListener('click',closeEmbeddedBrowser);
 document.getElementById('browser-remove-view').addEventListener('click',closeEmbeddedBrowser);
 document.getElementById('browser-address-form').addEventListener('submit',navigateEmbeddedBrowser);
 document.getElementById('browser-back').addEventListener('click',()=>embeddedBrowserView()?.canGoBack()&&embeddedBrowserView().goBack());
 document.getElementById('browser-forward').addEventListener('click',()=>embeddedBrowserView()?.canGoForward()&&embeddedBrowserView().goForward());
 document.getElementById('browser-reload').addEventListener('click',()=>embeddedBrowserView()?.reload());
 document.getElementById('browser-open-external').addEventListener('click',()=>openCurrentBrowserInWindow().catch(error=>toast(error.message)));
 document.getElementById('browser-save-current-job').addEventListener('click',()=>saveCurrentJob().catch(error=>toast(`Could not save this job: ${error.message}`)));
 state.browserAssistant.popupSubscription?.();
 state.browserAssistant.popupSubscription=window.menixDesktop?.onEmbeddedBrowserPopup?.(url=>handleEmbeddedBrowserPopup(url))||null;
 document.getElementById('browser-learn-more').addEventListener('click',()=>toast('Search opens the official Naukri results page. Import reads only the rendered listing cards after your click. It never scrolls, clicks Apply, bypasses access controls, or submits forms.'));
 renderBrowserDetails();
 if(state.browserAssistant.sessionConsent&&state.browserAssistant.url)mountEmbeddedBrowser();
 updateNaukriDiscoveryControls();
}

function persistAutoApplyContext(tab=state.browserAssistant.autoApplyTab){
 if(!tab?.applicationId||!tab.jobId)return;
 const job=state.jobs.find(item=>Number(item.id)===Number(tab.jobId));
 localStorage.setItem(AUTO_APPLY_CONTEXT_KEY,JSON.stringify({
 applicationId:tab.applicationId,jobId:tab.jobId,url:tab.url,sessionId:tab.sessionId||null,
 company:tab.company||job?.company||'',title:job?.title||tab.label||'',job
 }));
}

function clearAutoApplyContext(){
 localStorage.removeItem(AUTO_APPLY_CONTEXT_KEY);
}

function discardInactiveAutoApplyContext(){
 const tab=state.browserAssistant.autoApplyTab;
 if(!tab)return;
 const application=state.applications.find(item=>String(item.id)===String(tab.applicationId)&&Number(item.jobId)===Number(tab.jobId));
 const terminalStatuses=['SUBMITTED','WITHDRAWN','NOT_COMPLETED_YET','DECLINED','REJECTED','CLOSED'];
 if(application&&!terminalStatuses.includes(String(application.status||'').toUpperCase()))return;
 clearAutoApplyContext();
 state.browserAssistant.autoApplyTab=null;
 state.browserAssistant.selectedTab='details';
}

async function restoreAutoApplyContext(){
 if(state.browserAssistant.autoApplyTab)return;
 const raw=localStorage.getItem(AUTO_APPLY_CONTEXT_KEY);
 if(!raw)return;
 let saved;
 try{saved=JSON.parse(raw);}
 catch(error){clearAutoApplyContext();toast('The saved AutoApply session was invalid and has been cleared.');return;}
 const application=state.applications.find(item=>String(item.id)===String(saved.applicationId)&&Number(item.jobId)===Number(saved.jobId));
 const terminalStatuses=['SUBMITTED','WITHDRAWN','NOT_COMPLETED_YET','DECLINED','REJECTED','CLOSED'];
 if(!application||terminalStatuses.includes(String(application.status||'').toUpperCase())){
 clearAutoApplyContext();
 return;
 }
 let url;
 try{url=validateBrowserUrl(saved.url);}
 catch(error){clearAutoApplyContext();toast(`The saved AutoApply link is no longer valid: ${error.message}`);return;}
 let job=state.jobs.find(item=>Number(item.id)===Number(saved.jobId))||saved.job;
 if(!job){
 try{job=await api(`/jobs/${saved.jobId}`);}
 catch(error){toast(`The saved AutoApply job could not be restored: ${error.message}`);return;}
 }
 if(!job||Number(job.id)!==Number(saved.jobId)){
 clearAutoApplyContext();
 return;
 }
 state.jobs=[job,...state.jobs.filter(item=>Number(item.id)!==Number(job.id))];
 state.browserAssistant.savedJobId=Number(job.id);
 state.browserAssistant.selectedTab='prepare';
 state.browserAssistant.url=url;
 state.browserAssistant.hostname=new URL(url).hostname;
 state.browserAssistant.sessionConsent=true;
 state.browserAssistant.autoApplyTab={
 id:`browser-application-${application.id}`,label:`${job.company||saved.company||'Company'} — ${job.title||saved.title||'Application'}`,
 url,view:null,jobId:Number(job.id),applicationId:application.id,sessionId:saved.sessionId||null,
 company:job.company||saved.company||'the employer',status:'Ready',fields:[],uploads:[],
 submitCandidates:[],reviewed:false,fieldsFilled:0
 };
 state.browserAssistant.applicationInspectionEnabled=true;
 try{state.autoApply.resumeOptions=await api('/resumes');}
 catch(error){toast(`Saved resumes could not load: ${error.message}`);}
}

function getBrowserAssistantUrl() {
 const roots={Naukri:'https://www.naukri.com/mnjuser/recommendedjobs?src=gnbOpportunities',LinkedIn:'https://www.linkedin.com/jobs/', 'Google Jobs':'https://www.google.com/search?ibp=htl;jobs',Indeed:'https://www.indeed.com/',Glassdoor:'https://www.glassdoor.com/Job/index.htm','Other Site':state.browserAssistant.otherUrl};
 const url=roots[state.browserAssistant.portal];
 if(!url)throw new Error('Choose a supported job site before opening it.');
 return validateBrowserUrl(url);
}

async function openBrowserAssistant() {
 state.browserAssistant.applicationInspectionEnabled=false;
 if(state.browserAssistant.autoApplyTab)state.browserAssistant.autoApplyTab.view=null;
 state.browserAssistant.sessionConsent=true;
 const saveButton=document.getElementById('browser-save-current-job');if(saveButton)saveButton.disabled=false;
 navigateBrowserView(getBrowserAssistantUrl());
}

function validateBrowserUrl(rawUrl) {
 let url;
 try{url=new URL(String(rawUrl||''));}catch{throw new Error('Enter a valid portal HTTPS address.');}
 if(url.protocol!=='https:'||url.username||url.password)throw new Error('Only public HTTPS links without embedded credentials can be opened.');
 return url.href;
}

function embeddedBrowserView(){return document.querySelector('#browser-view-wrap webview');}
function hideCurrentJobSaveButton(){
 const button=document.getElementById('browser-save-current-job');
 if(button)button.hidden=true;
}
function isCurrentJobPage(){
 const visible=node=>{
  if(!node||!node.getClientRects().length)return false;
  const style=getComputedStyle(node);
  return style.display!=='none'&&style.visibility!=='hidden';
 };
 const postings=[];
 const collectPostings=value=>{
  if(Array.isArray(value)){value.forEach(collectPostings);return;}
  if(!value||typeof value!=='object')return false;
  const types=Array.isArray(value['@type'])?value['@type']:[value['@type']];
  if(types.includes('JobPosting'))postings.push(value);
  if(value['@graph'])collectPostings(value['@graph']);
 };
 for(const script of document.querySelectorAll('script[type="application/ld+json"]')){
  try{collectPostings(JSON.parse(script.textContent||''));}catch{}
 }
 if(postings.length===1){
  const raw=typeof postings[0].url==='string'?postings[0].url:postings[0].url?.url;
  if(!raw)return true;
  try{
   const postingUrl=new URL(raw,location.href);
   if(postingUrl.origin===location.origin&&postingUrl.pathname===location.pathname)return true;
  }catch{}
 }
 const detailMarkup=['#jobDescriptionText','.jobs-description__content','.jd-description','[data-automation="jobDetails"]'];
 const hasVisibleDescription=detailMarkup.some(selector=>Array.from(document.querySelectorAll(selector)).some(visible));
 const visibleTitle=Array.from(document.querySelectorAll('h1,[data-test="job-title"],[data-automation="jobTitle"]')).some(node=>visible(node)&&String(node.innerText||node.textContent||'').trim());
 return Boolean(visibleTitle&&hasVisibleDescription);
}
async function updateCurrentJobSaveAvailability(view=embeddedBrowserView()){
 const button=document.getElementById('browser-save-current-job');
 if(!button)return;
 button.hidden=true;
 if(!view)return;
 let url;
 try{url=new URL(view.getURL());}catch{return;}
 if(url.protocol!=='https:')return;
 button.hidden=!(await view.executeJavaScript(`(${isCurrentJobPage.toString()})()`,true));
}
function mountEmbeddedBrowser(){
 const wrap=document.getElementById('browser-view-wrap');
 if(!wrap||embeddedBrowserView())return;
 if(!state.browserAssistant.sessionConsent)return;
 const source=state.browserAssistant.url||getBrowserAssistantUrl();
 state.browserAssistant.url=validateBrowserUrl(source);
 state.browserAssistant.hostname=new URL(state.browserAssistant.url).hostname;
 state.browserAssistant.status='Opening Website';
 const status=document.getElementById('browser-session-status');if(status)status.textContent='Opening Website';
 const address=document.getElementById('browser-address');if(address)address.value=state.browserAssistant.url;
 const tab=document.getElementById('browser-current-tab');if(tab)tab.textContent=state.browserAssistant.hostname;
 const src=` src="${esc(state.browserAssistant.url)}"`;
 wrap.innerHTML=`<webview class="browser-embedded-view" partition="persist:menix-application-sessions" webpreferences="contextIsolation=yes,nodeIntegration=no,sandbox=yes"${src}></webview>`;
 const view=embeddedBrowserView();
 if(state.browserAssistant.applicationInspectionEnabled&&state.browserAssistant.autoApplyTab)state.browserAssistant.autoApplyTab.view=view;
 view.addEventListener('did-start-loading',()=>setBrowserViewStatus('Opening Website'));
 view.addEventListener('did-finish-load',()=>{
  syncBrowserViewLocation();
  updateCurrentJobSaveAvailability(view).catch(error=>toast(`Could not check this page for job details: ${error.message}`));
  if(state.browserAssistant.importStatus.startsWith('Opening Naukri search'))state.browserAssistant.importStatus='Naukri results ready; import visible listings when ready.';
  updateNaukriDiscoveryControls();
  if(state.browserAssistant.applicationInspectionEnabled&&state.browserAssistant.autoApplyTab?.view===view)inspectAutoApplyForm();
 });
 view.addEventListener('did-navigate',event=>{syncBrowserViewLocation(event.url);hideCurrentJobSaveButton();});
 view.addEventListener('did-navigate-in-page',event=>{syncBrowserViewLocation(event.url);hideCurrentJobSaveButton();updateCurrentJobSaveAvailability(view).catch(error=>toast(`Could not check this page for job details: ${error.message}`));});
 view.addEventListener('page-title-updated',event=>{const tab=document.getElementById('browser-current-tab');if(tab&&event.title)tab.textContent=event.title;});
 view.addEventListener('did-fail-load',event=>{
  if(event.errorCode===-3)return;
  state.browserAssistant.status='Failed';
  state.browserAssistant.importStatus=`Page failed to load: ${event.errorDescription||'Network or website error'}`;
  setBrowserViewStatus('Failed');
  toast(`Portal could not load: ${event.errorDescription||'Network or website error'}`);
 });
}

function navigateBrowserView(rawUrl){
 const url=validateBrowserUrl(rawUrl);
 hideCurrentJobSaveButton();
 state.browserAssistant.url=url;
 state.browserAssistant.hostname=new URL(url).hostname;
 state.browserAssistant.status='Opening Website';
 const address=document.getElementById('browser-address');
 const tab=document.getElementById('browser-current-tab');
 if(address)address.value=url;
 if(tab)tab.textContent=state.browserAssistant.hostname;
 setBrowserViewStatus('Opening Website');
 const view=embeddedBrowserView();
 if(!view){mountEmbeddedBrowser();return;}
 view.loadURL(url);
}

function syncBrowserViewLocation(rawUrl){
 const view=embeddedBrowserView();
 let url=rawUrl||view?.getURL()||'';
 if(!url||url==='about:blank')return;
 try{url=validateBrowserUrl(url);}catch{return;}
 state.browserAssistant.url=url;
 state.browserAssistant.hostname=new URL(url).hostname;
 state.browserAssistant.status='User Browsing';
 const address=document.getElementById('browser-address');
 const tab=document.getElementById('browser-current-tab');
 if(address)address.value=url;
 if(tab)tab.textContent=state.browserAssistant.hostname;
 hideCurrentJobSaveButton();
 setBrowserViewStatus('User Browsing');
 updateNaukriDiscoveryControls();
}

function setBrowserViewStatus(status){
 state.browserAssistant.status=status;
 const statusElement=document.getElementById('browser-session-status');
 const host=document.getElementById('browser-session-host');
 const dot=document.getElementById('browser-status-dot');
 if(statusElement)statusElement.textContent=status==='User Browsing'?'User Browsing':status;
 if(host)host.textContent=state.browserAssistant.hostname||'Manual mode';
 dot?.classList.toggle('is-active',status==='User Browsing');
 updateNaukriDiscoveryControls();
}

function navigateEmbeddedBrowser(event){
 event.preventDefault();
 const address=document.getElementById('browser-address');
 if(!address)return;
 try{navigateBrowserView(address.value);}catch(error){toast(error.message);}
}

function closeEmbeddedBrowser(){
 const view=embeddedBrowserView();
 view?.remove();
 clearAutoApplyContext();
 if(state.browserAssistant.autoApplyTab)state.browserAssistant.autoApplyTab.view=null;
 state.browserAssistant.url='';
 state.browserAssistant.hostname='';
 state.browserAssistant.status='Closed';
 state.browserAssistant.sessionConsent=false;
 state.browserAssistant.applicationInspectionEnabled=false;
 const wrap=document.getElementById('browser-view-wrap');
 if(wrap)wrap.innerHTML='<div class="browser-empty-state"><div class="browser-empty-icon">◎</div><strong>Browser tab closed</strong><span>Your portal sign-in may remain saved. Sign out on the portal to end it.</span><button type="button" class="browser-open-empty" id="browser-empty-open">Reopen portal</button></div>';
 document.getElementById('browser-empty-open')?.addEventListener('click',()=>openBrowserAssistant(false).catch(error=>toast(error.message)));
 const address=document.getElementById('browser-address');if(address)address.value='';
 const tab=document.getElementById('browser-current-tab');if(tab)tab.textContent=state.browserAssistant.portal;
 setBrowserViewStatus('Closed');
}

async function openCurrentBrowserInWindow(){
 const url=state.browserAssistant.url||getBrowserAssistantUrl();
 if(!window.menixDesktop?.openApplication)throw new Error('Open-in-browser is available in the Menix desktop app.');
 await window.menixDesktop.openApplication(validateBrowserUrl(url));
}

function renderBrowserDetails(){
 const target=document.getElementById('browser-detail-content');
 if(!target)return;
 const job=state.browserAssistant.savedJobId?state.jobs.find(item=>Number(item.id)===Number(state.browserAssistant.savedJobId)):null;
 document.querySelectorAll('[data-browser-tab]').forEach(button=>button.classList.toggle('active',button.dataset.browserTab===state.browserAssistant.selectedTab));
 const detailsTab=state.browserAssistant.selectedTab==='details';
 const jobTitle=job?.title||'';
 const jobCompany=job?.company||'';
 const score=Number(state.browserAssistant.match?.score);
 const matchData=state.browserAssistant.match;
 let body='';
 if(detailsTab){
  body=`<div class="browser-job-summary">${job?`<div class="browser-company-logo">${esc(initials(job.company||'Job').slice(0,1))}</div><div class="browser-job-heading"><strong>${esc(job.title)}</strong><span>${esc(job.company||'Company not specified')}</span><small>${esc(job.location||'Location not specified')} · ${esc(job.workMode||'Work arrangement not specified')}</small></div><button type="button" class="browser-bookmark" aria-label="Saved job">♧</button>`:`<div class="browser-job-empty"><strong>No job selected yet</strong><span>Choose a listing in the portal and enter its verified details below.</span></div>`}</div>
   <form id="browser-save-job" class="browser-job-form">
    <label class="browser-field"><span>Original job URL</span><input name="url" type="url" value="${esc(job?.url||'')}" placeholder="https://www.naukri.com/job-listings/..." ${job?'readonly':''} required></label>
    <div class="browser-form-row"><label class="browser-field"><span>Job title</span><input name="title" value="${esc(jobTitle)}" placeholder="Full Stack Developer" ${job?'readonly':''} required></label><label class="browser-field"><span>Company</span><input name="company" value="${esc(jobCompany)}" placeholder="Company name" ${job?'readonly':''}></label></div>
    <div class="browser-form-row"><label class="browser-field"><span>Location</span><input name="location" value="${esc(job?.location||state.browserAssistant.location)}" placeholder="Remote" ${job?'readonly':''}></label><label class="browser-field"><span>Work arrangement</span><input name="workMode" value="${esc(job?.workMode||state.browserAssistant.workMode)}" placeholder="Remote / hybrid / on-site" ${job?'readonly':''}></label></div>
    <label class="browser-field"><span>Experience requirements</span><input name="experienceRequirements" value="${esc(job?.experienceRequirements||(state.browserAssistant.experience==='fresher'?'Fresher / entry level':''))}" placeholder="Fresher / 0-2 years" ${job?'readonly':''}></label>
    <label class="browser-field"><span>Key skills</span><input name="requiredSkills" value="${esc(job?.requiredSkills||'')}" placeholder="Java, SQL, PostgreSQL" ${job?'readonly':''}></label>
    <label class="browser-field"><span>Verified notes (optional)</span><textarea name="description" placeholder="Paste the description you reviewed on the original listing" ${job?'readonly':''}>${esc(job?.description||'')}</textarea></label>
    <div class="browser-detail-actions"><button class="button browser-save-action" type="submit" ${job?'disabled':''}>${job?'Job Saved':'Save Job'}</button><button class="button browser-prepare-action" id="browser-prepare-application" type="button" ${job?'':'disabled'}>Prepare Application</button><button class="button browser-open-listing-action" id="browser-open-listing" type="button" ${job?'':'disabled'}>Open in AutoApply</button></div>
   </form>`;
 }else if(state.browserAssistant.selectedTab==='ai'){
  body=job&&matchData?`<div class="browser-match-heading"><strong>AI Match Analysis</strong><span>Profile-based match</span></div><div class="browser-match-analysis"><div class="browser-match-ring" style="--match-score:${Math.max(0,Math.min(100,score||0))}"><strong>${Number.isFinite(score)?score:0}%</strong><span>Match</span></div><ul>${(matchData.explanation||'').split(/[.;]\s*/).filter(Boolean).map(item=>`<li>${esc(item)}</li>`).join('')||'<li>Score calculated from your saved candidate profile and this job listing.</li>'}</ul></div><h4>Required skills</h4><div class="browser-skill-list">${(String(job.requiredSkills||'').split(/[,;|\\n]/).map(item=>item.trim()).filter(Boolean).map(skill=>`<span>${esc(skill)}</span>`).join(''))||'<span>Not listed by the job details provided</span>'}</div>`:`<div class="browser-tab-empty"><strong>${job?'Calculating your match…':'Match analysis is not available yet'}</strong><span>${job?'The score is generated from your saved profile and this job.':'Save a job after reviewing its real listing to calculate a profile match.'}</span></div>`;
 }else if(state.browserAssistant.selectedTab==='ai'){
  body=job&&matchData?`<div class="browser-match-heading"><strong>AI Match Analysis</strong><span>Profile-based match</span></div><div class="browser-match-analysis"><div class="browser-match-ring" style="--match-score:${Math.max(0,Math.min(100,score||0))}"><strong>${Number.isFinite(score)?score:0}%</strong><span>Match</span></div><ul>${(matchData.explanation||'').split(/[.;]\s*/).filter(Boolean).map(item=>`<li>${esc(item)}</li>`).join('')||'<li>Score calculated from your saved candidate profile and this job listing.</li>'}</ul></div><h4>Required skills</h4><div class="browser-skill-list">${(String(job.requiredSkills||'').split(/[,;|\\n]/).map(item=>item.trim()).filter(Boolean).map(skill=>`<span>${esc(skill)}</span>`).join(''))||'<span>Not listed by the job details provided</span>'}</div>`:`<div class="browser-tab-empty"><strong>${job?'Calculating your match…':'Match analysis is not available yet'}</strong><span>${job?'The score is generated from your saved profile and this job.':'Save a job after reviewing its real listing to calculate a profile match.'}</span></div>`;
 }else if(state.browserAssistant.selectedTab==='history'){
  const answers=state.smartApplyAnswers.filter(answer=>!answer.excluded).slice(0,12);
  body=`<div class="browser-tab-content browser-answer-history"><h3>Approved Answer History</h3><p>Previously approved answers can be suggested for similar questions. Review each answer before using it.</p>${answers.length?`<div class="browser-answer-list">${answers.map(answer=>`<article><strong>${esc(answer.question)}</strong><span>${esc(answer.answer)}</span><small>${esc(answer.category||'General')} · ${Number(answer.confidence||0)?`${Math.round(Number(answer.confidence)*100)}% confidence`:'Approved answer'}</small></article>`).join('')}</div>`:'<div class="browser-tab-empty"><strong>No approved answers yet</strong><span>Approve an answer during form review to build reusable memory.</span></div>'}<button type="button" id="browser-answer-memory-settings">View and edit all answers</button></div>`;
 }else if(state.browserAssistant.selectedTab==='prepare'){
  const autoApplyTab=state.browserAssistant.autoApplyTab;
  if(autoApplyTab&&Number(autoApplyTab.jobId)===Number(job?.id)){
   const resumes=state.autoApply.resumeOptions||[];
   const selectedResume=state.autoApply.selectedResume||localStorage.getItem('menix-selected-resume')||'';
   body=`<div class="browser-tab-content"><h3>AutoApply · ${esc(job.title)}</h3><p>The employer form and AutoApply controls stay together in this workspace. Verify the employer and form owner. Smart Autofill only fills supported, high-confidence profile facts; it never submits.</p><label class="browser-field"><span>Saved resume (optional for profile autofill)</span><select id="application-resume"><option value="">${resumes.length?'No resume selected':'No saved resumes'}</option>${resumes.map(resume=>`<option value="${esc(resume.storageName)}" ${resume.storageName===selectedResume?'selected':''}>${esc(resume.filename)}</option>`).join('')}</select></label><div class="browser-autoapply-actions"><button class="button" id="application-inspect" type="button">Rescan Form</button><button class="button" id="application-save-draft" type="button">Save as Draft</button><button class="button button-primary" id="application-submit-button" type="button" disabled>Submit Application</button></div><details class="application-more-tools"><summary>More form tools</summary><div class="browser-autoapply-actions"><button class="button" id="application-autofill" type="button">Smart Autofill</button><button class="button" id="application-suggest-missing" type="button">Suggest Missing Answers</button><button class="button" id="application-clear-suggestions" type="button">Clear Current Suggestions</button><button class="button" id="application-review-button" type="button">Review Form</button><button class="button" id="application-upload-resume" type="button" disabled>Choose resume file</button><button class="button" id="application-view-memory" type="button">Answer Memory</button></div></details><span id="application-status-badge" class="status-pill">${esc(autoApplyTab.status||'Ready')}</span><p id="application-assist-status" class="muted small" aria-live="polite">The form will be scanned when it finishes loading. Smart Autofill remains a separate action.</p><section id="application-field-review" class="application-field-review"><p class="muted">Waiting for the application form to load…</p></section></div>`;
  }else{
   body=`<div class="browser-tab-content"><h3>Prepare Application</h3><p>${job?`Prepare materials for <strong>${esc(job.title)}</strong> at ${esc(job.company||'the employer')}.`: 'Save a selected job before preparing an application.'}</p><div class="browser-safe-note">The employer page and AutoApply controls stay together in this AutoApply workspace. Submission always requires your confirmation.</div><button class="button browser-prepare-action" id="browser-prepare-tab-action" type="button" ${job?'':'disabled'}>Open AutoApply in this window</button></div>`;
  }
 }else{
  const autoApplyTab=state.browserAssistant.autoApplyTab;
  body=autoApplyTab&&Number(autoApplyTab.jobId)===Number(job?.id)
   ?`<div class="browser-tab-content"><h3>Review &amp; Submit</h3><p>Check every answer on the employer form. The page and controls remain in this AutoApply workspace.</p><div class="browser-safe-note">Submission still requires your explicit confirmation. The tracker changes to Submitted only after employer confirmation.</div><div class="browser-autoapply-actions"><button class="button" id="application-review-button" type="button">Review form</button><button class="button button-primary" id="application-submit-button" type="button" disabled>Submit application</button></div><span id="application-status-badge" class="status-pill">${esc(autoApplyTab.status||'Ready')}</span><p id="application-assist-status" class="muted small" aria-live="polite">Review the employer form before submitting.</p><section id="application-field-review" class="application-field-review"><p class="muted">Open Detect &amp; Fill to inspect the form.</p></section></div>`
   :`<div class="browser-tab-content"><h3>Review &amp; Submit</h3><p>Check the employer's application form, resume attachment, and every answer in the portal.</p><div class="browser-safe-note">Start AutoApply from Prepare Application to keep the form, review, and submission controls in this window.</div><button class="button browser-open-listing-action" id="browser-review-open" type="button" ${job?'':'disabled'}>Open Original Listing</button></div>`;
 }
 target.innerHTML=body;
 document.getElementById('browser-save-job')?.addEventListener('submit',saveBrowserAssistantJob);
 document.getElementById('browser-prepare-application')?.addEventListener('click',()=>prepareBrowserAssistantJob(job));
 document.getElementById('browser-prepare-tab-action')?.addEventListener('click',()=>prepareBrowserAssistantJob(job));
 document.getElementById('browser-open-listing')?.addEventListener('click',()=>job&&openJobOnOriginalSite(job));
 document.getElementById('browser-review-open')?.addEventListener('click',()=>job&&openJobOnOriginalSite(job));
 document.getElementById('browser-answer-memory-settings')?.addEventListener('click',()=>go('settings'));
 document.getElementById('application-resume')?.addEventListener('change',event=>{
  state.autoApply.selectedResume=event.target.value;
  localStorage.setItem('menix-selected-resume',event.target.value);
  setApplicationAssistStatus(event.target.value?'Resume selected for an optional native upload. Detect the current form before autofilling.':'Resume selection cleared; profile-based autofill remains available.');
 });
 document.getElementById('application-inspect')?.addEventListener('click',inspectAutoApplyForm);
 document.getElementById('application-autofill')?.addEventListener('click',fillAutoApplyForm);
 document.getElementById('application-suggest-missing')?.addEventListener('click',suggestMissingAnswers);
 document.getElementById('application-clear-suggestions')?.addEventListener('click',()=>clearAutoApplySuggestions(activeApplicationTab()));
 document.getElementById('application-view-memory')?.addEventListener('click',()=>go('settings'));
 document.getElementById('application-review-button')?.addEventListener('click',reviewAutoApplyForm);
 document.getElementById('application-save-draft')?.addEventListener('click',saveAutoApplyDraft);
 document.getElementById('application-upload-resume')?.addEventListener('click',uploadAutoApplyResume);
 document.getElementById('application-submit-button')?.addEventListener('click',submitAutoApplyForm);
 if(job&&state.browserAssistant.selectedTab==='ai'&&!matchData)loadBrowserAssistantMatch(job.id);
}

async function loadBrowserAssistantMatch(jobId){
 try{
  state.browserAssistant.match=await api(`/jobs/${jobId}/match`);
  if(state.page==='browser'&&state.browserAssistant.selectedTab==='ai')renderBrowserDetails();
 }catch(error){toast(`Could not calculate job match: ${error.message}`);}
}

async function prepareBrowserAssistantJob(job){
 if(!job){toast('Save a selected job before preparing an application.');return;}
 if(!job.applicationUrlVerified){openImportedJobListing(job);toast('The application destination is not verified. Review the original listing and open Apply there; then confirm the destination in AutoApply.');return;}
 await startBrowserAssistantApplication(job);
}

async function startBrowserAssistantApplication(job, applicationRecord=null){
 const url=job?.applyUrl||job?.url;
 if(!job||!url){toast('This saved job does not have an application URL.');return false;}
 if(!job.applicationUrlVerified){openImportedJobListing(job);toast('The application destination is unverified. Menix opened the original job listing instead; use Apply there yourself.');return false;}
 let applicationUrl;
 try{applicationUrl=validateBrowserUrl(url);}catch(error){toast(error.message);return false;}
 state.browserAssistant.sessionConsent=true;
 state.jobs=[job,...state.jobs.filter(item=>Number(item.id)!==Number(job.id))];
 const existing=state.applications.find(item=>Number(item.jobId)===Number(job.id));
 if(existing&&!['SHORTLISTED','DISCOVERED','WITHDRAWN','NOT_COMPLETED_YET'].includes(String(existing.status||'').toUpperCase())&&existing.id!==applicationRecord?.id){
  if(existing.status==='SUBMITTED')toast('This application is already confirmed as submitted.');
  else toast('This job already has an application record. Update it in Applications to avoid creating a duplicate.');
  return false;
 }
 let record=applicationRecord;
 try{
  if(!record)record=existing||await api(`/jobs/${job.id}/apply`,{method:'POST'});
  if(record.status==='SUBMITTED'){toast('This application is already confirmed as submitted.');return false;}
  state.applications=[record,...state.applications.filter(item=>item.id!==record.id)];
 }catch(error){toast(`Could not start this application: ${error.message}`);return false;}
 let sessionId=state.browserAssistant.autoApplyTab?.applicationId===record.id?state.browserAssistant.autoApplyTab.sessionId:null;
 if(!sessionId){
  try{
   const sessions=await api('/autofill/sessions');
   sessionId=sessions.find(session=>Number(session.applicationId)===Number(record.id))?.id||null;
  }catch(error){toast(`Existing AutoApply session could not be checked: ${error.message}`);}
 }
 if(!sessionId){
  try{
   const session=await api('/autofill/sessions',{method:'POST',body:JSON.stringify({jobId:Number(job.id),applicationId:record.id})});
   sessionId=session.id;
  }catch(error){toast(`Application started, but AutoApply session tracking is unavailable: ${error.message}`);}
 }
 state.browserAssistant.savedJobId=Number(job.id);
 state.browserAssistant.selectedTab='prepare';
 state.browserAssistant.url=applicationUrl;
 state.browserAssistant.hostname=new URL(applicationUrl).hostname;
 state.browserAssistant.autoApplyTab={
  id:`browser-application-${record.id}`,label:`${job.company||'Company'} — ${job.title}`,url:applicationUrl,
  view:null,jobId:Number(job.id),applicationId:record.id,sessionId,company:job.company||'the employer',
  status:'Ready',fields:[],uploads:[],submitCandidates:[],reviewed:false,fieldsFilled:0
 };
 state.browserAssistant.applicationInspectionEnabled=true;
 persistAutoApplyContext(state.browserAssistant.autoApplyTab);
 state.autoApply.status='Ready';
 if(state.page!=='browser'){
  state.page='browser';
  window.history.pushState({page:'browser',selectedJob:state.selectedJob,detailReturnPage:'browser'},'');
  updateNavigation('browser');
 }
 renderBrowserAssistant();
 if(!state.autoApply.resumeOptions.length){
  try{state.autoApply.resumeOptions=await api('/resumes');}
  catch(error){toast(`Saved resumes could not load: ${error.message}`);}
  renderBrowserDetails();
 }
 setAutoApplyStatus(state.browserAssistant.autoApplyTab,'Ready','Application opened in AutoApply. The form will be scanned when it loads; use Smart Autofill when you are ready.');
 return true;
}

async function openJobOnOriginalSite(job){
 const url=job.applyUrl||job.url;
 if(!url){toast('This saved job does not have an original HTTPS URL.');return;}
 try{
  navigateBrowserView(url);
 }catch(error){toast(`Could not open original listing: ${error.message}`);}
}

function openImportedJobListing(job){
 const url=validateBrowserUrl(job.url);
 const assistant=state.browserAssistant;
 assistant.savedJobId=Number(job.id);
 assistant.selectedTab='details';
 assistant.sessionConsent=true;
 assistant.applicationInspectionEnabled=false;
 assistant.url=url;
 assistant.hostname=new URL(url).hostname;
 assistant.status='Opening Naukri listing';
 if(assistant.autoApplyTab)assistant.autoApplyTab.view=null;
 if(state.page!=='browser'){
  assistant.suppressAutoApplyRestore=true;
  go('browser');
  return;
 }
 renderBrowserAssistant();
}

async function saveBrowserAssistantJob(event) {
 event.preventDefault();
 const form=event.currentTarget;
 const data=Object.fromEntries(new FormData(form));
 try{data.url=validateBrowserUrl(data.url);}catch(error){toast(error.message);return;}
 data.source=`${state.browserAssistant.portal} (manual)`;
 try{
  const job=await api('/jobs',{method:'POST',body:JSON.stringify(data)});
  state.jobs=[job,...state.jobs.filter(item=>Number(item.id)!==Number(job.id))];
  state.browserAssistant.savedJobId=job.id;
  state.browserAssistant.match=null;
  state.browserAssistant.selectedTab='details';
  toast('Selected job saved. Review the details before preparing an application.');
  renderBrowserDetails();
  if(state.browserAssistant.url)syncBrowserViewLocation();
 }catch(error){toast(error.message);}
}

function renderDashboard() {
 const savedAvatar=localStorage.getItem(accountAvatarStorageKey);
 const dashboardThreshold=Math.max(0,Math.min(100,state.minScore));
 const recommendations=state.jobs
  .filter(job=>!state.query||`${job.title} ${job.company} ${job.location} ${job.workMode} ${job.requiredSkills}`.toLowerCase().includes(state.query.toLowerCase()))
  .filter(job=>!state.location||`${job.location||''} ${job.workMode||''}`.toLowerCase().includes(state.location.toLowerCase()))
  .filter(stageAllows)
  .map(job=>({...job,matchScore:Number(job.score??scoreLocal(job))}))
  .sort((a,b)=>(Date.parse(b.postedAt||b.publishedAt||'')||0)-(Date.parse(a.postedAt||a.publishedAt||'')||0)||b.matchScore-a.matchScore)
  .slice(0,6);
 const noSourcesConfigured=!state.jobDiagnostics?.sourcesConfigured;
 const emptyJobsMessage=noSourcesConfigured?'No sources configured. Add sources in Sources.':'No jobs match the current dashboard filters.';
 const savedCount=state.applications.filter(application=>String(application.status||'').toUpperCase()==='SHORTLISTED').length;
 const tracked=state.applications.filter(application=>!['SHORTLISTED','DISCOVERED','NOT_COMPLETED_YET'].includes(String(application.status||'').toUpperCase()));
 const activeCount=tracked.filter(application=>!['DECLINED','REJECTED','WITHDRAWN','SUBMISSION_FAILED'].includes(String(application.status||'').toUpperCase())).length;
 const groups=[
  {label:'Applied',color:'#2678ff',statuses:['SUBMITTED','APPLIED']},
  {label:'Under Review',color:'#823df0',statuses:['UNDER_REVIEW','IN_REVIEW','APPLICATION_RECEIVED','NEEDS_ATTENTION','APPLYING','DRAFT','AWAITING_APPROVAL','READY_TO_SUBMIT']},
  {label:'Interview',color:'#f5b632',statuses:['INTERVIEW','INTERVIEWING']},
  {label:'Offer',color:'#08bd83',statuses:['OFFER','OFFER_RECEIVED']},
  {label:'Rejected / Closed',color:'#fa3f58',statuses:['REJECTED','DECLINED','WITHDRAWN','SUBMISSION_FAILED','FAILED','CLOSED']}
 ].map(group=>({...group,count:tracked.filter(application=>group.statuses.includes(String(application.status||'').toUpperCase())).length}));
 const chartTotal=groups.reduce((sum,group)=>sum+group.count,0);
 let offset=0;
 const circumference=2*Math.PI*39;
 const chartSegments=groups.map(group=>{
  const length=chartTotal?circumference*group.count/chartTotal:0;
  const segment=`<circle class="progress-segment" cx="50" cy="50" r="39" fill="none" stroke="${group.color}" stroke-width="11" stroke-dasharray="${length} ${circumference-length}" stroke-dashoffset="${-offset}"><title>${esc(group.label)}: ${group.count}</title></circle>`;
  offset+=length;
  return segment;
 }).join('');
 const sourceRows=dashboardSourceRows(state.sources);
 content.innerHTML=`
  <div class="dashboard-searchbar">
   <form id="dashboard-search-form" class="dashboard-search-form" role="search">
    <label class="dashboard-search-field"><span aria-hidden="true">⌕</span><input id="dashboard-query" aria-label="Filter saved jobs" placeholder="Filter saved jobs by title, skill, company…" value="${esc(state.query)}"></label>
    <label class="dashboard-search-field location-field"><span aria-hidden="true">⌖</span><input id="dashboard-location" aria-label="Job location" placeholder="Remote, On-site, Hybrid or city" value="${esc(state.location)}"></label>
    <button class="button button-primary" type="submit">Filter</button>
   </form>
   <div class="dashboard-controls">
    <label class="dashboard-freshness-control">Date range <select id="dashboard-freshness"><option value="24h" ${state.dashboardFreshness==='24h'?'selected':''}>Last 24 hours</option><option value="7d" ${state.dashboardFreshness==='7d'?'selected':''}>Last 7 days</option><option value="older" ${state.dashboardFreshness==='older'?'selected':''}>Older than 7 days</option><option value="all" ${state.dashboardFreshness==='all'?'selected':''}>All posting dates</option></select></label>
    <button class="button button-primary dashboard-sync-button" id="dashboard-sync-jobs" type="button">Search Naukri</button>
    <button class="icon-button dashboard-notifications" id="dashboard-notifications" title="Notifications" aria-label="Notifications"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"></path></svg></button>
    <button class="icon-button dashboard-theme" id="dashboard-theme" title="Toggle light theme" aria-label="Toggle light theme">☾</button>
    <div class="profile-menu-wrap"><button class="dashboard-profile" id="dashboard-profile" aria-label="Open account menu" aria-expanded="false"><span id="dashboard-avatar-fallback" ${savedAvatar?'hidden':''}>${esc(initials(state.profile?.fullName||'A').slice(0,1))}</span><img id="dashboard-avatar-image" class="dashboard-avatar-image" src="${savedAvatar?esc(savedAvatar):''}" alt="" ${savedAvatar?'':'hidden'}><b aria-hidden="true">⌄</b></button><div class="profile-popover" id="profile-popover" hidden><strong>${esc(state.profile?.fullName||'Candidate')}</strong><button data-go="profile">Resume &amp; Profile</button><button data-go="settings">Settings</button></div></div>
   </div>
  </div>
  <div class="grid dashboard-stats">
   <article class="card kpi-card kpi-jobs"><div class="kpi-icon" aria-hidden="true"><svg viewBox="0 0 48 48"><path d="M8 17h32v23H8zM18 17v-4a3 3 0 0 1 3-3h6a3 3 0 0 1 3 3v4M8 26h32M20 26v4h8v-4"></path></svg></div><div class="kpi-content"><span class="stat-label">Eligible Jobs</span><strong class="stat-value">${Number(state.stats.totalJobs||0)}</strong><span class="stat-foot">${esc(state.dashboardFreshness==='older'?'Older than 7 days':state.dashboardFreshness==='all'?'All posting dates':state.dashboardFreshness==='24h'?'Last 24 hours':'Last 7 days')} · ${Number(state.stats.newIn24Hours||0)} posted in 24h</span></div><svg class="kpi-trend trend-blue" viewBox="0 0 96 44" aria-hidden="true"><path d="M3 37c14-2 17-20 29-18s14 13 26 8 18-20 35-24"></path><circle cx="93" cy="3" r="3"></circle></svg></article>
   <article class="card kpi-card kpi-matches"><div class="kpi-icon" aria-hidden="true"><svg viewBox="0 0 48 48"><circle cx="22" cy="26" r="15"></circle><circle cx="22" cy="26" r="8"></circle><path d="M22 26l19-19M33 7h8v8M31 7v7h7"></path></svg></div><div class="kpi-content"><span class="stat-label">Strong Matches</span><strong class="stat-value">${Number(state.stats.strongMatches||0)}</strong><span class="stat-foot">${Number(state.stats.strongMatches||0)===0?'No matches meet the selected threshold':`${dashboardThreshold}%+ of eligible jobs`}</span></div><svg class="kpi-trend trend-green" viewBox="0 0 96 44" aria-hidden="true"><path d="M3 37c14-2 17-20 29-18s14 13 26 8 18-20 35-24"></path><circle cx="93" cy="3" r="3"></circle></svg></article>
   <article class="card kpi-card kpi-saved"><div class="kpi-icon" aria-hidden="true"><svg viewBox="0 0 48 48"><path d="M12 8h24v33L24 32 12 41z"></path></svg></div><div class="kpi-content"><span class="stat-label">Saved Jobs</span><strong class="stat-value">${savedCount}</strong><span class="stat-foot">In your shortlist</span></div><svg class="kpi-trend trend-purple" viewBox="0 0 96 44" aria-hidden="true"><path d="M3 37c14-2 17-20 29-18s14 13 26 8 18-20 35-24"></path><circle cx="93" cy="3" r="3"></circle></svg></article>
   <article class="card kpi-card kpi-applications"><div class="kpi-icon" aria-hidden="true"><svg viewBox="0 0 48 48"><path d="M13 7h16l8 8v26H13zM29 7v9h8M19 24h12M19 30h12M19 36h8"></path></svg></div><div class="kpi-content"><span class="stat-label">Applications</span><strong class="stat-value">${tracked.length}</strong><span class="stat-foot">${activeCount} active</span></div><svg class="kpi-trend trend-orange" viewBox="0 0 96 44" aria-hidden="true"><path d="M3 37c14-2 17-20 29-18s14 13 26 8 18-20 35-24"></path><circle cx="93" cy="3" r="3"></circle></svg></article>
  </div>
  <div class="dashboard-workspace">
   <section class="dashboard-center">
    <div class="dashboard-section-title"><div><h2>Job recommendations</h2><p>Jobs are shown when they match your filters and freshness settings.</p></div><button class="text-button" data-go="jobs">View all jobs&nbsp; →</button></div>
    ${recommendations.length?`<div class="job-grid dashboard-job-grid">${recommendations.map(jobRow).join('')}</div>`:`<div class="card dashboard-empty"><strong>${esc(emptyJobsMessage)}</strong>${noSourcesConfigured?'<p>Add an employer board or saved source to begin.</p><button class="button button-primary" data-go="sources">Open Sources</button>':'<p>Review these counts to see which dashboard conditions excluded jobs.</p>'}${noSourcesConfigured?'':dashboardDiagnosticsMarkup(state.jobDiagnostics)}<button class="button button-primary" id="dashboard-open-naukri">Search Naukri</button></div>`}
   </section>
   <aside class="dashboard-right" aria-label="Dashboard summary">
     <section class="card autoapply-promo">
      <div class="panel-heading"><div><h2>AutoApply workspace</h2><p>Review a saved job and prepare its official form.</p></div></div>
      <button class="button button-primary" data-go="browser">Open AutoApply&nbsp; →</button>
     </section>
     <section class="card application-mini">
     <div class="panel-heading"><h2>Application Progress</h2><button class="text-button" data-go="applications">View all&nbsp; →</button></div>
     ${tracked.length?`<div class="donut-layout"><div class="donut-chart" role="img" aria-label="Application status chart, ${tracked.length} tracked applications"><svg viewBox="0 0 100 100" aria-hidden="true"><circle class="donut-track" cx="50" cy="50" r="39"></circle>${chartSegments}</svg><div class="donut-center"><strong>${tracked.length}</strong><span>Total</span></div></div><div class="donut-legend">${groups.map((group,index)=>`<div class="donut-legend-row"><span class="legend-dot legend-${index}"></span><span>${esc(group.label)}</span><strong>${group.count}</strong></div>`).join('')}</div></div><p class="chart-note">${chartTotal===0?'Progress categories appear as you update tracked application statuses.':''}</p>`:`<div class="chart-empty"><div class="donut-chart donut-zero"><svg viewBox="0 0 100 100" aria-hidden="true"><circle class="donut-track" cx="50" cy="50" r="39"></circle></svg><div class="donut-center"><strong>0</strong><span>Total</span></div></div><p>Application progress will appear after you track applications.</p></div>`}
    </section>
    <section class="card source-mini">
    <div class="panel-heading"><div><h2>Job discovery</h2><p>Manual browsing only</p></div><button class="text-button" data-go="sources">Manage&nbsp; →</button></div>
     ${sourceRows||'<div class="empty compact-empty">No source status is available.</div>'}
    </section>
   </aside>
  </div>`;
 bindCommon();
 document.getElementById('dashboard-sync-jobs')?.addEventListener('click',()=>startNaukriSearch().catch(error=>toast(`Could not search Naukri: ${error.message}`)));
 document.getElementById('dashboard-open-naukri')?.addEventListener('click',()=>startNaukriSearch().catch(error=>toast(`Could not search Naukri: ${error.message}`)));
 document.getElementById('dashboard-freshness')?.addEventListener('change',async event=>{state.dashboardFreshness=event.target.value;localStorage.setItem('menix-dashboard-freshness',state.dashboardFreshness);await render().catch(showError);});
 document.getElementById('dashboard-search-form')?.addEventListener('submit',event=>{
  event.preventDefault();
  state.query=document.getElementById('dashboard-query').value.trim();
  state.location=document.getElementById('dashboard-location').value.trim();
  localStorage.setItem('menix-job-filter-query',state.query);
  localStorage.setItem('menix-job-filter-location',state.location);
  render().catch(showError);
 });
 const profileMenuButton=document.getElementById('dashboard-profile');
 const profilePopover=document.getElementById('profile-popover');
 profileMenuButton?.addEventListener('click',()=>{
  profilePopover.hidden=!profilePopover.hidden;
  profileMenuButton.setAttribute('aria-expanded',String(!profilePopover.hidden));
 });
 document.getElementById('dashboard-notifications')?.addEventListener('click',()=>toast('No notifications are available.'));
 const themeButton=document.getElementById('dashboard-theme');
 if(themeButton){
  const lightTheme=localStorage.getItem('menix-theme')==='light';
  document.body.classList.toggle('light-theme',lightTheme);
  themeButton.textContent=lightTheme?'☼':'☾';
  themeButton.title=lightTheme?'Switch to dark theme':'Switch to light theme';
  themeButton.addEventListener('click',()=>{
   const nextLight=!document.body.classList.contains('light-theme');
   document.body.classList.toggle('light-theme',nextLight);
   localStorage.setItem('menix-theme',nextLight?'light':'dark');
   themeButton.textContent=nextLight?'☼':'☾';
   themeButton.title=nextLight?'Switch to dark theme':'Switch to light theme';
  });
 }
 function dashboardSourceRows(sources=[]){
  const groups=[
   {name:'Company boards',members:['Greenhouse','Lever','Ashby','Workable','SmartRecruiters']},
   {name:'Adzuna',members:['Adzuna']},
   {name:'Email alerts',members:['Email alerts']},
   {name:'Career pages',members:['Career pages']},
   {name:'Manual',members:['Manual']}
  ];
  return groups.map(group=>{
   const rows=group.members.map(name=>sources.find(item=>item.source===name)).filter(Boolean);
   if(!rows.length)return '';
   const count=rows.reduce((sum,row)=>sum+Number(row.jobCount||0),0);
   const lastSuccess=rows.map(row=>row.lastSuccessfulSync||'').filter(Boolean).sort().pop();
   const errors=rows.map(row=>row.error||row.errors||'').flatMap(value=>Array.isArray(value)?value:[value]).filter(Boolean);
   const status=errors.length?'Needs attention':rows.some(row=>row.enabled)?'Enabled':rows.some(row=>row.status==='User saved')?'User saved':'Off';
   return `<div class="mini-source-row"><span class="source-indicator ${errors.length?'source-warn':rows.some(row=>row.enabled)?'source-good':''}"></span><div><strong>${esc(group.name)}</strong><small>${count} job${count===1?'':'s'} · Last success ${esc(lastSuccess||'Never')}${errors.length?` · ${esc(errors[0])}`:''}</small></div><span class="source-state ${errors.length?'state-warn':status==='Enabled'?'state-good':''}">${esc(status)}</span></div>`;
  }).join('');
 }
}

function adzunaSearchProfilesMarkup(profiles){
 const rows=profiles.map(profile=>`<div class="form-grid adzuna-search-profile"><label class="field"><span>Keyword (what)</span><input name="what" value="${esc(profile.what)}" maxlength="200" required></label><label class="field"><span>Location (where)</span><input name="where" value="${esc(profile.where)}" maxlength="200" required></label><button type="button" class="button button-danger" data-remove-adzuna-profile aria-label="Remove search profile">Remove</button></div>`).join('');
 return `<section class="card settings-card" id="adzuna-search-settings"><h2>Adzuna search profiles</h2><p class="muted small">Each saved profile runs once per sync, with up to 3 pages of 50 results. API credentials stay in backend environment variables. Adzuna is skipped when its credentials are missing.</p><form id="adzuna-search-profiles-form"><div id="adzuna-search-profile-rows">${rows}</div><div class="actions"><button type="button" class="button" id="add-adzuna-profile">Add profile</button><button type="submit" class="button button-primary">Save Adzuna profiles</button></div><div id="adzuna-profile-result" class="muted small" aria-live="polite"></div></form></section>`;
}

function bindAdzunaSearchProfiles(){
 const form=document.getElementById('adzuna-search-profiles-form');
 const rows=document.getElementById('adzuna-search-profile-rows');
 document.getElementById('add-adzuna-profile').onclick=()=>rows.insertAdjacentHTML('beforeend',`<div class="form-grid adzuna-search-profile"><label class="field"><span>Keyword (what)</span><input name="what" maxlength="200" required></label><label class="field"><span>Location (where)</span><input name="where" maxlength="200" required></label><button type="button" class="button button-danger" data-remove-adzuna-profile aria-label="Remove search profile">Remove</button></div>`);
 rows.onclick=event=>{const button=event.target.closest('[data-remove-adzuna-profile]');if(button)button.closest('.adzuna-search-profile').remove();};
 form.onsubmit=async event=>{
  event.preventDefault();
  const output=document.getElementById('adzuna-profile-result');
  const profiles=[...rows.querySelectorAll('.adzuna-search-profile')].map(row=>({what:row.querySelector('[name="what"]').value,where:row.querySelector('[name="where"]').value}));
  if(!profiles.length){output.textContent='Keep at least one search profile.';return;}
  output.textContent='Saving profiles…';
  try{
   const saved=await api('/settings/adzuna-search-profiles',{method:'PUT',body:JSON.stringify({profiles})});
   output.textContent=`Saved ${saved.length} search profile(s).`;
   toast('Adzuna search profiles saved.');
  }catch(error){output.textContent=`Could not save Adzuna profiles: ${error.message}`;}
 };
}

function emailAlertSettingsMarkup(settings){
 const providers=['Naukri','LinkedIn','Indeed','Glassdoor'];
 const allowlist=providers.map(provider=>`<label class="field"><span>${provider} allowed sender patterns</span><textarea name="allowlist-${provider}" rows="2" placeholder="One address or *@domain per line">${esc((settings.allowlist?.[provider]||[]).join('\n'))}</textarea></label>`).join('');
 return `<section class="card settings-card email-alert-settings"><h2>Email job-alert importer</h2><p class="notice">Menix will read only emails from the senders listed below, locally on this device.</p><p class="muted small">Creates alerts yourself on each job platform; Menix reads only matching alert emails and does not scrape job sites. The mailbox app password is encrypted locally before storage and is never returned by this API. IMAP access uses TLS.</p><form id="email-alert-settings-form"><label class="application-permission"><input name="enabled" type="checkbox" ${settings.enabled?'checked':''}><span>Enable email alert imports (off by default)</span></label><div class="form-grid"><label class="field"><span>IMAP host</span><input name="imapHost" value="${esc(settings.imapHost||'imap.gmail.com')}" required maxlength="255"></label><label class="field"><span>IMAP port</span><input name="imapPort" type="number" min="1" max="65535" value="${Number(settings.imapPort||993)}" required></label><label class="field"><span>Email address</span><input name="emailAddress" type="email" value="${esc(settings.emailAddress||'')}" required></label><label class="field"><span>Folder / label</span><input name="folderName" value="${esc(settings.folderName||'INBOX')}" required maxlength="255"></label><label class="field full"><span>App password ${settings.hasAppPassword?'(saved; leave blank to keep current)':''}</span><input name="appPassword" type="password" autocomplete="new-password" placeholder="${settings.passwordConfiguredByEnvironment?'Provided by environment':settings.hasAppPassword?'Saved locally; leave blank to keep it':'Enter mailbox app password'}" ${settings.passwordConfiguredByEnvironment?'disabled':''}></label></div><div class="form-grid">${allowlist}</div><label class="application-permission"><input name="markSeen" type="checkbox" ${settings.markSeen?'checked':''}><span>Mark imported alert emails as seen</span></label><div class="actions"><button class="button button-primary" type="submit">Save email settings</button><button class="button" type="button" id="test-email-alert-connection">Test connection</button></div><div id="email-alert-settings-result" class="muted small" aria-live="polite"></div> <div class="source-sync">Last run: ${esc(settings.lastRunAt||'Never')} · Last successful run: ${esc(settings.lastSuccessfulSyncAt||'Never')} · Last imported: ${Number(settings.lastImported||0)}</div>${settings.lastError?`<p class="source-error">${esc(settings.lastError)}</p>`:''}</form><div class="email-alert-help"><h3>Create alert emails</h3><ul><li><a href="https://www.naukri.com/mnjuser/alerts" target="_blank" rel="noreferrer">Naukri</a>: create or edit a job alert in your Naukri account and choose email delivery.</li><li><a href="https://www.linkedin.com/jobs/" target="_blank" rel="noreferrer">LinkedIn</a>: create a job alert from a jobs search page and choose email notifications.</li><li><a href="https://www.indeed.com/" target="_blank" rel="noreferrer">Indeed</a>: run a job search, create an alert and confirm email delivery.</li><li><a href="https://www.glassdoor.com/Job/" target="_blank" rel="noreferrer">Glassdoor</a>: create a job alert and enable email notifications.</li></ul><h3>Gmail app password</h3><ol><li>Enable 2-Step Verification for your Google account.</li><li>Open <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noreferrer">Google App Passwords</a>, create an app password, and paste it above (spaces are removed for IMAP login).</li><li>Confirm IMAP is enabled in Gmail settings. Use the allowlist below to match actual alert sender addresses; defaults are provided as a starting point.</li></ol><p class="muted small">Test connection checks IMAP login/folder access and counts allowlisted messages only; it does not import messages or mark them as seen.</p></div></section>`;
}

function bindEmailAlertSettings(){
 const form=document.getElementById('email-alert-settings-form');
 form.onsubmit=async event=>{
  event.preventDefault();
  const output=document.getElementById('email-alert-settings-result');
  const data=Object.fromEntries(new FormData(form));
  data.enabled=form.elements.enabled.checked;
  data.markSeen=form.elements.markSeen.checked;
  data.imapPort=Number(data.imapPort);
  data.allowlist=Object.fromEntries(['Naukri','LinkedIn','Indeed','Glassdoor'].map(provider=>[
   provider,String(data[`allowlist-${provider}`]||'').split(/\r?\n|,/).map(value=>value.trim()).filter(Boolean)
  ]));
  delete data['allowlist-Naukri'];delete data['allowlist-LinkedIn'];delete data['allowlist-Indeed'];delete data['allowlist-Glassdoor'];
  output.textContent='Saving mailbox settings locally…';
  try{await api('/settings/email-alerts',{method:'PUT',body:JSON.stringify(data)});output.textContent='Settings saved. Test the connection before enabling imports.';toast('Email alert settings saved.');await renderSettings();}
  catch(error){output.textContent=`Could not save email settings: ${error.message}`;}
 };
 document.getElementById('test-email-alert-connection').onclick=async event=>{
  const button=event.currentTarget;const output=document.getElementById('email-alert-settings-result');
  button.disabled=true;output.textContent='Checking IMAP login and folder…';
  try{const result=await api('/settings/email-alerts/test',{method:'POST'});output.textContent=result.ok?`Connection successful. ${Number(result.matchingMessages||0)} matching message(s) in ${result.folder}. Nothing was imported.`:`Connection failed: ${result.message}`;}
  catch(error){output.textContent=`Connection test failed: ${error.message}`;}
  finally{button.disabled=false;}
 };
}

function dailySyncSettingsMarkup(settings){
 const sources=[
  ['companyBoardsEnabled','Company boards'],['adzunaEnabled','Adzuna'],
  ['emailAlertsEnabled','Email alerts'],['careerPagesEnabled','Career pages']
 ];
 return `<section class="card settings-card" id="daily-sync-settings"><div class="section-head"><div><h2>Daily automatic sync</h2><p class="muted small">Choose the local time and sources Menix should check. The backend must be running for scheduled syncs.</p></div><span class="tag">${settings.lastRunAt?`Last run ${esc(new Date(settings.lastRunAt).toLocaleString())}`:'Not run yet'}</span></div><form id="daily-sync-form"><label class="application-permission"><input name="enabled" type="checkbox" ${settings.enabled?'checked':''}><span>Enable daily automatic sync</span></label><div class="form-grid"><label class="field"><span>Daily sync time (device local time)</span><input name="syncTime" type="time" required value="${esc(settings.syncTime||'08:15')}"></label></div><label class="application-permission"><input name="runOnOpen" type="checkbox" ${settings.runOnOpen?'checked':''}><span>Run enabled sources when Menix opens</span></label><fieldset class="daily-sync-source-options"><legend>Sources included in automatic sync</legend>${sources.map(([key,label])=>`<label class="application-permission"><input name="${key}" type="checkbox" ${settings[key]?'checked':''}><span>${label}</span></label>`).join('')}<p class="muted small">Manual jobs are user-saved and are never synchronized automatically. Email importing also requires its own enabled setting in Email job-alert importer.</p></fieldset><div class="actions"><button class="button button-primary" type="submit">Save sync settings</button></div><div id="daily-sync-result" class="muted small" aria-live="polite"></div>${settings.lastError?`<p class="source-error"><b>Last sync error</b><br>${esc(settings.lastError)}</p>`:''}</form></section>`;
}
function bindDailySyncSettings(){
 const form=document.getElementById('daily-sync-form');
 form.onsubmit=async event=>{
  event.preventDefault();
  const data=Object.fromEntries(new FormData(form));
  for(const name of ['enabled','runOnOpen','companyBoardsEnabled','adzunaEnabled','emailAlertsEnabled','careerPagesEnabled'])data[name]=form.elements[name].checked;
  const output=document.getElementById('daily-sync-result');
  output.textContent='Saving automatic sync settings…';
  try{
   await api('/settings/automatic-sync',{method:'PUT',body:JSON.stringify(data)});
   output.textContent='Settings saved. Daily sync runs at the selected time while the backend is available.';
   toast('Automatic sync settings saved.');
  }catch(error){output.textContent=`Could not save sync settings: ${error.message}`;}
 };
}
async function runConfiguredSourcesOnOpen(){
 if(state.automaticSyncStarted)return;
 state.automaticSyncStarted=true;
 try{
  const settings=await api('/settings/automatic-sync');
  if(!settings.runOnOpen)return;
  const result=await api('/jobs/sync/automatic',{method:'POST'});
  if(result.skipped)return;
  const errors=result.errors?.length?` Errors: ${result.errors.join('; ')}`:'';
  toast(`App-open sync: ${Number(result.imported||0)} imported, ${Number(result.updated||0)} updated.${errors}`);
  await render();
 }catch(error){toast(`App-open job sync failed: ${error.message}`);}
}

function renderSaved(){
 const saved=state.applications.filter(application=>application.status==='SHORTLISTED');
 const jobs=saved.map(application=>state.jobs.find(job=>Number(job.id)===Number(application.jobId))).filter(Boolean).map(job=>({...job,matchScore:Number(job.score??scoreLocal(job))}));
 content.innerHTML=`<div class="section-head"><div><h2>Saved jobs</h2><p class="muted small">Your shortlist is stored with your application records.</p></div><button class="button" data-go="jobs">Discover jobs</button></div>${jobs.length?`<div class="job-grid saved-job-grid">${jobs.map(jobRow).join('')}</div>`:'<div class="card empty">No saved jobs yet. Use the bookmark on a job card to add it to your shortlist.</div>'}`;
 bindCommon();
}

async function renderSettings(){
 const [answers,preferences,adzunaProfiles,emailAlertSettings,careerPages,dailySyncSettings]=await Promise.all([api('/smart-apply/answers'),api('/smart-apply/preferences'),api('/settings/adzuna-search-profiles'),api('/settings/email-alerts'),api('/settings/career-pages'),api('/settings/automatic-sync')]);
 state.smartApplyAnswers=answers;
 state.smartApplyPreferences=preferences;
 const profile=state.profile||{};
 const textField=(name,label,value,placeholder='')=>`<label class="field"><span>${label}</span><input name="${name}" value="${esc(value||'')}" placeholder="${esc(placeholder)}"></label>`;
 const longField=(name,label,value)=>`<label class="field full"><span>${label}</span><textarea name="${name}" rows="3">${esc(value||'')}</textarea></label>`;
 const answerRows=answers.map(item=>`<form class="smart-answer-row" data-memory-id="${item.id}"><label class="field"><span>Question</span><input name="question" value="${esc(item.question)}" required></label><label class="field full"><span>Approved answer</span><textarea name="answer" rows="3" required>${esc(item.answer)}</textarea></label><label class="field"><span>Category</span><input name="category" value="${esc(item.category)}"></label><div class="memory-meta">Source: ${esc(item.source)} · Approved ${esc(item.approvedAt?new Date(item.approvedAt).toLocaleDateString():'date unavailable')} · Used ${Number(item.usageCount||0)} time(s) · Corrections ${Number(item.correctionCount||0)}</div><label class="application-permission"><input name="excluded" type="checkbox" ${item.excluded?'checked':''}><span>Exclude this answer from matching</span></label><div class="actions"><button class="button button-primary" type="submit">Save answer</button><button class="button button-danger" type="button" data-delete-memory="${item.id}">Delete</button></div></form>`).join('');
 content.innerHTML=`<div class="card settings-card"><h2>Candidate Smart Apply Memory</h2><p class="muted small">Facts here are user-provided and stored in your local Menix database. Legal, work authorization and salary answers are never auto-filled; you must explicitly confirm sensitive answers.</p><form id="smart-profile-form"><div class="form-grid">${textField('fullName','Candidate name',profile.fullName)}${textField('email','Email',profile.email)}${textField('phone','Phone',profile.phone)}${textField('currentLocation','Current location',profile.currentLocation)}${textField('degree','Degree / qualification',profile.degree)}${textField('graduationYear','Graduation year',profile.graduationYear)}${textField('linkedinUrl','LinkedIn URL',profile.linkedinUrl)}${textField('githubUrl','GitHub URL',profile.githubUrl)}${textField('portfolioUrl','Portfolio URL',profile.portfolioUrl)}${textField('noticePeriod','Notice period',profile.noticePeriod)}${textField('minimumSalary','Salary expectation (optional)',profile.minimumSalary)}${textField('workAuthorization','Work authorization answer (explicitly supplied)',profile.workAuthorization)}${textField('sponsorshipAnswer','Sponsorship answer (explicitly supplied)',profile.sponsorshipAnswer)}${longField('education','Education',profile.education)}${longField('skills','Skills',profile.skills)}${longField('experience','Work experience / internships',profile.experience)}${longField('projects','Projects',profile.projects)}</div><div class="actions"><button class="button button-primary" type="submit">Save candidate profile</button></div></form></div><div class="card settings-card"><h2>Answer reuse preferences</h2><p class="muted small">Approved answer learning uses deterministic question matching only; no model is trained. Automatic reuse is off by default.</p><form id="smart-preferences-form"><label class="application-permission"><input name="automaticReuseApprovedAnswers" type="checkbox" ${preferences.automaticReuseApprovedAnswers?'checked':''}><span>Automatically reuse high-confidence, previously approved answers on non-sensitive fields</span></label><label class="application-permission"><input name="learningEnabled" type="checkbox" ${preferences.learningEnabled?'checked':''}><span>Allow saving answers I explicitly approve</span></label><div class="form-grid"><label class="field"><span>High-confidence threshold (0.50–1.00)</span><input name="highConfidenceThreshold" type="number" min="0.5" max="1" step="0.01" value="${preferences.highConfidenceThreshold}"></label><label class="field"><span>Medium-confidence threshold (0.50–1.00)</span><input name="mediumConfidenceThreshold" type="number" min="0.5" max="1" step="0.01" value="${preferences.mediumConfidenceThreshold}"></label></div><div class="actions"><button class="button button-primary" type="submit">Save reuse settings</button></div></form></div><div class="card settings-card"><div class="section-head"><div><h2>Approved answer library</h2><p class="muted small">${answers.length} saved answer(s). Excluded entries stay visible here but are not matched.</p></div><button type="button" class="button button-danger" id="clear-answer-memory" ${answers.length?'':'disabled'}>Delete all answer history</button></div><form id="new-smart-answer" class="smart-answer-row"><label class="field"><span>Question</span><input name="question" required maxlength="1000" placeholder="What technologies have you used?"></label><label class="field full"><span>Your approved answer</span><textarea name="answer" rows="3" required maxlength="8000" placeholder="Enter only accurate information you approve for reuse"></textarea></label><label class="field"><span>Category</span><input name="category" value="General" maxlength="80"></label><div class="actions"><button class="button button-primary" type="submit">Save approved answer</button></div></form>${answerRows||'<div class="empty">No approved answers saved. Add a response you want to reuse, or approve an answer from the form review panel.</div>'}</div><div class="card settings-card"><h2>Job matching preferences</h2><p class="muted small">Strong-match counts use the backend scoring service. Set your minimum score for the Strong Matches dashboard metric.</p><label class="settings-range" for="settings-min-score"><span>Minimum match score</span><strong id="settings-score-value">${state.minScore}%</strong><input id="settings-min-score" type="range" min="50" max="100" step="5" value="${state.minScore}"></label><div class="actions"><button class="button button-primary" id="save-settings">Save preferences</button></div><p class="footer-note">Your threshold is stored locally on this device. The backend remains the source of job match scores.</p></div>`;
 content.insertAdjacentHTML('beforeend', dailySyncSettingsMarkup(dailySyncSettings));
 bindDailySyncSettings();
 content.insertAdjacentHTML('beforeend', adzunaSearchProfilesMarkup(adzunaProfiles));
 bindAdzunaSearchProfiles();
 content.insertAdjacentHTML('beforeend', emailAlertSettingsMarkup(emailAlertSettings));
 bindEmailAlertSettings();
 content.insertAdjacentHTML('beforeend', `<section class="card settings-card career-page-settings"><div class="section-head"><div><h2>Career pages</h2><p class="muted small">Import only schema.org JobPosting JSON-LD from public HTTPS career pages. Menix checks robots.txt, does not sign in or use cookies, and does not scrape page selectors.</p></div><button class="button" type="button" id="sync-career-pages">Sync now</button></div><form id="career-page-form"><label class="field"><span>Public career-page URL (HTTPS)</span><input name="url" type="url" placeholder="https://careers.example.com/jobs" required></label><div class="actions"><button class="button button-primary" type="submit">Add career page</button></div><div id="career-page-result" class="muted small" aria-live="polite"></div></form> <div class="career-page-list">${careerPages.length?careerPages.map(page=>`<article class="source-card"><div class="source-card-head"><div class="source-card-title"><strong>${esc(page.name||'Career page')}</strong><small>${esc(page.url)}</small></div><span class="tag ${page.status==='REACHABLE'?'good':page.status==='ERROR'||page.status==='DISALLOWED_ROBOTS'||page.status==='NO_STRUCTURED_DATA'?'warn':''}">${esc(page.status||'NOT_TESTED')}</span></div><div class="source-sync">Last sync: ${esc(page.lastSyncAt||'Never')} · Last success: ${esc(page.lastSuccessfulSync||'Never')} · ${Number(page.fetched||0)} found · ${Number(page.imported||0)} imported · ${Number(page.updated||0)} updated · ${Number(page.rejected||0)} rejected · ${Number(page.deduplicated||0)} duplicates</div>${page.lastError?`<p class="source-error">${esc(page.lastError)}</p>`:''}<button class="button button-danger" type="button" data-career-page-remove="${page.id}">Remove</button></article>`).join(''):'<div class="empty">No career pages configured yet.</div>'}</div><p class="muted small">Daily automatic sync; each configured page is requested at most once per run, with one second between requests. A source failure does not refresh job verification or close jobs.</p></section>`);
 document.getElementById('career-page-form').onsubmit=async event=>{
  event.preventDefault();
  const form=event.currentTarget;
  const output=document.getElementById('career-page-result');
  output.textContent='Validating and saving the public HTTPS page…';
  try{
   await api('/settings/career-pages',{method:'POST',body:JSON.stringify({url:new FormData(form).get('url')})});
   toast('Career page added.');
   await renderSettings();
  }catch(error){output.textContent=`Could not add career page: ${error.message}`;}
 };
 document.querySelectorAll('[data-career-page-remove]').forEach(button=>button.onclick=async()=>{
  if(!confirm('Remove this career-page source? Imported jobs and application history will remain.'))return;
  try{await api(`/settings/career-pages/${button.dataset.careerPageRemove}`,{method:'DELETE'});await renderSettings();}
  catch(error){toast(`Could not remove career page: ${error.message}`);}
 });
 document.getElementById('sync-career-pages').onclick=async event=>{
  const button=event.currentTarget;button.disabled=true;
  try{
   const result=await api('/jobs/sync/Career%20pages',{method:'POST'});
   const errors=Array.isArray(result.errors)?result.errors.length:0;
   toast(`Career pages sync finished: ${Number(result.imported||0)} imported, ${Number(result.updated||0)} updated${errors?`, ${errors} issue(s)`:''}.`);
   await renderSettings();
  }catch(error){document.getElementById('career-page-result').textContent=`Career pages sync failed: ${error.message}`;}
  finally{button.disabled=false;}
 };
 document.getElementById('settings-min-score').addEventListener('input',event=>{document.getElementById('settings-score-value').textContent=`${event.target.value}%`;});
 document.getElementById('save-settings').addEventListener('click',async()=>{
  state.minScore=Number(document.getElementById('settings-min-score').value);
  localStorage.setItem('menix-min-score',String(state.minScore));
  toast('Matching preferences saved');
  go('dashboard');
 });
 document.getElementById('smart-profile-form').addEventListener('submit',async event=>{
  event.preventDefault();
  try{state.profile=await api('/profile',{method:'PUT',body:JSON.stringify({...profile,...Object.fromEntries(new FormData(event.currentTarget))})});toast('Candidate profile saved.');}
  catch(error){toast(`Could not save candidate profile: ${error.message}`);}
 });
 document.getElementById('smart-preferences-form').addEventListener('submit',async event=>{
  event.preventDefault();
  const data=Object.fromEntries(new FormData(event.currentTarget));
  data.automaticReuseApprovedAnswers=event.currentTarget.elements.automaticReuseApprovedAnswers.checked;
  data.learningEnabled=event.currentTarget.elements.learningEnabled.checked;
  data.highConfidenceThreshold=Number(data.highConfidenceThreshold);
  data.mediumConfidenceThreshold=Number(data.mediumConfidenceThreshold);
  try{state.smartApplyPreferences=await api('/smart-apply/preferences',{method:'PUT',body:JSON.stringify(data)});toast('Smart Apply reuse settings saved.');}
  catch(error){toast(`Could not save Smart Apply settings: ${error.message}`);}
 });
 document.getElementById('new-smart-answer').addEventListener('submit',async event=>{
  event.preventDefault();
  try{await api('/smart-apply/answers',{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(event.currentTarget)))});toast('Approved answer saved.');await renderSettings();}
  catch(error){toast(`Could not save answer: ${error.message}`);}
 });
 document.querySelectorAll('.smart-answer-row[data-memory-id]').forEach(form=>form.addEventListener('submit',async event=>{
  event.preventDefault();
  const item=Object.fromEntries(new FormData(form));item.excluded=form.elements.excluded.checked;
  try{await api(`/smart-apply/answers/${form.dataset.memoryId}`,{method:'PUT',body:JSON.stringify(item)});toast('Approved answer updated.');await renderSettings();}
  catch(error){toast(`Could not update answer: ${error.message}`);}
 }));
 document.querySelectorAll('[data-delete-memory]').forEach(button=>button.addEventListener('click',async()=>{
  if(!window.confirm('Delete this approved answer from memory?'))return;
  try{await api(`/smart-apply/answers/${button.dataset.deleteMemory}`,{method:'DELETE'});toast('Saved answer deleted.');await renderSettings();}
  catch(error){toast(`Could not delete answer: ${error.message}`);}
 }));
 document.getElementById('clear-answer-memory').addEventListener('click',async()=>{
  if(!window.confirm('Permanently delete all approved answer history? Candidate profile facts will remain unchanged.'))return;
  try{await api('/smart-apply/answers',{method:'DELETE'});state.smartApplyAnswers=[];toast('Approved answer history deleted.');await renderSettings();}
  catch(error){toast(`Could not clear answer history: ${error.message}`);}
 });
}

async function toggleSavedJob(id,button){
 if(button)button.disabled=true;
 try{
  const existing=state.applications.find(application=>Number(application.jobId)===Number(id));
  if(existing?.status==='SHORTLISTED'){
   await api(`/applications/${existing.id}`,{method:'PUT',body:JSON.stringify({status:'DISCOVERED'})});
   toast('Removed from saved jobs');
  }else if(existing){
   await api(`/applications/${existing.id}`,{method:'PUT',body:JSON.stringify({status:'SHORTLISTED'})});
   toast('Job saved');
  }else{
   await api('/applications',{method:'POST',body:JSON.stringify({jobId:id,status:'SHORTLISTED'})});
   toast('Job saved');
  }
  await render();
 }catch(error){toast(`Could not update saved job: ${error.message}`);}
 finally{if(button)button.disabled=false;}
}

function companyBoardSettingsMarkup(companyBoards){
 const providerOptions=COMPANY_BOARD_PROVIDERS.map(provider=>`<option value="${provider.key}">${provider.name}</option>`).join('');
 const cards=companyBoards.length?companyBoards.map(board=>`<article class="company-board-card card"><div class="company-board-head"><div><strong>${esc(board.company)}</strong><small>${esc(board.provider)} · ${esc(board.identifier)}</small></div><span class="tag ${board.testStatus==='VERIFIED'?'good':'warn'}">ATS ${board.testStatus==='VERIFIED'?'verified':'unverified'}</span></div><div class="company-board-meta"><span>${board.enabled?'Enabled':'Paused'}</span><span>${board.lastTestAt?`Last test ${esc(new Date(board.lastTestAt).toLocaleString())}`:'Not tested'}</span>${board.lastTestJobCount!==null&&board.lastTestJobCount!==undefined?`<span>${Number(board.lastTestJobCount)} jobs found</span>`:''}</div>${board.lastError?`<p class="source-error">${esc(board.lastError)}</p>`:''}<div class="actions"><button class="button button-primary" data-board-test="${board.id}">Test board</button><button class="button" data-board-toggle="${board.id}" data-enabled="${board.enabled}" ${board.enabled||board.testStatus==='VERIFIED'?'':'disabled'}>${board.enabled?'Pause':'Enable'}</button><button class="button button-danger" data-board-delete="${board.id}">Remove</button></div></article>`).join(''):'<div class="card empty">No company boards configured yet. Add an identifier or test a suggestion below.</div>';
 const suggestions=COMPANY_BOARD_SUGGESTIONS.map((suggestion,index)=>{const verified=companyBoards.find(board=>board.identifier.toLowerCase()===suggestion.identifier.toLowerCase()&&board.company.toLowerCase()===suggestion.company.toLowerCase()&&board.testStatus==='VERIFIED');const provider=verified?.provider||suggestion.provider;return `<article class="company-board-suggestion"><div><strong>${esc(suggestion.company)}</strong><small>Proposed ID: ${esc(suggestion.identifier)}</small></div><label class="field"><span>Test candidate provider</span><select data-suggestion-provider="${index}" ${verified?.enabled?'disabled':''}>${COMPANY_BOARD_PROVIDERS.map(item=>`<option value="${item.key}" ${item.key===provider?'selected':''}>${item.name}</option>`).join('')}</select></label><span class="tag ${verified?'good':'warn'}">ATS type: ${verified?`${esc(verified.provider)} verified`:'unverified'}</span><button class="button" data-board-suggestion="${index}" ${verified?.enabled?'disabled':''}>${verified?.enabled?'Enabled':'Test &amp; enable'}</button></article>`}).join('');
 return `<section class="card spaced-card company-boards-settings"><div class="section-head section-head-compact"><div><h2>Company boards</h2><p class="muted small">Add Ashby, Workable, or SmartRecruiters board identifiers. Saved identifiers are merged with environment settings and take effect without restarting the backend. Boards must pass a public HTTPS test before enabling.</p></div></div><form id="company-board-add-form"><div class="form-grid"><label class="field"><span>Provider</span><select name="provider" required>${providerOptions}</select></label><label class="field"><span>Company name</span><input name="company" maxlength="180" required placeholder="Example Labs"></label><label class="field full"><span>Board / account identifier</span><input name="identifier" maxlength="100" pattern="[A-Za-z0-9_-]+" required placeholder="example-labs"></label></div><div class="actions"><button class="button button-primary" type="submit">Add unverified board</button></div><div id="company-board-add-result" class="muted small" aria-live="polite"></div></form><div class="company-board-list">${cards}</div><div class="section-head"><div><h3>Suggested India-hiring companies</h3><p class="muted small">These are company and identifier candidates only. ATS/provider and IDs are unverified guesses; a successful test is required before a suggestion is enabled.</p></div><span class="tag">${COMPANY_BOARD_SUGGESTIONS.length} suggestions</span></div><div class="company-board-suggestions">${suggestions}</div></section>`;
}

async function addTestedCompanyBoard({company,identifier,provider}){
 let boards=await api('/sources/company-boards');
 let board=boards.find(item=>item.provider===provider&&item.identifier.toLowerCase()===identifier.toLowerCase());
 if(!board)board=await api('/sources/company-boards',{method:'POST',body:JSON.stringify({company,identifier,provider})});
 const result=await api(`/sources/company-boards/${board.id}/test`,{method:'POST'});
 if(!result.ok)throw new Error(result.message||'Board test failed; the board remains unverified and disabled.');
 if(!board.enabled)await api(`/sources/company-boards/${board.id}`,{method:'PUT',body:JSON.stringify({enabled:true})});
 return result;
}

function bindCompanyBoardSettings(){
 const form=document.getElementById('company-board-add-form');
 form.onsubmit=async event=>{event.preventDefault();const output=document.getElementById('company-board-add-result');const data=Object.fromEntries(new FormData(form));output.textContent='Saving unverified board…';try{await api('/sources/company-boards',{method:'POST',body:JSON.stringify(data)});toast('Board saved as unverified. Test it before enabling.');await renderSources()}catch(error){output.textContent=error.message}};
 content.querySelectorAll('[data-board-test]').forEach(button=>button.onclick=async()=>{button.disabled=true;try{const result=await api(`/sources/company-boards/${button.dataset.boardTest}/test`,{method:'POST'});toast(result.ok?`Board verified · ${Number(result.jobsFound||0)} job(s) found.`:`Board remains unverified: ${result.message}`);await renderSources()}catch(error){toast(error.message)}finally{button.disabled=false}});
 content.querySelectorAll('[data-board-toggle]').forEach(button=>button.onclick=async()=>{try{await api(`/sources/company-boards/${button.dataset.boardToggle}`,{method:'PUT',body:JSON.stringify({enabled:button.dataset.enabled!=='true'})});toast('Company board setting saved');await renderSources()}catch(error){toast(error.message)}});
 content.querySelectorAll('[data-board-delete]').forEach(button=>button.onclick=async()=>{if(!confirm('Remove this company board configuration? Imported jobs and application records will remain.'))return;try{await api(`/sources/company-boards/${button.dataset.boardDelete}`,{method:'DELETE'});toast('Company board removed');await renderSources()}catch(error){toast(error.message)}});
 content.querySelectorAll('[data-board-suggestion]').forEach(button=>button.onclick=async()=>{const suggestion=COMPANY_BOARD_SUGGESTIONS[Number(button.dataset.boardSuggestion)];const provider=document.querySelector(`[data-suggestion-provider="${button.dataset.boardSuggestion}"]`).value;button.disabled=true;try{const result=await addTestedCompanyBoard({...suggestion,provider});toast(`Board verified and enabled · ${Number(result.jobsFound||0)} job(s) found.`);await renderSources()}catch(error){toast(error.message);await renderSources()}finally{button.disabled=false}});
}

async function renderSources(){
 let manual=[],companyBoards=[],sourceStatus;try{[manual,companyBoards,state.portalCapabilities,sourceStatus]=await Promise.all([api('/sources/manual'),api('/sources/company-boards'),api('/portal-capabilities'),api('/sources')]);state.sources=sourceStatus.sources||[]}catch(e){throw new Error(`Sources registry could not load: ${e.message}`)}
 const builtins=state.sources||[];
 const sourceTypes=[
  {name:'Company boards',members:['Greenhouse','Lever','Ashby','Workable','SmartRecruiters'],endpoint:'Company boards',notes:'Greenhouse, Lever, Ashby, Workable and SmartRecruiters public job boards.'},
  {name:'Adzuna',members:['Adzuna'],endpoint:'Adzuna',notes:'Saved Adzuna search profiles and daily API usage.'},
  {name:'Email alerts',members:['Email alerts'],endpoint:'Email alerts',notes:'Allowlisted job-alert emails from your connected mailbox.'},
  {name:'Career pages',members:['Career pages'],endpoint:'Career pages',notes:'Public HTTPS pages parsed for JobPosting JSON-LD.'},
  {name:'Manual',members:['Manual'],endpoint:'',notes:'Jobs saved by you; this source has no automatic feed to run.'}
 ];
 const sourceCards=sourceTypes.map(group=>{
  const rows=group.members.map(name=>builtins.find(item=>item.source===name)).filter(Boolean);
  const count=rows.reduce((sum,row)=>sum+Number(row.jobCount||0),0);
  const lastSuccess=rows.map(row=>row.lastSuccessfulSync||'').filter(Boolean).sort().pop()||'Never';
  const errors=rows.map(row=>row.error||row.errors||'').flatMap(value=>Array.isArray(value)?value:[value]).filter(Boolean);
  let status=rows.some(row=>row.enabled)?'Enabled':rows[0]?.status||'Not configured';
  if(group.name==='Company boards'&&!sourceStatus.publicFeedsEnabled)status='Public feeds disabled';
  if(group.name==='Company boards'&&sourceStatus.publicFeedsEnabled&&!rows.some(row=>Number(row.enabledBoards||0)>0))status='No enabled boards';
  if(errors.length)status='Needs attention';
  const counts=rows.reduce((summary,row)=>({
   fetched:summary.fetched+Number(row.fetched||0),
   imported:summary.imported+Number(row.imported||0),
   updated:summary.updated+Number(row.updated||0),
   rejected:summary.rejected+Number(row.rejected||0),
   deduplicated:summary.deduplicated+Number(row.deduplicated||0)
  }),{fetched:0,imported:0,updated:0,rejected:0,deduplicated:0});
  return `<article class="source-type-card card"><div class="provider-top"><div class="source-logo">${esc(group.name.slice(0,1))}</div><div><strong>${esc(group.name)}</strong><small>${esc(group.notes)}</small></div><span class="tag ${errors.length?'warn':rows.some(row=>row.enabled)?'good':''}">${esc(status)}</span></div><p class="muted small">${count} saved job${count===1?'':'s'} · Last success: ${esc(lastSuccess)}</p><p class="muted small">Last run: ${esc(rows.map(row=>row.lastAttempt||'').filter(Boolean).sort().pop()||'Never')} · ${counts.fetched} checked · ${counts.imported} imported · ${counts.updated} updated · ${counts.rejected} rejected · ${counts.deduplicated} duplicates</p><p class="${errors.length?'source-error':'muted small'}"><b>Last error:</b> ${esc(errors.join('; ')||'None recorded')}</p><div class="actions"><button class="button button-primary" data-run-source="${esc(group.endpoint)}" ${group.endpoint?'':'disabled title="Manual jobs are saved individually; there is no feed to run."'}>Run now</button>${group.name==='Manual'?'<button class="button" data-manual-add>＋ Add job</button>':''}</div></article>`;
 }).join('');
 const providers=[
  {name:'Career pages',type:'Public JSON-LD career pages',url:'https://schema.org/JobPosting',notes:'Imports only schema.org JobPosting JSON-LD from HTTPS pages configured in Settings. robots.txt is checked; no CSS scraping, login, cookies, or redirects.',rate:'Daily sync; one request per page per run and one second between requests.'},
  {name:'Email alerts',type:'User-authorized IMAP',url:'https://support.google.com/mail/answer/7126229',notes:'Reads allowlisted job-alert email only from your own mailbox over IMAP/TLS. No job-site scraping or mailbox content upload.',rate:'Scheduled every 2 hours and available from Refresh jobs; off by default.'},
  {name:'Adzuna',type:'Job search API',url:'https://www.adzuna.in/',notes:'Searches your saved keyword and location profiles through the Adzuna API. Requires ADZUNA_APP_ID and ADZUNA_APP_KEY; no redirect is followed during sync.',rate:'Daily API-call cap is configurable; default 200. Each profile uses at most 3 pages.'},
  {name:'Greenhouse',type:'Employer job boards',url:'https://boards.greenhouse.io/',notes:'Public board listing access depends on a valid employer board token. This URL alone does not configure a board.',rate:'Per-board requests; back off on failures.'},
  {name:'Lever',type:'Employer job boards',url:'https://jobs.lever.co/',notes:'Public posting access depends on a valid employer site identifier. This URL alone does not configure a site.',rate:'Per-site requests; back off on failures.'},
  {name:'Ashby',type:'Employer job boards',url:'https://jobs.ashbyhq.com/',notes:'Public posting API; configure and test a board identifier before enabling sync.',rate:'At most one request per second; honor Retry-After.'},
  {name:'Workable',type:'Employer job boards',url:'https://apply.workable.com/',notes:'Public posting API; configure and test an account identifier before enabling sync.',rate:'At most one request per second; honor Retry-After.'},
  {name:'SmartRecruiters',type:'Employer job boards',url:'https://jobs.smartrecruiters.com/',notes:'Public posting API with offset/limit pagination; configure and test a company identifier before enabling sync.',rate:'At most one request per second; honor Retry-After.'},
  {name:'LinkedIn',type:'Official search fallback',url:'https://www.linkedin.com/jobs/',notes:'Use official access and permitted search links. No unauthorized scraping or automated Easy Apply.',rate:'Follow LinkedIn account and platform restrictions.'},
  {name:'Naukri',type:'Official search fallback',url:'https://www.naukri.com/',notes:'Use permitted search links or approved integration; do not automate restricted account activity.',rate:'Follow portal terms and rate limits.'},
  {name:'Indeed',type:'Official search fallback',url:'https://www.indeed.com/',notes:'External job-search link only in this build; no inbox or application API is connected.',rate:'Follow Indeed terms and account restrictions.'}
 ];
 content.innerHTML=`<div class="source-hero card"><div><div class="eyebrow">DISCOVERY CONNECTORS</div><h2>One place for every job source</h2><p class="muted">Connect feeds and APIs, save career pages manually, test URLs, and see exactly what is configured. Suggested board identifiers are explicitly unverified until tested.</p></div><div class="source-metrics"><div><strong>${builtins.filter(x=>x.enabled).length}</strong><span>Built-in enabled</span></div><div><strong>${manual.length}</strong><span>Manual sources</span></div><div><strong>${builtins.filter(x=>x.error).length+manual.filter(x=>x.connectionStatus==='ERROR').length}</strong><span>Reported errors</span></div></div></div>
 ${companyBoardSettingsMarkup(companyBoards)}
 <div class="card spaced-card"><div class="section-head section-head-compact"><div><h2>Existing job data</h2><p class="muted small">Reconciliation labels legacy, invalid, and overdue records without deleting jobs, saved items, or application history. Duplicate candidates are reported for review.</p></div><button class="button" id="reconcile-job-data">Reconcile job data</button></div><div id="reconcile-result" class="muted small"></div></div>
 <div class="card spaced-card"><div class="section-head section-head-compact"><div><h2>Add a source manually</h2><p class="muted small">Paste a public HTTPS feed, API endpoint, company careers page, or portal search URL. Menix tests reachability and gives an advisory format guess; it will not assume every URL is importable.</p></div></div>
 <form id="source-add-form"><div class="form-grid"><div class="field"><label for="source-name">Display name (optional)</label><input id="source-name" name="name" placeholder="e.g. Acme Careers"></div><div class="field"><label for="source-type">Source type</label><select id="source-type" name="type"><option value="CAREER_SITE">Company career page</option><option value="JSON_FEED">JSON API / feed</option><option value="RSS_FEED">RSS / Atom feed</option><option value="JOB_BOARD">Job board search page</option><option value="EMPLOYER_BOARD">Employer ATS board</option></select></div><div class="field full"><label for="source-url">HTTPS URL</label><input id="source-url" name="url" type="url" placeholder="https://careers.example.com/jobs" required></div><div class="field"><label for="source-terms">Terms / permissions notes</label><textarea id="source-terms" name="termsNotes">Verify provider terms and permissions before enabling automated retrieval.</textarea></div><div class="field"><label for="source-rate">Rate-limit notes</label><textarea id="source-rate" name="rateLimitNotes">Honor provider rate limits, HTTP 429, and Retry-After.</textarea></div></div><div class="actions"><button class="button button-primary" type="submit">＋ Add source</button><button class="button" type="button" id="source-discover">Discover supported source types</button></div><div id="source-add-result" class="muted small"></div></form></div>
 <div class="section-head spaced-card"><div><h2>Saved manual sources</h2><p class="muted small">Persistent local records. Test a source before treating it as ready.</p></div><button class="button" id="source-refresh">Refresh list</button></div>
 ${manual.length?`<div class="source-cards">${manual.map(s=>`<article class="source-card card" data-source-id="${s.id}"><div class="source-card-head"><div class="source-logo">${esc((s.name||'S').slice(0,1).toUpperCase())}</div><div class="source-card-title"><strong>${esc(s.name)}</strong><small>${esc(s.sourceUrl)}</small></div><span class="tag ${s.connectionStatus==='REACHABLE'?'good':s.connectionStatus==='ERROR'?'warn':''}">${esc(s.connectionStatus||'NOT_TESTED')}</span></div><div class="source-badges"><span class="tag">${esc(s.sourceType)}</span><span class="tag">${esc(s.detectedFormat||'UNKNOWN')}</span><span class="tag ${s.enabled?'good':''}">${s.enabled?'Enabled':'Paused'}</span></div><div class="source-notes"><p><b>Terms:</b> ${esc(s.termsNotes||'Not specified')}</p><p><b>Rate limits:</b> ${esc(s.rateLimitNotes||'Not specified')}</p></div><div class="source-sync"><span>Last test: ${esc(s.lastTestAt||'Never tested')}</span><span>Last sync: ${esc(s.lastSyncAt||'Not synced')}</span></div>${s.lastError?`<div class="source-error"><b>Diagnostic</b><p>${esc(s.lastError)}</p></div>`:''}<div class="actions"><button class="button button-primary" data-source-test="${s.id}">Test connection</button><button class="button" data-source-toggle="${s.id}" data-enabled="${s.enabled}">${s.enabled?'Disable':'Enable'}</button><button class="button button-danger" data-source-delete="${s.id}">Remove</button><a class="button button-quiet" href="${esc(s.sourceUrl)}" target="_blank" rel="noreferrer">Open site ↗</a></div><div class="source-test-result" id="source-test-${s.id}" aria-live="polite"></div></article>`).join('')}</div>`:'<div class="card empty">No manual sources saved yet. Add a public feed or career URL above. Your saved entries will appear here.</div>'}
 <div class="section-head spaced-card"><div><h2>Provider catalog</h2><p class="muted small">Reference options only. “Add manually” saves a URL; it does not grant private API access.</p></div><button class="button" data-go="capabilities">Portal capabilities</button></div>
 <div class="provider-catalog">${providers.map(p=>{const current=builtins.find(s=>s.source===p.name);const capability=state.portalCapabilities.find(item=>item.portal===p.name);const status=['Adzuna','Email alerts','Career pages'].includes(p.name)?(current?.status||'Not configured'):capability?.connectionState==='CONNECTED'?'Connected':capability?.error?'Error':capability?.connectionState==='NOT_TESTED'?'Enabled · not verified':current?.enabled?'Enabled · not verified':capability?'External link only':'Not connected';return `<article class="provider-card card"><div class="provider-top"><div class="source-logo">${esc(p.name.slice(0,1))}</div><div><strong>${esc(p.name)}</strong><small>${esc(p.type)}</small></div><span class="tag ${['Ready','Connected','Enabled'].includes(status)?'good':status==='Not configured'||status==='Daily limit reached'||status==='Off'||status==='Needs attention'?'warn':''}">${esc(status)}</span></div><p>${esc(p.notes)}</p>${current?.configuredPages!==undefined?`<p class="muted small">${Number(current.configuredPages)} page(s) configured · ${Number(current.fetched||0)} found · ${Number(current.imported||0)} imported · ${Number(current.updated||0)} updated · ${Number(current.rejected||0)} rejected · ${Number(current.deduplicated||0)} duplicates</p>`:''}${current?.configuredBoards!==undefined?`<p class="muted small">${Number(current.configuredBoards)} board(s) configured · ${Number(current.enabledBoards||0)} enabled${current.enabled?' · feed sync enabled':' · feed sync disabled'}</p>`:''}${p.name==='Adzuna'&&current?`<p class="muted small">${Number(current.profilesConfigured||0)} profile(s) · ${Number(current.callsToday||0)} of ${Number(current.dailyCallCap||200)} daily API calls used · ${Number(current.remainingCalls||0)} remaining</p>`:''}${p.name==='Email alerts'&&current?`<p class="muted small">Last run: ${esc(current.lastAttempt||'Never')} · ${Number(current.imported||0)} jobs imported last run</p>`:''}${capability?.lastAttempt||current?.lastAttempt?`<p class="muted small">Last attempt: ${esc(capability?.lastAttempt||current.lastAttempt)}</p>`:''}${current?.lastSuccessfulSync?`<p class="muted small">Last successful sync: ${esc(current.lastSuccessfulSync)}</p>`:''}${current?.lastSuccessfulSync&&p.name==='Career pages'?`<p class="muted small">${Number(current.fetched||0)} postings · ${Number(current.rejected||0)} rejected · ${Number(current.deduplicated||0)} duplicates</p>`:''}${capability?.fetched!==undefined?`<p class="muted small">${Number(capability.fetched)} fetched · ${Number(capability.imported||0)} imported · ${Number(capability.updated||0)} updated · ${Number(capability.rejected||0)} rejected · ${Number(capability.deduplicated||0)} duplicates · ${Number(capability.verifiedJobs||0)} currently verified open</p>`:''}${(capability?.error||current?.error)?`<p class="source-error"><b>Latest provider error</b><br>${esc(capability?.error||current.error)}</p>`:''}<p class="muted small"><b>Rate guidance:</b> ${esc(p.rate)}</p><div class="actions">${current?.enabled?`<button class="button" data-sync-provider="${esc(p.name)}">Retry sync</button>`:''}${p.name==='Career pages'?'<button class="button" data-career-settings-open>Manage in Settings</button>':''}${p.name!=='Email alerts'&&p.name!=='Career pages'?`<button class="button" data-catalog-add="${esc(p.name)}" data-catalog-url="${esc(p.url)}" data-catalog-type="${p.name==='LinkedIn'||p.name==='Naukri'||p.name==='Indeed'?'JOB_BOARD':p.name==='Greenhouse'||p.name==='Lever'||p.name==='Ashby'||p.name==='Workable'||p.name==='SmartRecruiters'?'EMPLOYER_BOARD':'JSON_FEED'}">Save URL / setup note</button>`:''}${['LinkedIn','Naukri','Indeed'].includes(p.name)?`<button class="button button-quiet" data-open-provider="${esc(p.url)}">Open search site ↗</button>`:''}</div></article>`}).join('')}</div>
 <div class="notice spaced-card"><strong>Safety and support:</strong> Public-feed sync is disabled unless <code>PUBLIC_JOB_FEEDS_ENABLED=true</code>. Career pages are managed in Settings and parse only JobPosting JSON-LD after a robots.txt check. Board tests use HTTPS, do not follow redirects, and block private/local addresses. A successful test only verifies that a public board responds; it does not confirm provider terms or submit applications.</div>`;
 content.querySelector('.source-hero')?.insertAdjacentHTML('afterend',`<section class="source-type-grid">${sourceCards}</section>`);
 const providerCatalog=content.querySelector('.provider-catalog');
 providerCatalog?.previousElementSibling?.remove();
 providerCatalog?.remove();
 bindCompanyBoardSettings();
 document.getElementById('source-add-form').onsubmit=async e=>{e.preventDefault();const form=Object.fromEntries(new FormData(e.currentTarget));const out=document.getElementById('source-add-result');out.textContent='Saving source…';try{const created=await api('/sources/manual',{method:'POST',body:JSON.stringify(form)});out.textContent=`Saved ${created.name}. Test the connection before enabling any automated retrieval.`;toast('Source saved');await renderSources();}catch(err){out.textContent=err.message;toast('Could not add source')}};
 document.getElementById('source-discover').onclick=()=>{document.getElementById('source-type').focus();toast('Choose a source type, then paste its public URL. Detection is advisory.');};
 document.getElementById('source-refresh').onclick=()=>renderSources().catch(e=>toast(e.message));
 document.getElementById('reconcile-job-data').onclick=async event=>{if(!confirm('Mark unverified, stale, and invalid listings? No jobs or application history will be deleted.'))return;const button=event.currentTarget;button.disabled=true;try{const result=await api('/jobs/reconcile',{method:'POST'});document.getElementById('reconcile-result').textContent=`Examined ${result.recordsExamined}; marked ${result.markedUnverified} unverified, ${result.markedStale} stale, ${result.markedInvalid} invalid; found ${result.duplicateRecords} duplicate candidates. Preserved ${result.savedRecordsPreserved} saved records and ${result.applicationRecordsPreserved} application records; deleted ${result.deletedRecords}.`;toast('Job reconciliation completed');await renderSources();}catch(error){document.getElementById('reconcile-result').textContent=`Reconciliation failed: ${error.message}`;}finally{button.disabled=false;}};
 content.querySelectorAll('[data-source-test]').forEach(btn=>btn.onclick=async()=>{const id=btn.dataset.sourceTest;const out=document.getElementById(`source-test-${id}`);out.textContent='Testing HTTPS reachability and detecting content type…';btn.disabled=true;try{const r=await api(`/sources/manual/${id}/test`,{method:'POST'});out.innerHTML=`<div class="${r.ok?'source-success':'source-error'}"><b>${r.ok?'Reachable':'Test failed'} · ${esc(r.detectedFormat||'Unknown')}</b><p>${esc(r.message||'')}</p>${r.statusCode?`<small>HTTP ${r.statusCode} · ${esc(r.contentType||'')}</small>`:''}</div>`;await renderSources();}catch(e){out.textContent=e.message}finally{btn.disabled=false}});
 content.querySelectorAll('[data-source-toggle]').forEach(btn=>btn.onclick=async()=>{try{await api(`/sources/manual/${btn.dataset.sourceToggle}`,{method:'PUT',body:JSON.stringify({enabled:btn.dataset.enabled!=='true'})});toast('Source setting saved');await renderSources()}catch(e){toast(e.message)}});
 content.querySelectorAll('[data-source-delete]').forEach(btn=>btn.onclick=async()=>{if(!confirm('Remove this saved source? Existing imported jobs will remain.'))return;try{await api(`/sources/manual/${btn.dataset.sourceDelete}`,{method:'DELETE'});toast('Source removed');await renderSources()}catch(e){toast(e.message)}});
 content.querySelectorAll('[data-catalog-add]').forEach(btn=>btn.onclick=()=>{document.getElementById('source-name').value=btn.dataset.catalogAdd;document.getElementById('source-url').value=btn.dataset.catalogUrl;document.getElementById('source-type').value=btn.dataset.catalogType;document.getElementById('source-url').scrollIntoView({behavior:'smooth',block:'center'});document.getElementById('source-url').focus();});
 content.querySelectorAll('[data-sync-provider]').forEach(btn=>btn.onclick=async()=>{btn.disabled=true;try{const result=await api(`/jobs/sync/${encodeURIComponent(btn.dataset.syncProvider)}`,{method:'POST'});toast(result.error?`${btn.dataset.syncProvider} sync failed: ${result.error}`:`${btn.dataset.syncProvider} sync completed; ${Number(result.imported||0)} imported, ${Number(result.updated||0)} updated.`);await renderSources();}catch(error){toast(`Could not sync ${btn.dataset.syncProvider}: ${error.message}`)}finally{btn.disabled=false}});
 content.querySelectorAll('[data-run-source]').forEach(button=>button.onclick=async()=>{
  const name=button.dataset.runSource;
  if(!name)return;
  button.disabled=true;button.textContent='Running…';
  try{
   const result=await api(`/jobs/sync/${encodeURIComponent(name)}`,{method:'POST'});
   const errors=result.errors?.length?` Errors: ${result.errors.join('; ')}`:result.error?` Error: ${result.error}`:'';
   toast(`${name}: ${Number(result.imported||0)} imported, ${Number(result.updated||0)} updated, ${Number(result.deduplicated||0)} duplicates.${errors}`);
   await renderSources();
  }catch(error){toast(`${name} sync failed: ${error.message}`);}
  finally{button.disabled=false;button.textContent='Run now';}
 });
 content.querySelector('[data-manual-add]')?.addEventListener('click',showAddJob);
 content.querySelectorAll('[data-open-provider]').forEach(btn=>btn.onclick=()=>{const a=document.createElement('a');a.href=btn.dataset.openProvider;a.target='_blank';a.rel='noreferrer';a.click()});
}

function profileCompleteness() {
  const p=state.profile||{}; const fields=['fullName','email','headline','skills','preferredTitles','preferredLocations']; return Math.round(fields.filter(k=>String(p[k]||'').trim()).length/fields.length*100);
}
function renderProfile() {
 const p=state.profile||{};
 content.innerHTML=`<div class="card"><div class="section-head section-head-compact"><div><h2>Your candidate profile</h2><div class="muted small">Only enter information that is true and verified. This profile powers match explanations and draft suggestions.</div></div><span class="tag good">${profileCompleteness()}% complete</span></div>
 <form id="profile-form"><div class="form-grid">
 ${field('Experience level','experienceLevel',p.experienceLevel||'Fresher / entry level','Fresher / entry level, junior, mid-level or senior')}
 ${field('Full name','fullName',p.fullName,'Your name')}${field('Email','email',p.email,'you@example.com','email')}${field('Phone number','phone',p.phone,'Your phone number','tel')}${field('LinkedIn profile','linkedinUrl',p.linkedinUrl,'https://linkedin.com/in/…')}${field('GitHub profile','githubUrl',p.githubUrl,'https://github.com/…')}${field('Portfolio / website','portfolioUrl',p.portfolioUrl,'https://…')}
 ${field('Professional headline','headline',p.headline,'Entry-level Java / Full Stack Developer','text','full')}
 ${field('Skills','skills',p.skills,'Java, Spring Boot, SQL, JavaScript')}
 ${field('Preferred job titles','preferredTitles',p.preferredTitles,'Java Developer, Backend Developer')}
 ${field('Preferred locations','preferredLocations',p.preferredLocations,'Jaipur, Bengaluru, Remote')}
 ${field('Education','education',p.education,'Degree, institution and graduation year')}
 ${field('Minimum expected salary (optional)','minimumSalary',p.minimumSalary,'Leave blank if flexible')}
 ${field('Projects','projects',p.projects,'Project names and what you built','textarea','full')}
 ${field('Experience / notes','experience',p.experience,'Fresher, internships or verified experience only','textarea','full')}
 </div><div class="actions"><button class="button button-primary" type="submit">Save profile</button></div></form></div>
 <div class="card spaced-card"><h2>Resume import</h2><p class="muted small">Upload a PDF, DOCX, DOC or TXT file (up to 8 MB). Menix reads text locally and suggests profile fields. You choose what to use; scanned image PDFs need OCR and are not read yet.</p><form id="resume-form"><div class="field"><label for="resume-file">Choose resume</label><input id="resume-file" name="file" type="file" accept=".pdf,.docx,.doc,.txt" required></div><div class="actions"><button class="button button-primary" type="submit">Read resume</button></div></form><div id="resume-result" class="muted small resume-result"></div><div id="resume-suggestions"></div></div>
 <div class="notice spaced-card"><strong>Privacy note:</strong> this local MVP saves profile data to its local database. Do not expose the backend to the public internet. Authentication and production-grade data protection still need to be added before multi-user or public deployment.</div>`;
 document.getElementById('profile-form').addEventListener('submit',async e=>{e.preventDefault();const form=Object.fromEntries(new FormData(e.currentTarget));form.minimumSalary=form.minimumSalary||'';try{await api('/profile',{method:'PUT',body:JSON.stringify(form)});toast('Profile saved');await render();}catch(err){toast(err.message)}});
 document.getElementById('resume-form').addEventListener('submit',async e=>{e.preventDefault();const file=document.getElementById('resume-file').files[0];if(!file)return;const fd=new FormData();fd.append('file',file);const result=document.getElementById('resume-result');result.textContent='Reading and saving resume locally...';try{const r=await api('/resumes/upload',{method:'POST',body:fd});result.textContent=`${r.filename} ? ${Math.round(r.sizeBytes/1024)} KB. ${r.message}`;showResumeSuggestions(r.suggestions||{});toast('Resume read');}catch(err){result.textContent=err.message;toast('Resume import failed');}});
}
function showResumeSuggestions(suggestions){
 const box=document.getElementById('resume-suggestions');const labels={fullName:'Full name',email:'Email',phone:'Phone number',linkedinUrl:'LinkedIn profile',githubUrl:'GitHub profile',portfolioUrl:'Portfolio / website',headline:'Professional headline',skills:'Skills',preferredTitles:'Preferred job titles',education:'Education',projects:'Projects',experience:'Experience'};const entries=Object.entries(suggestions).filter(([key,value])=>labels[key]&&String(value||'').trim());
 if(!entries.length){box.innerHTML='<div class="notice spaced-notice">No profile fields were identified. Check that the resume contains selectable text, or enter your details manually.</div>';return;}
 box.innerHTML=`<div class="notice spaced-notice"><strong>Review extracted details</strong><br>These suggestions came from resume text. Select the fields you want, edit anything inaccurate, then save your profile.</div><div class="form-grid resume-suggestion-grid">${entries.map(([key,value])=>`<div class="field ${['education','projects','experience'].includes(key)?'full':''}"><label class="suggestion-label"><input type="checkbox" checked data-use-resume="${key}"> Use ${labels[key]}</label>${['education','projects','experience'].includes(key)?`<textarea data-resume-value="${key}">${esc(value)}</textarea>`:`<input data-resume-value="${key}" value="${esc(value)}">`}</div>`).join('')}</div><div class="actions"><button class="button button-primary" type="button" id="apply-resume-suggestions">Fill selected profile fields</button></div>`;
 document.getElementById('apply-resume-suggestions').onclick=()=>{let applied=0;box.querySelectorAll('[data-use-resume]').forEach(check=>{if(!check.checked)return;const key=check.dataset.useResume;const target=document.querySelector(`#profile-form [name="${key}"]`);const value=box.querySelector(`[data-resume-value="${key}"]`);if(target&&value&&value.value.trim()){target.value=value.value.trim();applied++;}});toast(`${applied} profile fields filled. Review them, then save your profile.`);};
}
function field(label,name,value='',placeholder='',type='text',width='') {
 const common=`name="${name}" id="${name}" value="${esc(value)}" placeholder="${esc(placeholder)}"`;
 if(type==='textarea') return `<div class="field ${width}"><label for="${name}">${label}</label><textarea name="${name}" id="${name}" placeholder="${esc(placeholder)}">${esc(value)}</textarea></div>`;
 return `<div class="field ${width}"><label for="${name}">${label}</label><input ${common} type="${type}"></div>`;
}
function canonicalSkill(value){const key=String(value||'').trim().toLowerCase();return ({js:'javascript',node:'node.js',postgres:'postgresql',reactjs:'react','restful api':'rest api'})[key]||key;}
function careerBucket(job){
 const title=String(job.title||'').toLowerCase(), text=`${title} ${job.experienceLevel||''} ${job.description||''}`.toLowerCase();
 if(/\b(senior|sr\.?|principal|staff|lead|director|vp|vice president|head of|manager|chief|iii|iv)\b/.test(title))return 'senior';
 if(/\b(intern(ship)?|fresher|entry[- ]level|junior|jr\.?|graduate|new grad|campus|trainee|apprentice|associate)\b/.test(title))return 'entry';
 if(/\b(?:0\s*(?:-|to)\s*2\s*years?|no experience required|recent graduate|early career)\b/.test(text))return 'entry';
 if(/\b(5\+|6\+|7\+|8\+|10\+)\s*years?\b/.test(text))return 'senior';
 return 'unspecified';
}
function stageAllows(job){return state.careerStage!=='fresher'||careerBucket(job)!=='senior';}
function dashboardDiagnosticsMarkup(diagnostics={}){
 const verificationHours=Number(diagnostics.verificationWindowHours||48);
 const findings=[
  [Number(diagnostics.jobsFailingVerificationWindow||0),`${Number(diagnostics.jobsFailingVerificationWindow||0)} jobs hidden because they were not verified in the last ${verificationHours} hours.`],
  [Number(diagnostics.jobsWithoutVerificationTimestamp||0),`${Number(diagnostics.jobsWithoutVerificationTimestamp||0)} jobs have no verification timestamp; revisit or re-import them to verify availability.`],
  [Number(diagnostics.jobsOutsideDiscoveryWindow||0),`${Number(diagnostics.jobsOutsideDiscoveryWindow||0)} jobs were first discovered more than 7 days ago.`],
  [Number(diagnostics.jobsWithMatchScoreZero||0),`${Number(diagnostics.jobsWithMatchScoreZero||0)} jobs currently have a match score of 0.`],
  [Number(diagnostics.jobsExcludedRetiredSource||0),`${Number(diagnostics.jobsExcludedRetiredSource||0)} jobs are hidden because their source has been retired.`]
 ].filter(([count])=>count>0).map(([,hint])=>`<li>${esc(hint)}</li>`);
 const greenhouseBoards=Number(diagnostics.greenhouseBoardsConfigured||0);
 const leverBoards=Number(diagnostics.leverBoardsConfigured||0);
 const ashbyBoards=Number(diagnostics.ashbyBoardsConfigured||0);
 const workableAccounts=Number(diagnostics.workableAccountsConfigured||0);
 const smartRecruitersCompanies=Number(diagnostics.smartRecruitersCompaniesConfigured||0);
 if(!greenhouseBoards&&!leverBoards&&!ashbyBoards&&!workableAccounts&&!smartRecruitersCompanies)findings.push('<li>No employer boards configured: add companies in Sources.</li>');
 if(diagnostics.publicFeedsEnabled===false)findings.push('<li>Public employer-board feeds are disabled by default. Enable them with PUBLIC_JOB_FEEDS_ENABLED=true after configuring permitted sources.</li>');
 const sources=(diagnostics.sources||[]).map(source=>{
  const stateLabel=source.enabled?'Enabled':'Disabled';
  const configuredLabel=source.configured?'Configured':'Not configured';
  const boardCount=Number(source.configuredBoards||0);
  const boardText=boardCount?` · ${boardCount} board${boardCount===1?'':'s'}`:'';
  return `<li><strong>${esc(source.source||'Source')}</strong>: ${stateLabel} · ${configuredLabel}${boardText}</li>`;
 }).join('');
 return `<section class="card dashboard-diagnostics"><div class="panel-heading"><div><h3>Why is my dashboard empty?</h3><p>Dashboard default: ${esc(diagnostics.dashboardFreshnessDefault||'24h')} · selected date filter: ${esc(state.dashboardFreshness)} · verification window: ${verificationHours} hours</p></div><span>${Number(diagnostics.totalJobs||0)} total jobs</span></div><ul class="dashboard-diagnostic-findings">${findings.length?findings.join(''):'<li>No exclusion issues were found in the diagnostic counts.</li>'}</ul><h4>Source configuration</h4><ul class="dashboard-diagnostic-sources">${sources||'<li>No sources are listed.</li>'}</ul></section>`;
}
function extractCurrentJobFromPage(){
 const clean=(value,max=4000)=>String(value||'').replace(/<[^>]*>/g,' ').replace(/\s+/g,' ').trim().slice(0,max);
 const textOf=node=>clean(node?.innerText||'');
 const isVisible=node=>{
  if(!node?.getClientRects?.().length)return false;
  const style=getComputedStyle(node);
  return style.display!=='none'&&style.visibility!=='hidden';
 };
 const stripMarkup=value=>{
  const holder=document.createElement('div');
  holder.innerHTML=String(value||'');
  return clean(holder.innerText||holder.textContent||'');
 };
 const jobPostings=[];
 const visit=value=>{
  if(Array.isArray(value)){value.forEach(visit);return;}
  if(!value||typeof value!=='object')return;
  const types=Array.isArray(value['@type'])?value['@type']:[value['@type']];
  if(types.includes('JobPosting'))jobPostings.push(value);
  if(value['@graph'])visit(value['@graph']);
  if(value['@type']==='ItemList'&&value.itemListElement)visit(value.itemListElement.map(item=>item.item||item));
 };
 document.querySelectorAll('script[type="application/ld+json"]').forEach(script=>{
  try{visit(JSON.parse(script.textContent||''));}catch{}
 });
 const currentUrl=new URL(location.href);
 const matchingPostings=jobPostings.filter(posting=>{
  const raw=typeof posting.url==='string'?posting.url:posting.url?.url;
  if(!raw)return false;
  try{
   const postingUrl=new URL(raw,location.href);
   return postingUrl.hostname===currentUrl.hostname&&postingUrl.pathname===currentUrl.pathname;
  }catch{return false;}
 });
 const posting=matchingPostings.length===1?matchingPostings[0]:null;
 if(jobPostings.length&&!posting)return {error:jobPostings.length>1?'This page contains multiple job listings. Open one job detail page before saving it.':'Open an individual job detail page. Menix does not save search result lists.'};
 const structuredLocation=posting?.jobLocation;
 const address=Array.isArray(structuredLocation)?structuredLocation[0]?.address:structuredLocation?.address;
 const locationText=typeof address==='string'?address:[address?.addressLocality,address?.addressRegion,address?.addressCountry?.name||address?.addressCountry].filter(Boolean).join(', ');
 const selectorText=selectors=>{
  for(const selector of selectors.split(',')){
   const node=Array.from(document.querySelectorAll(selector.trim())).find(isVisible);
   if(node)return textOf(node);
  }
  return '';
 };
 let title=clean(posting?.title,200);
 let company=clean(posting?.hiringOrganization?.name||posting?.hiringOrganization?.legalName,200);
 let jobLocation=clean(locationText,250);
 let description=posting?.description?stripMarkup(posting.description):'';
 let skills=Array.isArray(posting?.skills)?posting.skills.join(', '):clean(posting?.skills||posting?.qualifications,1000);
 let experienceRequirements=clean(posting?.experienceRequirements,250);
 let employmentType=Array.isArray(posting?.employmentType)?posting.employmentType.join(', '):clean(posting?.employmentType,100);
 let workMode=clean(posting?.jobLocationType,80);
 let publishedAt=clean(posting?.datePosted,32);
 if(!posting){
  const host=currentUrl.hostname.toLowerCase();
  const path=currentUrl.pathname.toLowerCase();
  const detailPath=/job-listings|jobs\/view|viewjob|job-listing|\/careers?\/.+\/(jobs?|positions?)/i.test(path);
  const detailSelectors='#jobDescriptionText, .jobs-description__content, .jd-description, [data-automation="jobDetails"]';
  const hasDetailMarkup=Array.from(document.querySelectorAll(detailSelectors)).some(isVisible);
  const googleJobsDetail=host==='google.com'&&currentUrl.searchParams.has('ibp')&&Array.from(document.querySelectorAll('[role="main"] h1, [role="main"] h2')).some(isVisible);
  if(!detailPath&&!hasDetailMarkup&&!googleJobsDetail)return {error:'Open an individual job detail page. Menix does not save search result lists.'};
  title=selectorText('h1.jd-header-title, h1.topcard__title, h1.jobsearch-JobInfoHeader-title, [data-test="job-title"], [data-automation="jobTitle"], [role="main"] h1, [role="main"] h2, h1').slice(0,200);
  company=selectorText('a.jd-header-comp-name, .jobs-unified-top-card__company-name a, .job-details-jobs-unified-top-card__company-name a, .topcard__org-name-link, [data-test="inlineHeader-companyName"], [data-automation="job-detail-company"], [data-company-name]').slice(0,200);
  jobLocation=selectorText('.jd-header-comp-name + span, .jobs-unified-top-card__bullet, .job-details-jobs-unified-top-card__primary-description-container, .topcard__flavor--bullet, [data-test="inlineHeader-companyLocation"], [data-automation="job-detail-location"]').slice(0,250);
  description=selectorText('#jobDescriptionText, .jobs-description__content, .jd-description, [data-test="jobDescriptionContent"], [data-automation="jobDescription"], main [itemprop="description"]').slice(0,4000);
  skills='';
  experienceRequirements='';
  employmentType='';
  workMode='';
  publishedAt='';
 }
 if(!title)return {error:'Could not identify a job title on this page. Open a job detail page or enter its details manually.'};
 if(/\b(remote|telecommute)\b/i.test(`${workMode} ${jobLocation}`))workMode='Remote';
 else if(/\bhybrid\b/i.test(`${workMode} ${jobLocation}`))workMode='Hybrid';
 else if(/\bon[- ]?site\b|\bin[- ]office\b/i.test(`${workMode} ${jobLocation}`))workMode='On-site';
 const sourceJobId=currentUrl.pathname.match(/job-listings.*-(\d+)\/?$/i)?.[1]||'';
 return {title,company,location:jobLocation,description,requiredSkills:skills,experienceRequirements,employmentType,workMode,sourceJobId,structured:Boolean(posting),...(/^\d{4}-\d{2}-\d{2}/.test(publishedAt)?{publishedAt:publishedAt.slice(0,10)}:{})};
}
function sourceForJobUrl(rawUrl){
 const hostname=new URL(rawUrl).hostname.toLowerCase();
 if(hostname==='naukri.com'||hostname.endsWith('.naukri.com'))return 'Naukri';
 if(hostname==='linkedin.com'||hostname.endsWith('.linkedin.com'))return 'LinkedIn';
 if(hostname==='google.com'||hostname.endsWith('.google.com'))return 'Google Jobs';
 if(hostname==='indeed.com'||hostname.endsWith('.indeed.com'))return 'Indeed';
 if(hostname==='glassdoor.com'||hostname.endsWith('.glassdoor.com'))return 'Glassdoor';
 return 'External Link';
}
function buildNaukriSearchUrl(keyword,location,experience='fresher'){
 const terms=String(keyword||'').trim();
 if(!terms)throw new Error('Enter at least one job keyword before searching Naukri.');
 const searchTerms=experience==='fresher'&&!/\bfresher\b/i.test(terms)?`${terms} fresher`:terms;
 const url=new URL('https://www.naukri.com/jobs-in-india');
 url.searchParams.set('keyword',searchTerms);
 if(String(location||'').trim())url.searchParams.set('location',String(location).trim());
 if(experience==='fresher')url.searchParams.set('experience','0');
 return url.href;
}
function extractVisibleNaukriResultsFromPage(){
 const host=location.hostname.toLowerCase();
 if(host!=='naukri.com'&&!host.endsWith('.naukri.com'))return {error:'Open Naukri search results before importing visible listings.'};
 const clean=(value,max=2000)=>String(value||'').replace(/\s+/g,' ').trim().slice(0,max);
 const text=(card,selector)=>{
  const value=clean(card.querySelector(selector)?.innerText||card.querySelector(selector)?.textContent||'');
  return /^(not disclosed|not specified|unknown|not available|n\/?a|[-—]+)$/i.test(value)?'':value;
 };
 const visible=card=>{
  if(!card.getClientRects().length)return false;
  const style=getComputedStyle(card);
  return style.display!=='none'&&style.visibility!=='hidden';
 };
 const jobs=[];
 const seen=new Set();
 for(const card of Array.from(document.querySelectorAll('.cust-job-tuple')).filter(visible).slice(0,50)){
  const anchor=card.querySelector('a.title, a[href*="job-listings"]');
  if(!anchor)continue;
  let url;
  try{
   url=new URL(anchor.href||anchor.getAttribute('href')||'',location.href);
   if(url.protocol!=='https:'||!(url.hostname==='naukri.com'||url.hostname.endsWith('.naukri.com'))||!url.pathname.toLowerCase().includes('job-listings'))continue;
   url.search='';url.hash='';
  }catch{continue;}
  const canonicalUrl=url.href;
  if(seen.has(canonicalUrl))continue;
  seen.add(canonicalUrl);
  const title=clean(anchor.innerText||anchor.textContent,200);
  if(!title)continue;
  const company=text(card,'.comp-name a, .comp-name, [class*="comp-name"]');
  const locationText=text(card,'.loc, [class*="location"]');
  const experience=text(card,'.exp, [class*="experience"]');
  const description=text(card,'.job-desc, [class*="job-desc"], [class*="description"]');
  const salary=text(card,'.sal, [class*="salary"]');
  const skills=Array.from(card.querySelectorAll('ul.tags-gt li, [class*="tag"]')).map(node=>clean(node.innerText||node.textContent,80)).filter(Boolean).slice(0,20).join(', ');
  const explicitDate=card.querySelector('time[datetime], [data-posted-date]')?.getAttribute('datetime')||card.querySelector('[data-posted-date]')?.getAttribute('data-posted-date')||'';
  const workMode=/\bremote\b/i.test(`${locationText} ${description}`)?'Remote':/\bhybrid\b/i.test(`${locationText} ${description}`)?'Hybrid':/\bon[- ]?site\b|\bin[- ]office\b/i.test(`${locationText} ${description}`)?'On-site':'';
  const sourceJobId=clean(card.getAttribute('data-job-id')||anchor.getAttribute('data-job-id')||canonicalUrl.match(/job-listings.*-(\d+)$/i)?.[1]||'',120);
  jobs.push({
   title,company,location:locationText,workMode,description,salary,requiredSkills:skills,
   experienceRequirements:experience,sourceJobId,url:canonicalUrl,applyUrl:'',
   ...( /^\d{4}-\d{2}-\d{2}$/.test(explicitDate)?{publishedAt:explicitDate}:{} )
  });
 }
 return {jobs,scanned:Math.min(document.querySelectorAll('.cust-job-tuple').length,50)};
}
function stopNaukriSearch(){
 const assistant=state.browserAssistant;
 assistant.importController?.abort();
 assistant.importController=null;
 const view=embeddedBrowserView();
 if(view&&assistant.status==='Opening Website')view.stop();
 assistant.importStatus='Stopped by user';
 setBrowserViewStatus('Search stopped');
 updateNaukriDiscoveryControls();
}
function updateNaukriDiscoveryControls(){
 const assistant=state.browserAssistant;
 const stopButton=document.getElementById('browser-stop-search');
 const importButton=document.getElementById('browser-import-visible-jobs');
 const searchButton=document.getElementById('browser-search-naukri');
 const status=document.getElementById('browser-discovery-status');
 const verifyButton=document.getElementById('browser-verify-apply-url');
 if(stopButton)stopButton.hidden=!assistant.importController&&assistant.status!=='Opening Website';
 if(importButton)importButton.disabled=Boolean(assistant.importController)||assistant.portal!=='Naukri'||assistant.status==='Opening Website';
 if(searchButton)searchButton.disabled=Boolean(assistant.importController);
 if(status)status.textContent=assistant.lastImportAt?`${assistant.importStatus} · Last import ${assistant.lastImportAt}`:assistant.importStatus;
 if(verifyButton){
  const job=state.jobs.find(item=>Number(item.id)===Number(assistant.savedJobId));
  let sameListing=false;
  try{const current=new URL(assistant.url),listing=new URL(job?.url||'');sameListing=current.origin===listing.origin&&current.pathname===listing.pathname;}catch{}
  verifyButton.hidden=!job||job.applicationUrlVerified||!assistant.url||sameListing;
 }
}
async function startNaukriSearch(){
 if(state.browserAssistant.importController)throw new Error('Stop the current visible-results import before starting another search.');
 const keyword=document.getElementById('browser-search-keyword')?.value||state.query;
 const locationValue=document.getElementById('browser-search-location')?.value??state.location;
 const experience=document.getElementById('browser-search-experience')?.value||state.browserAssistant.experience;
 state.query=keyword.trim();
 state.location=locationValue.trim();
 state.browserAssistant.experience=experience;
 state.browserAssistant.workMode=document.getElementById('browser-search-work-mode')?.value||'Any';
 localStorage.setItem('menix-job-filter-query',state.query);
 localStorage.setItem('menix-job-filter-location',state.location);
 localStorage.setItem('menix-career-stage',experience==='fresher'?'fresher':'all');
 const url=buildNaukriSearchUrl(state.query,state.location,experience);
 state.browserAssistant.portal='Naukri';
 state.browserAssistant.sessionConsent=true;
 state.browserAssistant.applicationInspectionEnabled=false;
 state.browserAssistant.importStatus=`Opening Naukri search for ${state.query}`;
 if(state.browserAssistant.autoApplyTab)state.browserAssistant.autoApplyTab.view=null;
 if(state.page!=='browser'){
  state.browserAssistant.suppressAutoApplyRestore=true;
  state.browserAssistant.url=url;
  go('browser');
 }else{
  navigateBrowserView(url);
 }
 toast(`Naukri search opened. Apply the ${state.browserAssistant.workMode} filter on Naukri if available.`);
}
async function importVisibleNaukriJobs(){
 const assistant=state.browserAssistant;
 const view=embeddedBrowserView();
 if(!view)throw new Error('Open Naukri search results in AutoApply before importing.');
 const pageUrl=validateBrowserUrl(view.getURL());
 const host=new URL(pageUrl).hostname.toLowerCase();
 if(host!=='naukri.com'&&!host.endsWith('.naukri.com'))throw new Error('Import is available only while viewing Naukri.');
 if(assistant.importController)throw new Error('A Naukri import is already running.');
 const controller=new AbortController();
 assistant.importController=controller;
 assistant.importStatus='Reading visible Naukri result cards…';
 updateNaukriDiscoveryControls();
 try{
  const extraction=await view.executeJavaScript(`(${extractVisibleNaukriResultsFromPage.toString()})()`,true);
  if(controller.signal.aborted)throw new DOMException('Import stopped by user.','AbortError');
  if(extraction?.error)throw new Error(extraction.error);
  if(!Array.isArray(extraction?.jobs)||extraction.jobs.length===0)throw new Error('No supported Naukri job cards are visible on this page. Search and load results on Naukri, then try again.');
  assistant.importStatus=`Importing ${extraction.jobs.length} visible job${extraction.jobs.length===1?'':'s'}…`;
  updateNaukriDiscoveryControls();
  const result=await api('/jobs/import/naukri-visible',{method:'POST',body:JSON.stringify(extraction.jobs),signal:controller.signal});
  if(controller.signal.aborted)throw new DOMException('Import stopped by user.','AbortError');
  state.jobs=[...(result.jobs||[]),...state.jobs.filter(job=>!(result.jobs||[]).some(saved=>Number(saved.id)===Number(job.id)))];
  assistant.lastImportAt=new Date().toLocaleTimeString();
  assistant.importStatus=`Imported ${result.imported}; updated ${result.updated}; rejected ${result.rejected}`;
  assistant.destinationCaptureArmed=false;
  if(result.diagnostics?.length)toast(`${assistant.importStatus}. ${result.diagnostics[0]}`);
  else toast(`${assistant.importStatus}.`);
  if(result.jobs?.length&&state.page==='dashboard')await render();
  return result;
 }catch(error){
  if(error.name==='AbortError'||controller.signal.aborted){
   assistant.importStatus='Import stopped by user';
   return null;
  }
  assistant.importStatus=`Import failed: ${error.message}`;
  throw error;
 }finally{
  if(assistant.importController===controller)assistant.importController=null;
  updateNaukriDiscoveryControls();
 }
}
async function verifyCurrentApplyDestination(){
 const assistant=state.browserAssistant;
 const view=embeddedBrowserView();
 const job=state.jobs.find(item=>Number(item.id)===Number(assistant.savedJobId));
 if(!view||!job)throw new Error('Open an imported job listing in AutoApply before verifying an application destination.');
 const url=validateBrowserUrl(view.getURL());
 if(url===job.url)throw new Error('Open the application page from the Naukri listing first; the listing itself is not an application destination.');
 const verified=await api(`/jobs/${job.id}/application-url`,{method:'PUT',body:JSON.stringify({url})});
 state.jobs=[verified,...state.jobs.filter(item=>Number(item.id)!==Number(verified.id))];
 assistant.importStatus=`Apply destination verified: ${new URL(url).hostname}`;
 updateNaukriDiscoveryControls();
 toast('Application destination verified from the page you opened. Apply will now open that page.');
}
async function handleEmbeddedBrowserPopup(rawUrl){
 let url;
 try{url=validateBrowserUrl(rawUrl);}catch(error){toast(`Popup destination was not accepted: ${error.message}`);return;}
 const job=state.jobs.find(item=>Number(item.id)===Number(state.browserAssistant.savedJobId));
 if(!job){toast('Save a job in AutoApply before attaching an Apply link.');return;}
 if(new URL(url).origin===new URL(job.url).origin&&new URL(url).pathname===new URL(job.url).pathname){
  toast('This popup is the original job listing, not a separate Apply destination.');
  return;
 }
 if(!window.confirm('Attach this link to the saved job as its Apply link?'))return;
 try{
  const verified=await api(`/jobs/${job.id}/application-url`,{method:'PUT',body:JSON.stringify({url})});
  state.jobs=[verified,...state.jobs.filter(item=>Number(item.id)!==Number(verified.id))];
  state.browserAssistant.importStatus=`Apply destination verified: ${new URL(url).hostname}`;
  renderBrowserDetails();
  toast('Apply link attached. The job card will open this verified destination.');
 }catch(error){toast(`Could not attach this Apply link: ${error.message}`);}
}
async function saveCurrentJob(){
 const view=embeddedBrowserView();
 if(!view)throw new Error('Open a job page in the AutoApply browser first.');
 const button=document.getElementById('browser-save-current-job');
 if(button?.hidden)throw new Error('Open an HTTPS job detail page before saving it.');
 if(button){button.disabled=true;button.textContent='Reading page…';}
 try{
  const url=validateBrowserUrl(view.getURL());
  const extracted=await view.executeJavaScript(`(${extractCurrentJobFromPage.toString()})()`,true);
  if(extracted?.error)throw new Error(extracted.error);
  if(!extracted||typeof extracted!=='object'||!String(extracted.title||'').trim())throw new Error('The current page did not provide recognizable job details.');
  if(validateBrowserUrl(view.getURL())!==url)throw new Error('The page changed while Menix was reading it. Try saving again.');
  const {structured,...jobDetails}=extracted;
  const confirmed=structured?jobDetails:await reviewVisibleJobDetails(jobDetails);
  if(!confirmed)return;
  const job={...jobDetails,...confirmed,url,applyUrl:'',attributionUrl:url,source:sourceForJobUrl(url)};
  try{
   const saved=await api('/jobs',{method:'POST',body:JSON.stringify(job)});
   state.jobs=[saved,...state.jobs.filter(item=>Number(item.id)!==Number(saved.id))];
   state.browserAssistant.savedJobId=saved.id;
   state.browserAssistant.match=null;
   state.browserAssistant.selectedTab='details';
   toast('Job saved. Its Apply button opens the listing until you confirm a separate application destination.');
   renderBrowserDetails();
  }catch(error){
   if(error.status===409){toast('This job URL is already saved.');return;}
   throw error;
  }
 }finally{
  if(button){button.disabled=false;button.textContent='Save this job';}
 }
}
function reviewVisibleJobDetails(job){
 return new Promise(resolve=>{
  const page=document.querySelector('.browser-assistant-page');
  if(!page){resolve(null);return;}
  const overlay=document.createElement('div');
  overlay.className='browser-save-review-overlay';
  overlay.innerHTML=`<form class="browser-save-review" role="dialog" aria-modal="true" aria-labelledby="browser-save-review-title"><h3 id="browser-save-review-title">Review job details</h3><p>These details came from visible text on the current page. Confirm or edit them before saving.</p><label>Job title<input name="title" required maxlength="200" value="${esc(job.title||'')}"></label><label>Company<input name="company" maxlength="200" value="${esc(job.company||'')}"></label><label>Location<input name="location" maxlength="250" value="${esc(job.location||'')}"></label><label>Description<textarea name="description" maxlength="4000" rows="5">${esc(job.description||'')}</textarea></label><div class="browser-save-review-actions"><button type="button" data-review-cancel>Cancel</button><button type="submit">Confirm and save</button></div></form>`;
  const form=overlay.querySelector('form');
  const finish=value=>{overlay.remove();resolve(value);};
  form.addEventListener('submit',event=>{
   event.preventDefault();
   finish(Object.fromEntries(new FormData(form)));
  });
  overlay.querySelector('[data-review-cancel]').addEventListener('click',()=>finish(null));
  page.append(overlay);
  form.querySelector('[name="title"]').focus();
 });
}
async function openTrustedExternal(rawUrl){
 let url;
 try{url=new URL(String(rawUrl||''));}catch{throw new Error('Enter a valid portal URL.');}
 if(url.protocol!=='https:'||url.username||url.password)throw new Error('Only public HTTPS portal links can be opened.');
 if(window.menixDesktop?.openExternalHelp)await window.menixDesktop.openExternalHelp(url.href);
 else window.open(url.href,'_blank','noopener,noreferrer');
}
function scoreLocal(job) {
 const p=state.profile||{}; const skills=String(p.skills||'').toLowerCase().split(/[,;|\n]/).map(s=>s.trim()).filter(Boolean);
 const req=String(job.requiredSkills||'').toLowerCase().split(/[,;|\n]/).map(s=>s.trim()).filter(Boolean);
 const skillScore=req.length ? Math.round(req.filter(s=>skills.some(x=>canonicalSkill(x)===canonicalSkill(s))).length/req.length*65) : 25;
 const title=String(p.preferredTitles||'').toLowerCase().split(/[,;\n]/).map(s=>s.trim()).filter(Boolean);
 const titleScore=title.some(t=>String(job.title).toLowerCase().includes(t)||t.includes(String(job.title).toLowerCase()))?25:0;
 const loc=String(p.preferredLocations||'').toLowerCase().split(/[,;\n]/).map(s=>s.trim()).filter(Boolean);
 const locScore=loc.some(l=>String(job.location||'').toLowerCase().includes(l)||String(job.location||'').toLowerCase().includes('remote')&&l==='remote')?10:0;
 const total=Math.min(100,skillScore+titleScore+locScore);if(state.careerStage==='fresher'&&careerBucket(job)==='senior')return Math.min(total,25);if(state.careerStage==='fresher'&&careerBucket(job)==='unspecified')return Math.min(total,55);return total;
}
function browseNaukriManually(){
 state.browserAssistant.portal='Naukri';
 state.browserAssistant.sessionConsent=true;
 state.browserAssistant.applicationInspectionEnabled=false;
 if(state.browserAssistant.autoApplyTab)state.browserAssistant.autoApplyTab.view=null;
 const url='https://www.naukri.com/mnjuser/recommendedjobs?src=gnbOpportunities';
 if(state.page!=='browser'){
  state.browserAssistant.suppressAutoApplyRestore=true;
  state.browserAssistant.url=url;
  go('browser');
  return;
 }
 renderBrowserAssistant();
 navigateBrowserView(url);
}
function renderJobs() {
 const sourceNames=[...new Set([...(state.sources||[]).map(source=>source.source),...state.jobs.map(job=>job.source)].filter(Boolean))].sort((a,b)=>a.localeCompare(b));
 const sourceOptions=sourceNames.map(source=>`<option value="${esc(source)}" ${state.sourceFilter.toLowerCase()===source.toLowerCase()?'selected':''}>${esc(source)}</option>`).join('');
 const jobs=state.jobs.filter(j=>(state.sourceFilter==='all'||String(j.source||'').toLowerCase()===state.sourceFilter.toLowerCase())&&stageAllows(j)&&Number(j.score??0)>=state.jobMinScore&&`${j.title} ${j.company} ${j.location} ${j.workMode} ${j.requiredSkills}`.toLowerCase().includes(state.query.toLowerCase())&&(!state.location||`${j.location||''} ${j.workMode||''}`.toLowerCase().includes(state.location.toLowerCase()))).map(j=>({...j,matchScore:j.score??scoreLocal(j)})).sort((a,b)=>b.matchScore-a.matchScore);
 content.innerHTML=`<section class="jobs-page-shell"><div class="jobs-page-heading"><div><span class="eyebrow">JOB DISCOVERY</span><h2>New Jobs</h2><p class="muted small">Review verified listings from enabled employer-board feeds and jobs you explicitly saved from browser pages. Portal search and result collection remain user-driven.</p></div><button class="button button-primary" id="add-job">＋ Add job</button></div><div class="jobs-filters card"><div class="toolbar"><input class="search-input" id="job-query" aria-label="Filter jobs" placeholder="Filter by title, company, skill…" value="${esc(state.query)}"><input class="search-input location-select" id="job-location" aria-label="Filter by location" placeholder="Remote, On-site, Hybrid or city…" value="${esc(state.location)}"><select class="search-input" id="job-source"><option value="all" ${state.sourceFilter==='all'?'selected':''}>All sources</option>${sourceOptions}</select><select class="search-input stage-select" id="career-stage"><option value="fresher" ${state.careerStage==='fresher'?'selected':''}>Fresher / entry-level first</option><option value="all" ${state.careerStage==='all'?'selected':''}>All experience levels</option></select><select class="search-input" id="freshness"><option value="24h" ${state.freshness==='24h'?'selected':''}>Last 24 hours</option><option value="3d" ${state.freshness==='3d'?'selected':''}>Last 3 days</option><option value="7d" ${state.freshness==='7d'?'selected':''}>Last 7 days</option><option value="all" ${state.freshness==='all'?'selected':''}>All available dates</option></select><select class="search-input" id="sort-jobs"><option value="match">Sort: match</option><option value="posted">Sort: posting date</option><option value="discovered">Sort: discovered</option></select></div><div class="jobs-filter-bottom"><label class="small">Minimum match <input id="min-score" type="range" min="0" max="100" step="5" value="${state.jobMinScore}"><span id="min-score-value">${state.jobMinScore}%</span></label><button class="button" id="browse-naukri">Browse Naukri</button></div></div><div class="board-search"><div><strong>Job source</strong><small>Configured employer-board feeds sync through their public APIs; portal browsing and visible-page imports remain manual.</small></div><div class="actions"><button class="button button-primary" id="browse-naukri-secondary">Open Naukri recommendations</button></div></div><div class="jobs-results-heading"><h3>Matching jobs <span>${jobs.length}</span></h3><button class="text-button" data-go="applications">Applied jobs&nbsp; →</button></div><div id="job-list" class="job-grid all-job-grid">${jobs.length?jobs.map(jobRow).join(''):'<div class="card empty jobs-empty">No jobs from the selected sources match these filters. Check freshness, source settings, and your search terms.</div>'}</div><div class="footer-note">Menix syncs only configured public employer-board APIs. It does not scrape job-site search results, bypass site controls, or submit applications.</div></section><div id="job-detail"></div>`;
 document.getElementById('job-query').addEventListener('input',e=>{state.query=e.target.value;renderJobs();const el=document.getElementById('job-query');el.focus();el.setSelectionRange(el.value.length,el.value.length);});
 document.getElementById('job-source').addEventListener('change',async e=>{state.sourceFilter=e.target.value;localStorage.setItem('menix-job-source-filter',state.sourceFilter);await render().catch(showError);});
 document.getElementById('career-stage').addEventListener('change',e=>{state.careerStage=e.target.value;localStorage.setItem('menix-career-stage',state.careerStage);state.selectedJob=null;renderJobs();});
 document.getElementById('job-location').addEventListener('change',async e=>{state.location=e.target.value.trim();await render().catch(showError);});
 document.getElementById('freshness').addEventListener('change',async e=>{state.freshness=e.target.value;localStorage.setItem('menix-freshness',state.freshness);await render().catch(showError);});
 document.getElementById('min-score').addEventListener('input',e=>{state.jobMinScore=Number(e.target.value);document.getElementById('min-score-value').textContent=`${state.jobMinScore}%`;});
 document.getElementById('min-score').addEventListener('change',async e=>{state.jobMinScore=Number(e.target.value);localStorage.setItem('menix-job-min-score',String(state.jobMinScore));await render().catch(showError);});
 document.getElementById('sort-jobs').addEventListener('change',async e=>{state.sort=e.target.value;await render().catch(showError);});
 document.getElementById('browse-naukri').addEventListener('click',browseNaukriManually);
 document.getElementById('browse-naukri-secondary').addEventListener('click',browseNaukriManually);
 document.getElementById('add-job').addEventListener('click',showAddJob);bindJobButtons();
}
function bindJobButtons(){
 document.querySelectorAll('[data-job]').forEach(b=>b.addEventListener('click',()=>openJobDescription(Number(b.dataset.job))));
 document.querySelectorAll('[data-apply-job]').forEach(b=>b.addEventListener('click',()=>applyToJob(Number(b.dataset.applyJob),b).catch(e=>toast(e.message))));
 document.querySelectorAll('[data-decline-job]').forEach(b=>b.addEventListener('click',()=>declineJob(Number(b.dataset.declineJob),b).catch(e=>toast(e.message))));
}
function openJobDescription(id){
 if(!Number.isSafeInteger(id)||id<1){toast('This job has an invalid identifier.');return;}
 state.selectedJob=id;
 state.detailReturnPage=state.page==='job-detail'?(state.detailReturnPage||'jobs'):state.page;
 go('job-detail');
}
function renderJobDescriptionPage(){
 if(!state.selectedJob){go(state.detailReturnPage||'jobs');return;}
 content.innerHTML=`<section class="job-description-page"><div class="jobs-page-heading"><div><span class="eyebrow">JOB OPPORTUNITY</span><h2>Job description</h2><p class="muted small">Review the full listing, match details, and original application options.</p></div><button class="button" id="job-detail-back" type="button">← Back</button></div><div id="job-detail"><div class="loading">Loading job description…</div></div></section>`;
 document.getElementById('job-detail-back').addEventListener('click',()=>window.history.back());
 showJobDetail(state.selectedJob).catch(showError);
}
async function applyToJob(id,button){
 if(!Number.isSafeInteger(Number(id))||Number(id)<1)throw new Error('This job has an invalid identifier.');
 if(button)button.disabled=true;
 try{
  const detail=await api(`/jobs/${id}`);
  if(!detail.applicationUrlVerified){
   openImportedJobListing(detail);
   toast('The application destination is not verified. Menix opened the original listing; review it and choose Apply there.');
   return;
  }
  const applicationUrl=detail.applyUrl||detail.url;
  let parsed;
  try{parsed=new URL(String(applicationUrl||''));}catch{throw new Error('This job does not have a valid application link.');}
  if(parsed.protocol!=='https:')throw new Error('This job does not have a secure HTTPS application link.');
  const existing=state.applications.find(application=>Number(application.jobId)===Number(id));
  if(existing&&String(existing.status||'').toUpperCase()==='SUBMITTED'){toast('This job is already marked submitted.');return;}
  if(existing&&!['SHORTLISTED','DISCOVERED','WITHDRAWN','NOT_COMPLETED_YET'].includes(String(existing.status||'').toUpperCase())){toast('This job is already in your application tracker. Update it there to avoid a duplicate application.');return;}
  const opened=await startBrowserAssistantApplication({...detail,applyUrl:applicationUrl});
  if(opened)toast('Application opened in AutoApply. Review every answer; submission requires your confirmation.');
 }
 finally{if(button)button.disabled=false;}
}
async function declineJob(id,button){
 if(button)button.disabled=true;
 try{await api(`/jobs/${id}/decline`,{method:'POST'});toast('Job declined and removed from recommendations. You can restore it from the tracker.');await render();}
 finally{if(button)button.disabled=false;}
}
function showAddJob(){
 const area=document.getElementById('job-detail');
 area.innerHTML=`<div class="card job-detail"><h2>Save a job or company careers page</h2><p class="muted small">Use a public HTTPS listing or careers URL. Menix stores it locally and opens the original site for manual review and application.</p><form id="new-job"><div class="form-grid">${field('Job title (optional)','title','','Java Backend Developer')}${field('Company (optional)','company','','Company name')}${field('Location (optional)','location','','Remote / Jaipur / Bengaluru')}${field('Job or careers URL','url','','https://careers.example.com/jobs/...','url')}${field('Source / attribution URL (optional)','attributionUrl','','https://...','url')}${field('Required skills (optional)','requiredSkills','','Java, Spring Boot, SQL')}${field('Employment type (optional)','employmentType','','Full-time / Internship')}${field('Description (optional)','description','','Paste verified job details','textarea','full')}</div><div class="actions"><button class="button button-primary">Save job</button><button type="button" class="button" id="cancel-add">Cancel</button></div></form></div>`;
 document.getElementById('url').required=true;
 document.getElementById('cancel-add').onclick=()=>area.innerHTML='';
 document.getElementById('new-job').onsubmit=async e=>{e.preventDefault();const d=Object.fromEntries(new FormData(e.currentTarget));d.source='External Link';try{const j=await api('/jobs',{method:'POST',body:JSON.stringify(d)});state.selectedJob=j.id;toast('Job URL saved');await renderJobs();}catch(err){toast(err.message)}};
}
async function showJobDetail(id){
 const j=await api(`/jobs/${id}`);const m=await api(`/jobs/${id}/match`);const area=document.getElementById('job-detail');if(!area)throw new Error('The job description view is not available.');
 const missing=(m.missingSkills||[]), required=String(j.requiredSkills||'').trim();
 const descriptionHeading=j.importMethod==='BROWSER_SEARCH_RESULTS'?'Naukri result description excerpt':'Source job description';
 area.innerHTML=`<div class="card job-detail"><div class="detail-top"><div><div class="eyebrow">YOUR MATCH</div><h2>${esc(j.title)}</h2><div class="muted">${esc(j.company)} ? ${esc(j.location||'Location not specified')} ? ${esc(j.source||'Manual listing')}</div></div><div><div class="score score-large">${m.score}%</div><div class="tag ${m.score>=80?'good':''}">${m.score>=80?'Strong match':m.score>=60?'Good potential':'Needs review'}</div></div></div><progress class="match-progress" max="100" value="${Math.max(0, Math.min(100, Number(m.score) || 0))}" aria-label="Match score ${m.score}%">${m.score}%</progress><div class="muted small">${esc(m.explanation)}</div><div class="section-head"><h2>Matching skills</h2></div>${(m.matchedSkills||[]).map(s=>`<span class="tag good">${esc(s)}</span>`).join('')||'<span class="muted small">No matching skills listed.</span>'}<div class="section-head"><h2>Skills to check</h2></div>${missing.map(s=>`<span class="tag warn">${esc(s)}</span>`).join('')||(required?'<span class="muted small">No gaps found in the listed skills.</span>':'<span class="muted small">This listing does not specify required skills.</span>')}<div class="section-head"><h2>${descriptionHeading}</h2></div><p class="job-description">${esc(j.description||'No description supplied.')}</p><div class="actions">${j.url||j.applyUrl?`<button class="button button-primary" id="open-job">${j.applicationUrlVerified?'Apply with AutoApply':'Open original listing'}</button>${j.url?'<button class="button" id="view-source">Open original listing</button>':''}`:''}<button class="button" id="draft-job">Draft cover letter</button>${state.aiStatus.enabled?'<button class="button" id="ai-match">AI analyze fit</button>':''}</div>${!j.applicationUrlVerified?'<div class="footer-note">The application destination has not been verified. Menix will open the original listing; after you open Apply there, confirm the resulting page in AutoApply.</div>':''}${state.aiStatus.enabled?`<div class="footer-note">Optional Gemini analysis (${esc(state.aiStatus.model)}). It sends the job description and a minimized profile containing skills, target roles, experience level, and preferred locations after you confirm. Name, contact details, and resume are not sent.</div>`:'<div class="footer-note">Gemini analysis is disabled. Set GEMINI_API_KEY in the backend environment to enable optional AI matching.</div>'}<div id="ai-match-area"></div><div id="draft-area"></div><div class="footer-note">Deterministic match estimate is shown by default. AI analysis is optional and cached for the current job and profile.</div></div>`;
 document.getElementById('ai-match')?.addEventListener('click',async event=>{if(!window.confirm('Send this job description and your minimized skills, target roles, experience level, and preferred locations to Google Gemini for matching? Your name, contact details, and resume are not sent.'))return;const button=event.currentTarget,box=document.getElementById('ai-match-area');button.disabled=true;box.innerHTML='<p class="muted">Gemini is analyzing job fit…</p>';try{const r=await api(`/jobs/${id}/ai-match`,{method:'POST',body:JSON.stringify({consent:true})});box.innerHTML=`<div class="notice"><strong>Combined match: ${r.score}%</strong> <span class="muted small">(Gemini ${r.aiScore}% · deterministic ${r.deterministicScore}%)${r.cached?' · cached':''}</span><p>${esc(r.combinedExplanation)}</p><strong>Matched skills</strong><div>${(r.matchedSkills||[]).map(s=>`<span class="tag good">${esc(s)}</span>`).join('')||'<span class="muted small">None identified</span>'}</div><strong>Missing required skills</strong><div>${(r.missingRequiredSkills||[]).map(s=>`<span class="tag warn">${esc(s)}</span>`).join('')||'<span class="muted small">None identified</span>'}</div><p>Seniority: ${esc(r.seniorityCompatibility)} · Geographic eligibility: ${esc(r.geographicEligibility)}</p><p>${esc(r.workAuthorizationUncertainty)}</p>${(r.warnings||[]).map(w=>`<p class="muted small">${esc(w)}</p>`).join('')}<div class="muted small">Recommendation: ${esc(r.recommendation)} · ${r.cached?'Cached analysis':'Analysis saved for this job and profile'}</div></div>`;toast('AI match saved');}catch(e){box.innerHTML=`<div class="notice"><strong>AI analysis unavailable</strong><p>${esc(e.message)} Deterministic match remains available.</p></div>`;}finally{button.disabled=false;}});
 document.getElementById('draft-job').onclick=async()=>{const box=document.getElementById('draft-area');box.innerHTML='<p class="muted">Preparing draft...</p>';try{const d=await api('/drafts/cover-letter',{method:'POST',body:JSON.stringify({jobId:id})});box.innerHTML=`<div class="field cover-letter-field"><label>Editable cover-letter draft ? review before use</label><textarea id="draft-text" class="cover-letter-textarea">${esc(d.text)}</textarea></div><div class="actions"><button class="button" id="copy-draft">Select draft text</button></div>`;document.getElementById('copy-draft').onclick=()=>{document.getElementById('draft-text').select();toast('Draft selected ? copy with Ctrl+C');};}catch(e){box.innerHTML=`<p class="error">${esc(e.message)}</p>`}};
 document.getElementById('open-job')?.addEventListener('click',()=>applyToJob(id,document.getElementById('open-job')).catch(e=>toast(e.message)));document.getElementById('view-source')?.addEventListener('click',()=>openTrustedExternal(j.url).catch(e=>toast(e.message)));
}
function setApplicationAssistStatus(message){
 const status=document.getElementById('application-assist-status');
 if(status)status.textContent=message;
}

function activeApplicationTab(){
 return state.page==='browser'?state.browserAssistant.autoApplyTab:null;
}
function setAutoApplyStatus(tab,status,message){
 if(tab)tab.status=status;
 state.autoApply.status=status;
 if(tab?.applicationId){
  if(status==='Submitted')clearAutoApplyContext();
  else persistAutoApplyContext(tab);
 }
 setApplicationAssistStatus(message||status);
 const badge=document.getElementById('application-status-badge');
 if(badge)badge.textContent=status;
 if(tab?.sessionId){
  const backendStatus=status.toUpperCase().replace(/\s+/g,'_');
  const diagnostic=status==='Failed'?String(message||'').slice(0,1000):'';
  const detected=tab.fields?.length||0;
  const filled=tab.fieldsFilled||0;
  tab.sessionUpdate=(tab.sessionUpdate||Promise.resolve()).then(()=>api(`/autofill/sessions/${tab.sessionId}`,{
   method:'PUT',body:JSON.stringify({status:backendStatus,fieldsDetected:detected,fieldsFilled:filled,diagnostic})
  })).catch(error=>{toast(`AutoApply status could not be saved: ${error.message}`);});
 }
}
function applicationQuestion(field){
 return [field.label,field.context].filter(Boolean).join(' — ').trim()||field.name||'Unlabeled question';
}
function applicationProposal(field){
 const profileMapping=window.MenixAutofillEngine.mapField(field,state.profile||{});
 const memory=window.MenixSmartApplyMemory.matchQuestion(applicationQuestion(field),state.smartApplyAnswers,state.smartApplyPreferences);
 if(field.sensitive){
  const label=`${field.label||''} ${field.context||''}`;
  const profile=state.profile||{};
  const salaryExpectation=/\b(expected|expectation|desired|minimum|target)\b/i.test(label)&&!/\b(history|previous|past|current)\b/i.test(label)&&/salary|compensation/i.test(label);
  const sensitiveProfileValue=salaryExpectation?profile.minimumSalary:/sponsor/i.test(label)?profile.sponsorshipAnswer:/work authorization|authorized to work|visa/i.test(label)?profile.workAuthorization:'';
  if(sensitiveProfileValue)return {value:sensitiveProfileValue,source:'Profile',confidence:1,status:'confirmation-required',reason:'Explicitly saved sensitive fact; confirm its accuracy before applying it.'};
  if(memory.answer)return {value:memory.answer,source:memory.source,confidence:memory.confidence,memoryId:memory.memoryId,status:'confirmation-required',reason:memory.reason};
  return {value:'',source:'Manual',confidence:0,status:'confirmation-required',reason:'Sensitive or legally significant question must be answered and confirmed by you.'};
 }
 if(memory.status==='conflict')return {value:'',source:memory.source,confidence:memory.confidence,status:'conflict',reason:memory.reason};
 if(memory.answer&&memory.confidence>profileMapping.confidence)return {value:memory.answer,source:memory.source,confidence:memory.confidence,memoryId:memory.memoryId,status:memory.status,reason:memory.reason};
 if(profileMapping.status==='mapped'&&profileMapping.confidence<Number(state.smartApplyPreferences.mediumConfidenceThreshold||0.55))return {value:'',source:'',confidence:profileMapping.confidence,status:'blank',reason:'Profile match confidence is below the configured suggestion threshold; answer manually or add an exact approved answer.'};
 if(profileMapping.status==='mapped')return {value:profileMapping.value,source:'Profile',confidence:profileMapping.confidence,status:profileMapping.confidence>=state.smartApplyPreferences.highConfidenceThreshold?'profile-match':'suggestion',reason:profileMapping.reason};
 if(memory.answer)return {value:memory.answer,source:memory.source,confidence:memory.confidence,memoryId:memory.memoryId,status:memory.status,reason:memory.reason};
 if(memory.status==='confirmation-required')return {value:'',source:'Manual',confidence:0,status:'confirmation-required',reason:memory.reason};
 if(profileMapping.reason)return {value:'',source:'',confidence:Math.max(profileMapping.confidence||0,memory.confidence||0),status:profileMapping.status,reason:[profileMapping.reason,memory.reason].filter(Boolean).join(' ')};
 return {value:'',source:'',confidence:0,status:memory.status,reason:memory.reason};
}
function renderAutoApplyInspection(tab,scan){
 const panel=document.getElementById('application-field-review');
 if(!panel)return;
 if(scan.blocked){
  tab.reviewed=false;
  tab.fields=[];
  tab.requiredMissing=0;
  tab.validationIssues=0;
  tab.submitCandidates=[];
  const submit=document.getElementById('application-submit-button');
  if(submit)submit.disabled=true;
  panel.innerHTML=`<div class="notice"><strong>Manual action required</strong><p>${esc(scan.reason)}</p></div>`;
  return;
 }
 const fields=(scan.fields||[]).map(field=>{
  const proposal=applicationProposal(field);
  if(tab.dismissedSuggestions?.has(field.signature)||tab.rejectedSuggestions?.has(field.signature)){
   proposal.value='';proposal.source='';proposal.status='blank';proposal.reason='This suggestion was cleared or rejected for the current form.';
  }
  return {...field,proposal};
 });
 const unsupported=scan.unsupported||[];
 const uploads=scan.uploads||[];
 const requiredMissing=fields.filter(field=>field.required&&field.blank).length+unsupported.filter(field=>field.required).length+uploads.filter(field=>field.required&&field.blank).length;
 const validationIssues=fields.filter(field=>field.invalid).length+uploads.filter(field=>field.invalid).length;
 const requiredFields=[...fields,...unsupported,...uploads].filter(field=>field.required);
 const requiredCompleted=fields.filter(field=>field.required&&!field.blank&&!field.invalid).length+uploads.filter(field=>field.required&&!field.blank&&!field.invalid).length;
 const progress=requiredFields.length?Math.round(requiredCompleted/requiredFields.length*100):0;
 const eligibleSubmit=(scan.submitCandidates||[]).filter(candidate=>/\b(submit|apply now|send application)\b/i.test(candidate.label));
 tab.fields=fields;
 tab.unsupported=unsupported;
 tab.uploads=uploads;
 tab.submitCandidates=eligibleSubmit;
 tab.reviewed=false;
 tab.requiredMissing=requiredMissing;
 tab.validationIssues=validationIssues;
 tab.scanBlocked=false;
 const rows=fields.map(field=>{
  const proposal=field.proposal;
  const trackingSignature=field.stableSignature||field.signature;
  const wasAutoFilled=tab.autofilledSignatures?.has(field.signature)||tab.autofilledSignatures?.has(trackingSignature);
  const status=field.invalid?'Invalid':!field.blank?(wasAutoFilled?'Autofilled':'Answered'):field.sensitive?(proposal.value?'Needs confirmation':'Manual — sensitive'):field.aiSuggestion||proposal.value?'Suggested':field.required?'Blank — required':'Blank — optional';
  const reason=field.invalid?'The current value fails the employer control’s validation.':!field.blank?'The employer form currently contains a value.':field.sensitive?'Sensitive or legally significant questions must be answered and confirmed by you.':proposal.reason;
  const proposalValue=field.aiSuggestion||proposal.value||'';
  const savedOrigin=tab.answerOrigins?.get(field.signature)||tab.answerOrigins?.get(trackingSignature);
  const originMatches=!field.blank&&savedOrigin&&String(savedOrigin.value)===String(field.value);
  const source=!field.blank?(originMatches?savedOrigin.source:'Manual'):field.aiSuggestion?'AI Suggested':proposal.source;
  const confidence=!field.blank?(originMatches?`${Math.round(savedOrigin.confidence*100)}%`:'Not measured'):field.aiSuggestion?'Not measured':`${Math.round((proposal.confidence||0)*100)}%`;
  const canSuggest=field.blank&&!field.invalid;
  const currentAnswer=!field.blank?`<div class="application-current-answer">${esc(field.value)}</div>`:'';
  return `<article class="autofill-field-row ${field.required&&field.blank?'is-required-blank':''} ${field.invalid?'is-invalid':''}"><div class="autofill-field-heading"><strong>${esc(applicationQuestion(field))}${field.required?' <b class="required-marker" aria-label="Required">*</b>':''}</strong><span class="answer-state">${esc(status)}</span></div><small>${esc(field.type)} · Source: ${esc(source||'None')} · Confidence: ${esc(confidence)}</small>${currentAnswer}${reason?`<p class="muted small">${esc(reason)}</p>`:''}${canSuggest?`<textarea data-answer-value="${esc(field.id)}" rows="2" aria-label="Proposed answer for ${esc(applicationQuestion(field))}" placeholder="${field.required?'Required answer is still blank. Review or enter a truthful answer.':'Optional answer; leave blank if not applicable.'}">${esc(proposalValue)}</textarea><label class="small memory-approval"><input type="checkbox" data-answer-save="${esc(field.id)}" ${state.smartApplyPreferences.learningEnabled?'checked':''} ${state.smartApplyPreferences.learningEnabled?'':'disabled'}> Save this approved answer to Smart Apply Memory</label><div class="autofill-field-actions"><button type="button" class="button" data-answer-accept="${esc(field.id)}">Use &amp; approve</button><button type="button" class="button button-quiet" data-answer-reject="${esc(field.id)}">Reject suggestion</button></div>`:''}</article>`;
 }).join('');
 const unsupportedRows=unsupported.map(field=>`<article class="autofill-field-row is-unsupported"><div class="autofill-field-heading"><strong>${esc(field.label||'Unlabeled custom control')}${field.required?' <b class="required-marker" aria-label="Required">*</b>':''}</strong><span class="answer-state">Unsupported</span></div><small>${esc(field.type)} · ${esc(field.reason)}</small></article>`).join('');
 const uploadRows=uploads.map(upload=>`<article class="autofill-field-row"><div class="autofill-field-heading"><strong>${esc(upload.label||'File upload')}${upload.required?' <b class="required-marker" aria-label="Required">*</b>':''}</strong><span class="answer-state">${upload.invalid?'Invalid':upload.blank?'Blank — file required':'Answered'}</span></div><small>File upload · Source: Manual · Confidence: Not measured</small><p class="muted small">${upload.blank?'Choose the file yourself through the employer’s native file chooser. Menix never selects or uploads it automatically.':'A file is selected in the employer form; verify that it is the intended document.'}</p></article>`).join('');
 const resumeUploads=tab.uploads.filter(upload=>/\b(resume|cv|curriculum vitae)\b/i.test(`${upload.label} ${upload.accept}`));
 tab.resumeUploadCandidates=resumeUploads;
 const uploadMessage=resumeUploads.length===1?'A resume upload control was identified. Choose the file using the employer’s native picker.':tab.uploads.length?'File controls were detected, but Menix could not identify a unique resume control. Use the employer page manually.':'No visible resume upload control detected on this page.';
 panel.innerHTML=`<div class="autoapply-scan-summary"><div class="autoapply-progress-heading"><strong>Application Progress</strong><span>${progress}%</span></div><div class="autoapply-progress-track" role="progressbar" aria-label="Required application fields completed" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress}"><span style="width:${progress}%"></span></div><span>${requiredCompleted} of ${requiredFields.length} required fields completed</span><small>${fields.length} supported controls · ${unsupported.length} unsupported</small></div><div class="autofill-list">${rows||'<p class="muted">No supported form controls found. Complete the page manually or continue to the next step and scan again.</p>'}${uploadRows}${unsupportedRows}</div><p class="muted small">${esc(uploadMessage)}${tab.policy?.notice?` ${esc(tab.policy.notice)}`:''}</p>${scan.inaccessibleFrames?'<p class="muted small">Some cross-origin frames could not be inspected; complete those fields manually.</p>':''}${scan.submitCandidates?.length>eligibleSubmit.length?'<p class="muted small">A submit-like control could not be identified confidently. Use the employer page manually.</p>':''}`;
 const submit=document.getElementById('application-submit-button');
 if(submit)submit.disabled=true;
 const upload=document.getElementById('application-upload-resume');
 if(upload)upload.disabled=resumeUploads.length!==1;
 panel.querySelectorAll('[data-answer-accept]').forEach(button=>button.addEventListener('click',()=>acceptAutoApplyAnswer(tab,button.dataset.answerAccept)));
 panel.querySelectorAll('[data-answer-reject]').forEach(button=>button.addEventListener('click',()=>rejectAutoApplyAnswer(tab,button.dataset.answerReject)));
}
function answerCategory(question){
 const value=String(question||'').toLowerCase();
 if(/education|degree|graduat|university|college/.test(value))return 'Education';
 if(/skill|technology|programming/.test(value))return 'Skills';
 if(/project/.test(value))return 'Projects';
 if(/experience|employment|internship|work history/.test(value))return 'Experience';
 if(/salary|compensation|notice period|availability/.test(value))return 'Employment';
 if(/location|city|country/.test(value))return 'Location';
 if(/email|phone|name|linkedin|github|portfolio/.test(value))return 'Contact';
 return 'General';
}
function clearAutoApplySuggestions(tab){
 if(!tab)return;
 for(const field of tab.fields||[])delete field.aiSuggestion;
 tab.dismissedSuggestions=new Set((tab.fields||[]).filter(field=>field.blank).map(field=>field.signature));
 renderAutoApplyInspection(tab,{blocked:false,fields:tab.fields||[],unsupported:tab.unsupported||[],uploads:tab.uploads||[],submitCandidates:tab.submitCandidates||[]});
 setApplicationAssistStatus('Current suggestions cleared. Saved profile and approved memory remain unchanged.');
}
function rejectAutoApplyAnswer(tab,id){
 const field=tab?.fields?.find(item=>item.id===id);
 if(!field)return;
 (tab.rejectedSuggestions ||= new Set()).add(field.signature);
 delete field.aiSuggestion;
 renderAutoApplyInspection(tab,{blocked:false,fields:tab.fields||[],unsupported:tab.unsupported||[],uploads:tab.uploads||[],submitCandidates:tab.submitCandidates||[]});
 setApplicationAssistStatus('Suggestion rejected for this form. It was not saved to memory.');
}
async function acceptAutoApplyAnswer(tab,id){
 const field=tab?.fields?.find(item=>item.id===id);
 const editor=document.querySelector(`[data-answer-value="${CSS.escape(id)}"]`);
 if(!field||!editor)return;
 const rawValue=editor.value.trim();
 if(!rawValue){toast('Enter a truthful answer or leave the field blank.');return;}
 if(field.sensitive&&!window.confirm('This is a sensitive or legally significant question. Confirm that this answer is accurate and explicitly approved by you before using it.'))return;
 try{
  const fresh=await tab.view.executeJavaScript(window.MenixAutofillEngine.scanScript());
  if(fresh.blocked)throw new Error(fresh.reason);
  const match=(fresh.fields||[]).find(item=>item.signature===field.signature);
  if(!match||!match.blank)throw new Error('The employer field changed or already has a value. Rescan before continuing.');
  const checkboxValue=field.type==='checkbox'?rawValue.toLowerCase()==='true'||rawValue.toLowerCase()==='yes':rawValue;
  if(field.type==='checkbox'&&!checkboxValue)throw new Error('A checkbox suggestion must match a saved skill; complete this control manually.');
  const result=await tab.view.executeJavaScript(window.MenixAutofillEngine.fillScript([{id:match.id,value:checkboxValue}]));
  if(!result?.filled)throw new Error('The answer could not be applied. Complete the field manually.');
  tab.autofilledSignatures ||= new Set();
  tab.autofilledSignatures.add(field.signature);
  tab.answerOrigins ||= new Map();
  tab.answerOrigins.set(field.signature,{value:checkboxValue,source:field.aiSuggestion?'AI Suggested':field.proposal?.source||'Manual',confidence:field.proposal?.confidence||0});
  const remember=document.querySelector(`[data-answer-save="${CSS.escape(id)}"]`)?.checked&&state.smartApplyPreferences.learningEnabled;
  if(remember){
   const payload={question:applicationQuestion(field),answer:rawValue,category:answerCategory(applicationQuestion(field)),source:field.aiSuggestion?'AI Suggested':field.proposal?.source||'Manual'};
   const saved=field.proposal?.memoryId?await api(`/smart-apply/answers/${field.proposal.memoryId}`,{method:'PUT',body:JSON.stringify({...payload,excluded:false})}):await api('/smart-apply/answers',{method:'POST',body:JSON.stringify(payload)});
   if(!state.smartApplyAnswers.some(item=>item.id===saved.id))state.smartApplyAnswers.push(saved);
   else state.smartApplyAnswers=state.smartApplyAnswers.map(item=>item.id===saved.id?saved:item);
  }
  const memoryId=field.proposal?.memoryId;
  if(memoryId)await api(`/smart-apply/answers/${memoryId}/used`,{method:'POST'}).catch(error=>toast(`Answer used; memory usage could not be recorded: ${error.message}`));
  await scanAutoApplyPage(tab);
  setApplicationAssistStatus(remember?'Answer used and saved as approved memory.':'Answer used after your approval; it was not added to memory.');
 }catch(error){toast(error.message);setApplicationAssistStatus(error.message);}
}
async function suggestMissingAnswers(){
 const tab=activeApplicationTab();
 if(!tab?.view){toast('Open an application page and detect its fields first.');return;}
 try{
  const scan=await scanAutoApplyPage(tab);
  if(scan.blocked){setAutoApplyStatus(tab,'Failed',scan.reason);return;}
  const eligible=(scan.fields||[]).filter(field=>field.blank&&!field.sensitive&&field.label&&!applicationProposal(field).value&&field.type!=='checkbox');
  if(!eligible.length){setApplicationAssistStatus('No eligible unanswered questions need an AI draft. Sensitive and unsupported questions remain manual.');return;}
  if(!state.aiStatus.enabled){setApplicationAssistStatus('AI suggestions are unavailable because Gemini is not configured. Deterministic profile and approved-answer matching remain available.');return;}
  const resumeName=document.getElementById('application-resume')?.value;
  if(!resumeName){setApplicationAssistStatus('Select a saved resume before requesting an AI draft. Resume text may contain personal information.');return;}
  if(!window.confirm('Send the selected resume text, relevant saved job details, and these non-sensitive unanswered questions to the configured Gemini provider? Drafts remain suggestions and will not be filled automatically.'))return;
  const requestFields=eligible.slice(0,80).map(field=>({id:field.id,type:field.type,label:field.label,name:field.name,context:field.context,placeholder:field.placeholder,required:field.required,maxLength:field.maxLength,options:field.options}));
  const drafts=await api('/resumes/application-draft',{method:'POST',body:JSON.stringify({storageName:resumeName,jobId:tab.jobId,consent:true,fields:requestFields})});
  const current=await tab.view.executeJavaScript(window.MenixAutofillEngine.scanScript());
  if(current.blocked)throw new Error(current.reason);
  const draftById=new Map((drafts||[]).map(item=>[item.id,String(item.value||'').trim()]));
  for(const field of current.fields||[]){
   const original=eligible.find(item=>item.signature===field.signature);
   const suggestion=original&&draftById.get(original.id);
   if(field.blank&&!field.sensitive&&suggestion)field.aiSuggestion=suggestion;
  }
  setApplicationAssistStatus(`${draftById.size} AI draft(s) returned. Review each answer; none were filled automatically.`);
  renderAutoApplyInspection(tab,current);
 }catch(error){setApplicationAssistStatus(`AI suggestions failed: ${error.message}`);toast(`AI suggestions failed: ${error.message}`);}
}
async function scanAutoApplyPage(tab){
 if(!tab?.view)throw new Error('Open an application page first.');
 const policy=window.MenixAutofillEngine.classifyUrl(tab.view.getURL());
 if(!policy.allowed)throw new Error(policy.reason);
 const scan=await tab.view.executeJavaScript(window.MenixAutofillEngine.scanScript());
 tab.policy=policy;
 tab.scanBlocked=Boolean(scan.blocked);
 renderAutoApplyInspection(tab,scan);
 return scan;
}
async function inspectAutoApplyForm(){
 const tab=activeApplicationTab();
 setAutoApplyStatus(tab,'Detecting Fields','Detecting visible form fields…');
 try{
  const scan=await scanAutoApplyPage(tab);
  if(scan.blocked){setAutoApplyStatus(tab,'Failed',scan.reason);return;}
  const remaining=scan.fields.filter(field=>field.required&&field.blank).length+(scan.unsupported||[]).filter(field=>field.required).length+(scan.uploads||[]).filter(field=>field.required&&field.blank).length;
  const warnings=scan.fields.filter(field=>field.invalid).length+(scan.uploads||[]).filter(field=>field.invalid).length;
  setAutoApplyStatus(tab,'Needs Review',`${scan.fields.length} supported and ${(scan.unsupported||[]).length} unsupported controls detected; ${remaining} required fields remain, with ${warnings} validation warning(s).`);
 }catch(error){setAutoApplyStatus(tab,'Failed',`Field detection failed: ${error.message}`);toast(error.message);}
}
async function fillAutoApplyForm(){
 const tab=activeApplicationTab();
 if(!tab?.jobId){toast('Open a saved job application first.');return;}
 setAutoApplyStatus(tab,'Autofilling','Checking the application page and detecting blank supported fields…');
 try{
  const policy=window.MenixAutofillEngine.classifyUrl(tab.view.getURL());
  if(!policy.allowed)throw new Error(policy.reason);
  const scan=await scanAutoApplyPage(tab);
  if(scan.blocked){setAutoApplyStatus(tab,'Failed',scan.reason);return;}
  const preferences=state.smartApplyPreferences;
  const answers=[];
  const reusableMemoryIds=[];
  for(const field of scan.fields||[]){
   if(!field.blank||field.sensitive)continue;
   if(tab.dismissedSuggestions?.has(field.signature)||tab.rejectedSuggestions?.has(field.signature))continue;
   const proposal=applicationProposal(field);
   if(proposal.source==='Profile'&&proposal.value&&proposal.confidence>=Number(preferences.highConfidenceThreshold||0.85)){
    answers.push({id:field.id,value:proposal.value,signature:field.signature,stableSignature:field.stableSignature,source:'Profile',confidence:proposal.confidence});
   }else if(proposal.source==='Previously Approved'&&proposal.value&&preferences.automaticReuseApprovedAnswers&&proposal.status==='approved-match'&&proposal.confidence>=Number(preferences.highConfidenceThreshold||0.85)){
    answers.push({id:field.id,value:proposal.value,signature:field.signature,stableSignature:field.stableSignature,source:'Previously Approved',confidence:proposal.confidence,memoryId:proposal.memoryId});
    reusableMemoryIds.push(proposal.memoryId);
   }
  }
  if(!answers.length){
   const requiredMissing=scan.fields.filter(field=>field.required&&field.blank).length+(scan.unsupported||[]).filter(field=>field.required).length+(scan.uploads||[]).filter(field=>field.required&&field.blank).length;
   setAutoApplyStatus(tab,'Needs Review',`No approved high-confidence answers are eligible for automatic reuse. ${requiredMissing} required field(s) remain; review suggestions or answer manually.`);
   return;
  }
  const current=await tab.view.executeJavaScript(window.MenixAutofillEngine.scanScript());
  if(current.blocked){setAutoApplyStatus(tab,'Failed',current.reason);return;}
  const currentBySignature=new Map((current.fields||[]).map(field=>[field.signature,field]));
  const safeValues=[];
  for(const answer of answers){
   const field=currentBySignature.get(answer.signature);
   if(field?.blank&&!field.sensitive)safeValues.push({id:field.id,value:answer.value,signature:answer.signature,stableSignature:field.stableSignature,source:answer.source,confidence:answer.confidence});
  }
  if(!safeValues.length){
   await scanAutoApplyPage(tab);
   setAutoApplyStatus(tab,'Needs Review','The form changed; no answers were filled. Review the rescan results.');
   return;
  }
  setAutoApplyStatus(tab,'Autofilling',`Applying ${safeValues.length} approved high-confidence profile or memory answer(s)…`);
  const result=await tab.view.executeJavaScript(window.MenixAutofillEngine.fillScript(safeValues));
  tab.autofilledSignatures ||= new Set();
  tab.answerOrigins ||= new Map();
  for(const answer of safeValues){
   const trackingSignature=answer.stableSignature||answer.signature;
   tab.autofilledSignatures.add(trackingSignature);
   tab.answerOrigins.set(trackingSignature,{value:answer.value,source:answer.source,confidence:answer.confidence});
  }
  for(const id of reusableMemoryIds){
   try{await api(`/smart-apply/answers/${id}/used`,{method:'POST'});}
   catch(error){toast(`Answer filled, but memory usage could not be recorded: ${error.message}`);}
  }
  const after=await scanAutoApplyPage(tab);
  const filled=Number(result?.filled||0);
  tab.fieldsFilled=filled;
  const missing=after.fields.filter(field=>field.required&&field.blank).length+(after.unsupported||[]).filter(field=>field.required).length;
  setAutoApplyStatus(tab,'Needs Review',`${filled} approved answers filled. ${missing} required field(s) remain; review every value and validation warning before proceeding.`);
 }catch(error){setAutoApplyStatus(tab,'Failed',`Autofill failed: ${error.message}`);toast(`Autofill failed: ${error.message}`);}
}
async function reviewAutoApplyForm(){
 const tab=activeApplicationTab();
 setAutoApplyStatus(tab,'Needs Review','Rechecking the current form and required fields…');
 try{
  const scan=await scanAutoApplyPage(tab);
  if(scan.blocked){setAutoApplyStatus(tab,'Failed',scan.reason);return;}
  const requiredMissing=scan.fields.filter(field=>field.required&&field.blank).length+(scan.unsupported||[]).filter(field=>field.required).length+(scan.uploads||[]).filter(field=>field.required&&field.blank).length;
  const validationIssues=scan.fields.filter(field=>field.invalid).length+(scan.uploads||[]).filter(field=>field.invalid).length;
  const candidates=(scan.submitCandidates||[]).filter(candidate=>/\b(submit|apply now|send application)\b/i.test(candidate.label));
  tab.reviewed=requiredMissing===0&&validationIssues===0;
  const submit=document.getElementById('application-submit-button');
  const maySubmit=window.MenixAutofillEngine.canSubmit({
   reviewed:tab.reviewed,
   requiredMissing,validationIssues,unsupportedCount:(scan.unsupported||[]).length,submitCandidateCount:candidates.length,blocked:scan.blocked
  }).allowed;
  if(submit)submit.disabled=!maySubmit;
  setAutoApplyStatus(tab,'Needs Review',maySubmit
   ?'Review complete. Required controls are filled and one submit control was identified; submission still requires your confirmation.'
   :`${requiredMissing} required fields remain, ${validationIssues} validation warnings, or no unique submit control was identified. Complete unsupported controls on the employer page, then rescan and review again.`);
 }catch(error){setAutoApplyStatus(tab,'Failed',`Form review failed: ${error.message}`);toast(error.message);}
}
async function uploadAutoApplyResume(){
 const tab=activeApplicationTab();
 if(!tab?.uploads?.length){toast('No visible file-upload control was detected.');return;}
 const upload=tab.resumeUploadCandidates?.length===1?tab.resumeUploadCandidates[0]:null;
 if(!upload){toast('No unique resume upload control could be identified. Use the employer page manually.');return;}
 if(!window.confirm('Open the employer’s native file chooser for this detected resume field? Menix will not select a file; choose the resume yourself.')){
  return;
 }
 try{
  const result=await tab.view.executeJavaScript(window.MenixAutofillEngine.clickUploadScript(upload.id),true);
  if(!result.opened)throw new Error(result.reason||'The upload control is unavailable.');
  setAutoApplyStatus(tab,'Needs Review','Choose the intended resume in the employer’s native file chooser, then review the page.');
 }catch(error){setAutoApplyStatus(tab,'Failed',`Resume chooser failed: ${error.message}`);toast(error.message);}
}

async function saveAutoApplyDraft(){
 const tab=activeApplicationTab();
 if(!tab?.applicationId){toast('Start AutoApply from a saved job before saving a draft.');return;}
 try{
  const saved=await api(`/applications/${tab.applicationId}`,{method:'PUT',body:JSON.stringify({status:'DRAFT'})});
  state.applications=[saved,...state.applications.filter(item=>Number(item.id)!==Number(saved.id))];
  setAutoApplyStatus(tab,'Draft Saved','Application progress saved to your tracker.');
  toast('Application saved as a draft.');
 }catch(error){toast(`Could not save draft: ${error.message}`);}
}

async function submitAutoApplyForm(){
 const tab=activeApplicationTab();
 if(!tab?.jobId||!tab.applicationId){toast('Start this saved job in AutoApply before submitting.');return;}
 const initialGate=window.MenixAutofillEngine.canSubmit({
  reviewed:tab.reviewed,requiredMissing:tab.requiredMissing,validationIssues:tab.validationIssues,
  unsupportedCount:tab.unsupported?.length||0,submitCandidateCount:tab.submitCandidates?.length||0,blocked:tab.scanBlocked
 });
 if(!initialGate.allowed){toast(initialGate.reason);return;}
 const fresh=await scanAutoApplyPage(tab).catch(error=>{setAutoApplyStatus(tab,'Failed',`Final form check failed: ${error.message}`);return null;});
 if(!fresh||fresh.blocked){setAutoApplyStatus(tab,'Failed',fresh?.reason||'The current form cannot be safely checked.');return;}
 const missing=fresh.fields.filter(field=>field.required&&field.blank).length+(fresh.unsupported||[]).filter(field=>field.required).length+(fresh.uploads||[]).filter(field=>field.required&&field.blank).length;
 const validationIssues=fresh.fields.filter(field=>field.invalid).length+(fresh.uploads||[]).filter(field=>field.invalid).length;
 const candidates=(fresh.submitCandidates||[]).filter(candidate=>/\b(submit|apply now|send application)\b/i.test(candidate.label));
 const finalGate=window.MenixAutofillEngine.canSubmit({
  reviewed:true,requiredMissing:missing,validationIssues,unsupportedCount:(fresh.unsupported||[]).length,submitCandidateCount:candidates.length,blocked:fresh.blocked
 });
 if(!finalGate.allowed){tab.reviewed=false;setAutoApplyStatus(tab,'Needs Review',`The page changed since review. ${finalGate.reason}`);toast(finalGate.reason);return;}
 tab.submitCandidates=candidates;
 tab.requiredMissing=missing;
 tab.validationIssues=validationIssues;
 tab.reviewed=true;
 let beforeVerification;
 try{beforeVerification=await tab.view.executeJavaScript(window.MenixAutofillEngine.verifySubmissionScript());}
 catch(error){setAutoApplyStatus(tab,'Failed',`The employer page could not be checked before submission: ${error.message}`);return;}
 if(!window.confirm(`Submit this application to ${tab.company||'the employer'} now? Menix will click the single identified submit control and mark it submitted only if a confirmation is detected.`))return;
 try{
  const result=await tab.view.executeJavaScript(window.MenixAutofillEngine.clickSubmitScript(tab.submitCandidates[0].id),true);
  if(!result.clicked)throw new Error(result.reason||'The application form could not be submitted.');
  setAutoApplyStatus(tab,'Needs Review','Submission was attempted. Waiting for an employer confirmation…');
  await new Promise(resolve=>setTimeout(resolve,1500));
  const verification=await tab.view.executeJavaScript(window.MenixAutofillEngine.verifySubmissionScript());
  const newConfirmation=verification.confirmed&&(!beforeVerification.confirmed||verification.location!==beforeVerification.location||verification.evidence!==beforeVerification.evidence);
  if(!newConfirmation){
   setAutoApplyStatus(tab,'Failed','Submission is not confirmed by the employer page. The tracker was not marked submitted; check the page or confirmation email.');
   return;
  }
  const evidence=`Employer page confirmation: ${String(verification.evidence).slice(0,500)}`;
  const saved=await api(`/applications/${tab.applicationId}`,{method:'PUT',body:JSON.stringify({status:'SUBMITTED',submissionEvidence:evidence})});
  state.applications=[saved,...state.applications.filter(item=>item.id!==saved.id)];
  clearAutoApplyContext();
  setAutoApplyStatus(tab,'Submitted',`Employer confirmation detected (“${verification.evidence}”). Application history was updated.`);
 }catch(error){setAutoApplyStatus(tab,'Failed',`Submission was not confirmed: ${error.message}. Check the employer page before retrying.`);toast(error.message);}
}

function applicationRow(a){
 const status=String(a.status||'NEEDS_ATTENTION');
 const statusClass=status==='SUBMITTED'?'status-applied':['INTERVIEW','OFFER'].includes(status)?'status-progress':status==='DECLINED'?'status-closed':'status-pending';
 const displayStatus=status==='NOT_COMPLETED_YET'?'Not Completed Yet':status.replaceAll('_',' ');
 return `<article class="application-card" data-application-card="${a.id}"><div class="application-card-top"><div class="company-icon">${esc(initials(a.company||'Company'))}</div><div class="application-title"><strong>${esc(a.jobTitle||'Untitled job')}</strong><span>${esc(a.company||'Company not specified')}</span></div><span class="application-status ${statusClass}">${esc(displayStatus)}</span></div><div class="application-card-meta"><span>Updated ${esc((a.updatedAt||'').slice(0,10)||'Date unavailable')}</span>${a.appliedAt?`<span>Applied ${esc(a.appliedAt)}</span>`:''}</div>${a.notes?`<p class="application-card-notes">${esc(a.notes)}</p>`:''}<div class="application-card-actions">${a.status==='DECLINED'?`<button class="button button-primary" data-restore-job="${a.jobId}">Restore to jobs</button>`:`<button class="button button-primary" data-app-edit="${a.id}">Update status</button>`}</div><div class="application-edit-slot" data-app-edit-slot="${a.id}"></div></article>`;
}
function renderApplications(){
 const active=state.applications.filter(a=>!['DECLINED','SHORTLISTED','DISCOVERED','WITHDRAWN','NOT_COMPLETED_YET'].includes(String(a.status||'').toUpperCase())),declined=state.applications.filter(a=>String(a.status||'').toUpperCase()==='DECLINED');
 content.innerHTML=`<section class="applications-page"><div class="applications-page-heading"><div><span class="eyebrow">YOUR JOB SEARCH</span><h2>Applied Jobs</h2><p class="muted small">Track applications with the employer. Choose “Not Completed Yet” to remove one from this active list and return its job to recommendations. Withdrawn history is preserved.</p></div><div class="application-summary"><strong>${active.length}</strong><span>Active applications</span></div></div><section class="application-section"><div class="application-section-heading"><h3>Application tracker</h3><span>${active.length} tracked</span></div>${active.length?`<div class="application-card-grid">${active.map(applicationRow).join('')}</div>`:'<div class="card empty">No active applications. Start a new application from Dashboard or Jobs to see it here.</div>'}</section><section class="application-section declined-section"><div class="application-section-heading"><h3>Rejected jobs</h3><span>${declined.length} declined</span></div>${declined.length?`<div class="application-card-grid">${declined.map(applicationRow).join('')}</div>`:'<div class="card empty">Rejected jobs stay here so you can restore them to recommendations later.</div>'}</section></section>`;
 document.querySelectorAll('[data-app-edit]').forEach(b=>b.onclick=()=>editApplication(Number(b.dataset.appEdit)));
 document.querySelectorAll('[data-restore-job]').forEach(b=>b.onclick=async()=>{b.disabled=true;try{await api(`/jobs/${b.dataset.restoreJob}/restore`,{method:'POST'});toast('Job restored to recommendations');await render();}catch(e){toast(e.message)}finally{b.disabled=false;}});
}
async function editApplication(id){
 const a=state.applications.find(x=>x.id===id);if(!a)return;const area=document.querySelector(`[data-app-edit-slot="${id}"]`);
 if(!area)return;
 const shouldClose=area.hasChildNodes();
 document.querySelectorAll('.application-edit-slot').forEach(slot=>slot.replaceChildren());
 if(shouldClose)return;
 const statuses=['NEEDS_ATTENTION','DRAFT','AWAITING_APPROVAL','SUBMITTED','SUBMISSION_FAILED','INTERVIEW','REJECTED','OFFER','WITHDRAWN','NOT_COMPLETED_YET'];
 const statusLabel=status=>status==='NOT_COMPLETED_YET'?'Not Completed Yet':status.replaceAll('_',' ');
 area.innerHTML=`<div class="application-edit-card"><h3>Update application</h3><form id="app-form"><div class="form-grid"><div class="field"><label>Status</label><select name="status">${statuses.map(s=>`<option value="${s}" ${a.status===s?'selected':''}>${esc(statusLabel(s))}</option>`).join('')}</select></div><div class="field"><label>Application date (optional)</label><input type="date" name="appliedAt" value="${esc((a.appliedAt||'').slice(0,10))}"></div><div class="field full"><label>Notes / follow-up</label><textarea name="notes">${esc(a.notes||'')}</textarea></div><div class="field full" id="application-evidence-field" hidden><label for="application-submission-evidence">Submission confirmation evidence</label><textarea id="application-submission-evidence" maxlength="1000" placeholder="For example: confirmation number or “Application received” email"></textarea></div></div><p class="muted small">A status change does not submit an application. To mark submitted, provide confirmation from the employer portal or email.</p><div class="actions"><button class="button button-primary">Save changes</button><button class="button" type="button" id="cancel-edit">Cancel</button></div></form></div>`;
 document.getElementById('cancel-edit').onclick=()=>area.innerHTML='';
 const appForm=document.getElementById('app-form');
 const statusSelect=appForm.elements.status;
 const evidenceField=document.getElementById('application-evidence-field');
 statusSelect.addEventListener('change',()=>{evidenceField.hidden=statusSelect.value!=='SUBMITTED'||a.status==='SUBMITTED';});
 appForm.onsubmit=async e=>{e.preventDefault();const d=Object.fromEntries(new FormData(e.currentTarget));if(d.status==='SUBMITTED'&&a.status!=='SUBMITTED'){const evidence=document.getElementById('application-submission-evidence').value.trim();if(!evidence){evidenceField.hidden=false;document.getElementById('application-submission-evidence').focus();toast('Add confirmation evidence before marking this submitted.');return}d.submissionEvidence=evidence}try{const saved=await api(`/applications/${id}`,{method:'PUT',body:JSON.stringify(d)});state.applications=[saved,...state.applications.filter(item=>item.id!==saved.id)];if(['WITHDRAWN','NOT_COMPLETED_YET'].includes(String(d.status).toUpperCase())){if(state.browserAssistant.autoApplyTab?.applicationId===id){state.browserAssistant.autoApplyTab=null;state.browserAssistant.savedJobId=null}toast(d.status==='WITHDRAWN'?'Application withdrawn. Its history is preserved; start another application from Jobs when ready.':'Removed from Applied Jobs. This job is available again in your recommendations.');go('jobs');return}toast('Application updated');await render();}catch(err){toast(err.message)}};
}
function outreachDraftMessage(contactName='',company='',jobTitle=''){
 const profile=state.profile||{};
 const name=String(profile.fullName||'').trim();
 const skills=String(profile.skills||'').split(/[,;\n]/).map(skill=>skill.trim()).filter(Boolean).slice(0,3);
 const role=jobTitle||String(profile.preferredTitles||'').split(/[,;\n]/)[0]||'the open role';
 const target=company||'your team';
 return `Hi ${contactName||'there'},\n\nI’m reaching out because I’m exploring ${role} opportunities at ${target}. My profile lists ${skills.length?skills.join(', '):'relevant skills and projects'}${profile.headline?`, and I’m currently focused on ${profile.headline}`:''}. Would you be open to sharing whether my background might be relevant?\n\nThank you${name?`,\n${name}`:''}`;
}
function outreachForm(record={}){
 const selectedJob=state.jobs.find(job=>Number(job.id)===Number(record.jobId));
 const options=state.jobs.map(job=>`<option value="${job.id}" ${Number(job.id)===Number(record.jobId)?'selected':''}>${esc(job.title)} — ${esc(job.company)}</option>`).join('');
 const draft=record.draftMessage||outreachDraftMessage(record.contactName,record.company,selectedJob?.title);
 return `<form id="outreach-form" class="outreach-form card"><input type="hidden" name="id" value="${esc(record.id||'')}"><div class="outreach-form-heading"><div><h3>${record.id?'Edit draft':'New recruiter draft'}</h3><p class="muted small">Draft only. Menix will not send a message or mark it sent.</p></div><span class="provider-badge badge-external">External / manual</span></div><div class="form-grid"><div class="field"><label for="outreach-portal">Portal</label><select id="outreach-portal" name="portal"><option ${record.portal==='LinkedIn'?'selected':''}>LinkedIn</option><option ${record.portal==='Naukri'?'selected':''}>Naukri</option><option ${record.portal==='Indeed'?'selected':''}>Indeed</option><option ${record.portal==='Email'?'selected':''}>Email</option><option ${!['LinkedIn','Naukri','Indeed','Email'].includes(record.portal)?'selected':''}>External</option></select></div><div class="field"><label for="outreach-contact">Contact name</label><input id="outreach-contact" name="contactName" maxlength="180" value="${esc(record.contactName||'')}" placeholder="Recruiter name"></div><div class="field"><label for="outreach-company">Company</label><input id="outreach-company" name="company" maxlength="180" value="${esc(record.company||'')}" placeholder="Company name"></div><div class="field"><label for="outreach-profile-url">Contact profile URL</label><input id="outreach-profile-url" name="profileUrl" type="url" value="${esc(record.profileUrl||'')}" placeholder="https://www.linkedin.com/in/…"></div><div class="field"><label for="outreach-job">Related job (optional)</label><select id="outreach-job" name="jobId"><option value="">No related job</option>${options}</select></div><div class="field"><label for="outreach-job-url">Job / careers URL</label><input id="outreach-job-url" name="jobUrl" type="url" value="${esc(record.jobUrl||selectedJob?.url||'')}" placeholder="https://careers.company.com/jobs/…"></div><div class="field full"><label for="outreach-message">Message draft</label><textarea id="outreach-message" name="draftMessage" maxlength="8000" rows="7" placeholder="Write a truthful, personalized message based on your profile">${esc(draft)}</textarea></div><div class="field"><label for="outreach-follow-up">Optional follow-up date</label><input id="outreach-follow-up" name="followUpDate" type="date" value="${esc(record.followUpDate||'')}"></div><div class="field"><label for="outreach-notes">Contact notes</label><textarea id="outreach-notes" name="notes" maxlength="3000" rows="2" placeholder="Where you met, context, follow-up notes">${esc(record.notes||'')}</textarea></div></div><div class="actions"><button class="button button-primary" type="submit">Save draft</button><button class="button" type="button" id="cancel-outreach-edit">Cancel</button></div></form>`;
}
async function renderOutreach(){
 [state.outreach,state.portalCapabilities]=await Promise.all([api('/outreach'),api('/portal-capabilities')]);
 const activeTab=state.outreachTab;
 const tabs=[['drafts','Drafts'],['contacts','Contacts'],['sent','Sent Log'],['inboxes','Connected Inboxes']];
 const records=state.outreach||[];
 const drafts=records.filter(record=>record.status==='DRAFT');
 const contacts=records.filter(record=>record.contactName||record.company);
 const sent=records.filter(record=>record.status==='SENT');
 const panes={
  drafts:`<div class="outreach-layout"><div class="outreach-list-panel"><div class="outreach-list-heading"><div><h3>Saved drafts</h3><span>${drafts.length} saved</span></div><button class="button button-primary" id="new-outreach-draft">＋ New draft</button></div>${drafts.length?`<div class="outreach-record-list">${drafts.map(record=>`<article class="outreach-record card"><div class="outreach-record-header"><div><strong>${esc(record.contactName||'Recruiter')}</strong><small>${esc([record.company,record.portal].filter(Boolean).join(' · ')||'External contact')}</small></div><span class="provider-badge badge-external">Draft</span></div><p>${esc(record.draftMessage||'No message text yet')}</p><div class="outreach-record-meta">${record.followUpDate?`Follow-up ${esc(record.followUpDate)}`:'No follow-up date'}${record.jobId?` · Job #${esc(record.jobId)}`:''}</div><div class="actions"><button class="button" data-outreach-edit="${record.id}">Edit</button><button class="button" data-outreach-copy="${record.id}">Copy message</button><button class="button" data-outreach-open="${record.id}">Open portal</button><button class="button button-quiet" data-outreach-sent="${record.id}">I sent this manually…</button></div><div class="sent-confirmation" id="outreach-confirm-${record.id}" hidden><label for="outreach-evidence-${record.id}">Send confirmation evidence</label><textarea id="outreach-evidence-${record.id}" maxlength="1000" placeholder="For example: clicked Send on LinkedIn after reviewing this draft"></textarea><div class="actions"><button class="button button-primary" data-confirm-sent="${record.id}">Confirm sent</button><button class="button" data-cancel-sent="${record.id}">Cancel</button></div></div></article>`).join('')}</div>`:'<div class="empty outreach-empty">Drafts you save will appear here.</div>'}</div><div id="outreach-composer">${outreachForm()}</div></div>`,
  contacts:`<section class="outreach-list-panel"><div class="outreach-list-heading"><div><h3>Recruiter contacts</h3><span>${contacts.length} saved</span></div><button class="button button-primary" id="new-contact-draft">＋ Add contact</button></div>${contacts.length?`<div class="outreach-record-list">${contacts.map(record=>`<article class="contact-row card"><div class="contact-avatar">${esc(initials(record.contactName||record.company||'C').slice(0,1))}</div><div class="contact-main"><strong>${esc(record.contactName||'Unnamed contact')}</strong><span>${esc(record.company||'Company not specified')} · ${esc(record.portal||'External')}</span><small>${record.notes?esc(record.notes):'No notes yet'}${record.followUpDate?` · Follow up ${esc(record.followUpDate)}`:''}</small></div><div class="contact-actions">${record.profileUrl||record.jobUrl?`<button class="button" data-outreach-open="${record.id}">Open profile / job</button>`:''}${record.status!=='SENT'?`<button class="button" data-outreach-edit="${record.id}">Edit</button>`:''}</div></article>`).join('')}</div>`:'<div class="empty outreach-empty">Save a recruiter draft with contact details to build your local contacts list.</div>'}</section>`,
  sent:`<section class="outreach-list-panel"><div class="outreach-list-heading"><div><h3>Confirmed sent messages</h3><span>${sent.length} logged</span></div></div><div class="notice"><strong>Manual confirmation only.</strong> This log changes only after you confirm that you sent a draft externally and add confirmation evidence. Menix does not send messages.</div>${sent.length?`<div class="outreach-record-list">${sent.map(record=>`<article class="outreach-record card"><div class="outreach-record-header"><div><strong>${esc(record.contactName||'Recruiter')}</strong><small>${esc([record.company,record.portal].filter(Boolean).join(' · '))}</small></div><span class="provider-badge badge-confirmed">Confirmed sent</span></div><p>${esc(record.draftMessage)}</p><div class="outreach-record-meta">Confirmed ${esc(record.sentAt||'')} · Evidence: ${esc(record.confirmationEvidence||'User confirmed')}</div></article>`).join('')}</div>`:'<div class="empty outreach-empty">Confirmed messages will appear here after you add evidence.</div>'}</section>`,
  inboxes:`<section class="outreach-list-panel"><div class="outreach-list-heading"><div><h3>Connected inboxes</h3><span>No inbox integration is currently connected</span></div></div><div class="notice"><strong>Privacy by default.</strong> Menix does not request portal passwords or access private inboxes. No approved messaging authorization is configured in this build.</div><div class="capability-list">${state.portalCapabilities.map(portal=>`<article class="capability-row card"><div class="capability-row-heading"><strong>${esc(portal.portal)}</strong><span class="provider-badge ${portal.badge==='Direct API'?'badge-direct':'badge-external'}">${esc(portal.badge)}</span></div><div class="capability-tags"><span>Inbox read: <b>Unavailable</b></span><span>Send: <b>Unavailable</b></span><span>Submission: <b>External / manual</b></span></div><p>${esc(portal.limitations||'No authorized messaging integration configured.')}</p></article>`).join('')}</div></section>`
 };
 content.innerHTML=`<section class="outreach-page"><div class="outreach-page-heading"><div><span class="eyebrow">RECRUITER NETWORK</span><h2>Messages &amp; Outreach</h2><p class="muted small">Prepare messages, track contacts and schedule follow-ups. Sending always happens on the portal.</p></div><span class="provider-badge badge-external">No inbox connected</span></div><nav class="outreach-tabs" aria-label="Outreach sections">${tabs.map(([key,label])=>`<button type="button" class="${activeTab===key?'active':''}" data-outreach-tab="${key}">${label}${key==='drafts'?` <span>${drafts.length}</span>`:''}</button>`).join('')}</nav>${panes[activeTab]}</section>`;
 content.querySelectorAll('[data-outreach-tab]').forEach(button=>button.addEventListener('click',()=>{state.outreachTab=button.dataset.outreachTab;state.activeOutreachId=null;renderOutreach().catch(showError)}));
 content.querySelectorAll('[data-outreach-edit]').forEach(button=>button.addEventListener('click',()=>{state.activeOutreachId=Number(button.dataset.outreachEdit);const record=records.find(item=>item.id===state.activeOutreachId);if(record){const composer=document.getElementById('outreach-composer');if(composer)composer.innerHTML=outreachForm(record);bindOutreachForm()}}));
 content.querySelector('#new-outreach-draft')?.addEventListener('click',()=>{state.activeOutreachId=null;document.getElementById('outreach-composer').innerHTML=outreachForm();bindOutreachForm()});
 content.querySelector('#new-contact-draft')?.addEventListener('click',()=>{state.outreachTab='drafts';state.activeOutreachId=null;renderOutreach().then(()=>document.getElementById('outreach-contact')?.focus()).catch(showError)});
 content.querySelectorAll('[data-outreach-copy]').forEach(button=>button.addEventListener('click',async()=>{const record=records.find(item=>item.id===Number(button.dataset.outreachCopy));if(record)await copyOutreachMessage(record.draftMessage)}));
 content.querySelectorAll('[data-outreach-open]').forEach(button=>button.addEventListener('click',()=>{const record=records.find(item=>item.id===Number(button.dataset.outreachOpen));const portalUrls={LinkedIn:'https://www.linkedin.com/jobs/',Naukri:'https://www.naukri.com/',Indeed:'https://www.indeed.com/'};const url=record?.profileUrl||record?.jobUrl||portalUrls[record?.portal];if(!url){toast('Add a public profile or job URL first.');return}openTrustedExternal(url).catch(error=>toast(error.message))}));
 content.querySelectorAll('[data-outreach-sent]').forEach(button=>button.addEventListener('click',()=>{const confirmation=document.getElementById(`outreach-confirm-${button.dataset.outreachSent}`);if(confirmation){confirmation.hidden=false;confirmation.querySelector('textarea')?.focus()}}));
 content.querySelectorAll('[data-cancel-sent]').forEach(button=>button.addEventListener('click',()=>{const confirmation=document.getElementById(`outreach-confirm-${button.dataset.cancelSent}`);if(confirmation)confirmation.hidden=true}));
 content.querySelectorAll('[data-confirm-sent]').forEach(button=>button.addEventListener('click',async()=>{const evidence=document.getElementById(`outreach-evidence-${button.dataset.confirmSent}`)?.value.trim();if(!evidence){toast('Add evidence before confirming this was sent.');return}try{await api(`/outreach/${button.dataset.confirmSent}/confirm-sent`,{method:'POST',body:JSON.stringify({evidence})});state.outreachTab='sent';toast('Manual send confirmation saved.');await renderOutreach()}catch(error){toast(error.message)}}));
 bindOutreachForm();
}
function bindOutreachForm(){
 const form=document.getElementById('outreach-form');
 if(!form)return;
 form.querySelector('#cancel-outreach-edit')?.addEventListener('click',()=>{state.activeOutreachId=null;const composer=document.getElementById('outreach-composer');if(composer)composer.innerHTML=outreachForm();bindOutreachForm()});
 form.querySelector('#outreach-job')?.addEventListener('change',event=>{const job=state.jobs.find(item=>Number(item.id)===Number(event.target.value));if(job){form.elements.company.value=form.elements.company.value||job.company;form.elements.jobUrl.value=job.url||job.applyUrl||'';const message=form.elements.draftMessage;if(!message.value.trim()||message.value===outreachDraftMessage(form.elements.contactName.value,form.elements.company.value,''))message.value=outreachDraftMessage(form.elements.contactName.value,job.company,job.title)}});
 form.addEventListener('submit',async event=>{event.preventDefault();const data=Object.fromEntries(new FormData(form));delete data.id;data.status='DRAFT';const id=Number(form.elements.id.value);try{await api(id?`/outreach/${id}`:'/outreach',{method:id?'PUT':'POST',body:JSON.stringify(data)});state.activeOutreachId=null;toast('Outreach draft saved.');await renderOutreach()}catch(error){toast(error.message)}});
}
async function copyOutreachMessage(message){
 try{await navigator.clipboard.writeText(message);toast('Message copied to clipboard.')}
 catch{const area=document.createElement('textarea');area.value=message;area.style.position='fixed';area.style.opacity='0';document.body.append(area);area.select();const copied=document.execCommand('copy');area.remove();toast(copied?'Message copied to clipboard.':'Select the message and copy it manually.')}
}
async function renderPortalCapabilities(){
 state.portalCapabilities=await api('/portal-capabilities');
 content.innerHTML=`<section class="sources-page"><div class="section-head"><div><h2>Portal capabilities</h2><p class="muted small">Current support and connection state. An external link is not an account or inbox connection.</p></div><button class="button" data-go="sources">Manage sources</button></div><div class="capability-list">${state.portalCapabilities.map(portal=>`<article class="capability-row card"><div class="capability-row-heading"><strong>${esc(portal.portal)}</strong><span class="provider-badge ${portal.badge==='Direct API'?'badge-direct':'badge-external'}">${esc(portal.badge)}</span></div><div class="capability-tags"><span>Job search: <b>${esc(portal.jobSearch)}</b></span><span>Details: <b>${esc(portal.jobDetails)}</b></span><span>Account: <b>${esc(portal.accountConnection)}</b></span><span>Inbox read: <b>${esc(portal.messagingRead)}</b></span><span>Send: <b>${esc(portal.messagingSend)}</b></span><span>Applications: <b>${esc(portal.applicationSubmission)}</b></span></div><p>${esc(portal.limitations)}</p></article>`).join('')}</div></section>`;
 bindCommon();
}
function renderInterview(){
 const opts=state.jobs.map(j=>`<option value="${j.id}">${esc(j.title)} ? ${esc(j.company)}</option>`).join('');
 content.innerHTML=`<div class="grid two-col"><div class="card"><h2>Interview practice</h2><p class="muted small">Generate a practice set based on a saved job. These are practice prompts, not predictions of the exact interview.</p><div class="field"><label>Select a job</label><select id="interview-job"><option value="">General Java / backend practice</option>${opts}</select></div><div class="actions"><button class="button button-primary" id="get-questions">Generate questions</button></div><div id="questions" class="questions-area"></div></div><div class="card"><h2>Suggested practice plan</h2><div class="question"><strong>Java fundamentals</strong><div class="muted small">OOP, collections, exceptions, streams, JVM basics.</div></div><div class="question"><strong>Spring Boot</strong><div class="muted small">Dependency injection, REST controllers, validation, JPA.</div></div><div class="question"><strong>SQL and databases</strong><div class="muted small">Joins, indexes, transactions, normalization.</div></div><div class="question"><strong>Project explanation</strong><div class="muted small">Explain your own design choices, trade-offs, testing and what you learned.</div></div></div></div>`;
 document.getElementById('get-questions').onclick=async()=>{const id=document.getElementById('interview-job').value;try{const qs=await api(`/interview/questions${id?`?jobId=${id}`:''}`);document.getElementById('questions').innerHTML=qs.map((q,i)=>`<div class="question"><strong>${i+1}. ${esc(q.question||q)}</strong><div class="muted small">${esc(q.category||'Practice')}</div><textarea class="answer-textarea" placeholder="Write your answer here..."></textarea></div>`).join('');}catch(e){toast(e.message)}};
}
function bindCommon(){
 document.querySelectorAll('[data-go]').forEach(b=>b.addEventListener('click',()=>go(b.dataset.go)));
 document.querySelectorAll('[data-save-job]').forEach(b=>b.addEventListener('click',()=>toggleSavedJob(Number(b.dataset.saveJob),b)));
 bindJobButtons();
}
const sidebar=document.querySelector('.sidebar');
const workspaceNav=document.getElementById('nav');
workspaceNav.addEventListener('click',e=>{
 const b=e.target.closest('[data-page]');
 if(!b)return;
 sidebar.classList.add('is-pinned');
 go(b.dataset.page);
});
document.addEventListener('click',event=>{
 if(workspaceNav.contains(event.target))return;
 sidebar.classList.remove('is-pinned');
 workspaceNav.querySelector('.nav-item:focus')?.blur();
});
window.addEventListener('popstate',event=>{
 const navigation=event.state;
 if(!navigation?.page)return;
 state.page=navigation.page;
 state.selectedJob=navigation.selectedJob||null;
 state.detailReturnPage=navigation.detailReturnPage||null;
 updateNavigation(state.page);
 render().catch(showError);
});
let swipeDistance=0,swipeResetTimer=0,lastSwipeBack=0;
window.addEventListener('wheel',event=>{
 if(event.ctrlKey||event.shiftKey||event.deltaX>=0||-event.deltaX<Math.abs(event.deltaY)*1.5){
  swipeDistance=0;
  return;
 }
 const target=event.target instanceof Element?event.target:null;
 if(target?.closest('input,textarea,select,[contenteditable="true"]'))return;
 let element=target;
 while(element&&element!==document.body){
  const style=getComputedStyle(element);
  if(element.scrollWidth>element.clientWidth&&/(auto|scroll)/.test(style.overflowX))return;
  element=element.parentElement;
 }
 swipeDistance-=event.deltaX;
 clearTimeout(swipeResetTimer);
 swipeResetTimer=setTimeout(()=>{swipeDistance=0;},140);
 if(swipeDistance>=90&&Date.now()-lastSwipeBack>800&&window.history.state?.page){
  swipeDistance=0;
  lastSwipeBack=Date.now();
  window.history.back();
 }
},{passive:true});
document.getElementById('account-profile-link')?.addEventListener('click',()=>go('profile'));
document.getElementById('change-account-avatar')?.addEventListener('click',()=>document.getElementById('account-avatar-file')?.click());
document.getElementById('account-avatar-file')?.addEventListener('change',async event=>{
 const file=event.target.files?.[0];
 if(!file)return;
 if(!['image/jpeg','image/png','image/webp'].includes(file.type)){
  toast('Choose a PNG, JPG, or WebP image.');
  event.target.value='';
  return;
 }
 if(file.size>5*1024*1024){
  toast('Choose an image smaller than 5 MB.');
  event.target.value='';
  return;
 }
 let bitmap;
 try{
  bitmap=await createImageBitmap(file);
  const size=256;
  const canvas=document.createElement('canvas');
  canvas.width=size;canvas.height=size;
  const context=canvas.getContext('2d');
  if(!context)throw new Error('Could not process this image.');
  const crop=Math.min(bitmap.width,bitmap.height);
  context.drawImage(bitmap,(bitmap.width-crop)/2,(bitmap.height-crop)/2,crop,crop,0,0,size,size);
  const avatar=canvas.toDataURL('image/jpeg',0.86);
  localStorage.setItem(accountAvatarStorageKey,avatar);
  const image=document.getElementById('account-avatar-image');
  image.src=avatar;image.hidden=false;
  const dashboardImage=document.getElementById('dashboard-avatar-image');
  const dashboardFallback=document.getElementById('dashboard-avatar-fallback');
  if(dashboardImage){dashboardImage.src=avatar;dashboardImage.hidden=false;}
  if(dashboardFallback)dashboardFallback.hidden=true;
  toast('Profile photo updated.');
 }catch(error){
  toast(error.name==='QuotaExceededError'?'Could not save this photo. Choose a smaller image.':error.message||'Could not update profile photo.');
 }finally{
  bitmap?.close();
  event.target.value='';
 }
});
document.body.classList.toggle('light-theme',localStorage.getItem('menix-theme')==='light');
window.history.replaceState({page:state.page,selectedJob:null,detailReturnPage:null},'');
render().then(runConfiguredSourcesOnOpen).catch(showError);
