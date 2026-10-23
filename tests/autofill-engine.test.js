const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const engine = require('../src/features/job-assistant/autofill-engine.js');

const inputPrototype = {};
Object.defineProperty(inputPrototype, 'value', {
  get() { return this._value; },
  set(value) { this._value = String(value); }
});
class MockEvent {
  constructor(type) { this.type = type; }
}

class MockElement {
  constructor(doc, { tag = 'INPUT', type = 'text', label = '', name = '', value = '', required = false, options = [], checked = false, hidden = false } = {}) {
    this.ownerDocument = doc;
    this.tagName = tag;
    this.type = type;
    this.name = name;
    this.id = '';
    this.placeholder = '';
    this.title = '';
    this.accept = '';
    this.multiple = false;
    this.disabled = false;
    this.readOnly = false;
    this.required = required;
    this.maxLength = -1;
    this.options = options;
    this.selectedIndex = 0;
    this.checked = checked;
    this._value = value;
    this.labels = label ? [{ textContent: label, innerText: label }] : [];
    this.attributes = {};
    this.events = [];
    this.clicks = 0;
    this.isConnected = true;
    this.form = null;
    this.hidden = hidden;
  }
  get value() { return this._value; }
  set value(value) { this._value = String(value); }
  getClientRects() { return this.hidden ? [] : [{}]; }
  getAttribute(name) { return this.attributes[name] || null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  removeAttribute(name) { delete this.attributes[name]; }
  closest(selector) { return selector === 'form' ? this.form : null; }
  dispatchEvent(event) { this.events.push(event.type); return true; }
  click() { this.clicks++; }
}

class MockDocument {
  constructor(controls = [], submitControls = [], bodyText = '') {
    const inputElementPrototype = inputPrototype;
    const textareaElementPrototype = {};
    Object.defineProperty(textareaElementPrototype, 'value', {
      get() { return this._value; },
      set(value) { this._value = String(value); }
    });
    this.defaultView = {
      Event: MockEvent,
      getComputedStyle: element => ({ display: element.hidden ? 'none' : 'block', visibility: 'visible' }),
      HTMLInputElement: { prototype: inputElementPrototype },
      HTMLTextAreaElement: { prototype: textareaElementPrototype }
    };
    this.body = { innerText: bodyText };
    this.controls = controls;
    this.submitControls = submitControls;
    this.frames = [];
    controls.forEach(element => { element.ownerDocument = this; });
    submitControls.forEach(element => { element.ownerDocument = this; });
  }
  querySelectorAll(selector) {
    if (selector === 'iframe,frame' || selector === 'iframe') return this.frames;
    if (selector === 'input,textarea,select') return this.controls;
    if (selector === 'button[type="submit"],input[type="submit"],[role="button"][aria-label*="submit" i]') return this.submitControls;
    if (selector.includes('[data-menix-autofill-id=')) {
      const id = selector.match(/data-menix-autofill-id="([^"]+)"/)?.[1];
      return [...this.controls, ...this.submitControls].filter(element => element.getAttribute('data-menix-autofill-id') === id);
    }
    if (selector === '[data-menix-autofill-id]') {
      return [...this.controls, ...this.submitControls].filter(element => element.getAttribute('data-menix-autofill-id'));
    }
    return [];
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  getElementById() { return null; }
}

function runPage(script, document) {
  return vm.runInNewContext(script, {
    document,
    location: { href: 'https://jobs.example.test/apply' },
    CSS: { escape: value => value },
    Event: MockEvent
  });
}

test('classifies HTTPS platforms conservatively and disables LinkedIn automation', () => {
  assert.equal(engine.classifyUrl('http://jobs.example.test').allowed, false);
  assert.equal(engine.classifyUrl('https://user:pass@example.test').allowed, false);
  assert.equal(engine.classifyUrl('https://linkedin.com/jobs').allowed, false);
  const greenhouse = engine.classifyUrl('https://boards.greenhouse.io/example/jobs/1');
  assert.equal(greenhouse.allowed, true);
  assert.equal(greenhouse.provider, 'Greenhouse');
  assert.equal(greenhouse.providerSpecificAdapter, false);
  assert.match(greenhouse.notice, /not validated/i);
  const forms = engine.classifyUrl('https://docs.google.com/forms/d/e/example/viewform');
  assert.equal(forms.provider, 'Google Forms');
  assert.equal(forms.providerSpecificAdapter, false);
  assert.match(forms.notice, /cross-origin controls/i);
});

test('maps exact profile fields, choices and skill checkboxes while refusing ambiguous or sensitive answers', () => {
  const profile = { fullName: 'Ada Lovelace', email: 'ada@example.test', skills: 'Java, SQL' };
  assert.equal(engine.mapField({ type: 'text', label: 'Full name' }, profile).value, 'Ada Lovelace');
  assert.equal(engine.mapField({ type: 'text', label: 'Name' }, profile).value, 'Ada Lovelace');
  assert.equal(engine.mapField({ type: 'text', label: 'Company name' }, profile).status, 'unmapped');
  assert.equal(engine.mapField({ type: 'text', autocomplete: 'name' }, profile).value, 'Ada Lovelace');
  assert.equal(engine.mapField({ type: 'text', label: 'First name' }, profile).value, 'Ada');
  assert.equal(engine.mapField({ type: 'text', label: 'Last name' }, profile).value, 'Lovelace');
  assert.equal(engine.mapField({ type: 'select', label: 'Work location', options: [{ value: 'remote', label: 'Remote' }] }, { preferredLocations: 'Remote' }).value, 'remote');
  assert.equal(engine.mapField({ type: 'checkbox', label: 'Java' }, profile).value, true);
  assert.equal(engine.mapField({ type: 'text', label: 'Legal work authorization' }, profile).status, 'manual');
  assert.equal(engine.mapField({ type: 'number', label: 'Expected salary' }, profile).status, 'manual');
});

test('scans dynamic text, select, radio, checkbox, file upload and submit controls', () => {
  const controls = [
    new MockElement(null, { label: 'Email address', type: 'email', required: true }),
    new MockElement(null, { label: 'Password', type: 'password', required: true }),
    new MockElement(null, { tag: 'SELECT', label: 'Preferred location', options: [{ value: '', label: 'Choose one' }, { value: 'remote', label: 'Remote' }] }),
    new MockElement(null, { label: 'Java', type: 'checkbox' }),
    new MockElement(null, { label: 'Resume', type: 'file', required: true }),
    new MockElement(null, { label: 'Portfolio attachment', type: 'color', required: true })
  ];
  controls[1].value = '';
  controls[1].selectedIndex = 0;
  const form = { querySelector: () => null };
  const submit = new MockElement(null, { tag: 'BUTTON', label: 'Submit application' });
  submit.form = form;
  const doc = new MockDocument(controls, [submit]);
  const result = runPage(engine.scanScript(), doc);
  assert.equal(result.fields.length, 3);
  assert.equal(result.fields.some(field => /password/i.test(field.label)), false);
  assert.equal(result.fields[0].required, true);
  assert.equal(result.fields[1].type, 'select');
  assert.equal(result.fields[1].blank, true);
  assert.equal(result.fields[2].type, 'checkbox');
  assert.equal(result.uploads.length, 1);
  assert.equal(result.uploads[0].required, true);
  assert.equal(result.uploads[0].blank, true);
  assert.equal(result.unsupported.length, 1);
  assert.equal(result.unsupported[0].required, true);
  assert.equal(result.submitCandidates.length, 1);
  const dynamicallyAdded = new MockElement(doc, { label: 'Phone', type: 'tel' });
  doc.controls.push(dynamicallyAdded);
  assert.ok(runPage(engine.scanScript(), doc).fields.some(field => field.label === 'Phone'));
});

test('does not block CAPTCHA privacy notices but pauses on an active challenge', () => {
  const notice = new MockDocument([], [], 'This site is protected by reCAPTCHA and the Google Privacy Policy and Terms of Service apply.');
  assert.equal(runPage(engine.scanScript(), notice).blocked, false);

  const prompt = new MockDocument([], [], 'Complete the CAPTCHA to continue.');
  assert.equal(runPage(engine.scanScript(), prompt).blocked, true);

  const challengeFrame = { src: 'https://www.google.com/recaptcha/api2/bframe', title: '', ownerDocument: null };
  const framedChallenge = new MockDocument([], []);
  framedChallenge.frames.push(challengeFrame);
  assert.equal(runPage(engine.scanScript(), framedChallenge).blocked, true);
});

test('detects hidden file inputs only when paired with a visible, named upload control', () => {
  const hiddenInput = new MockElement(null, { type: 'file', hidden: true });
  const trigger = {
    innerText: 'Attach Resume',
    ownerDocument: null,
    title: '',
    getAttribute: () => null,
    getClientRects: () => [{}]
  };
  const wrapper = { querySelectorAll: () => [trigger], parentElement: null };
  hiddenInput.parentElement = wrapper;
  const doc = new MockDocument([hiddenInput]);
  trigger.ownerDocument = doc;
  const result = runPage(engine.scanScript(), doc);
  assert.equal(result.uploads.length, 1);
  assert.equal(result.uploads[0].label, 'Attach Resume');
  assert.equal(runPage(engine.clickUploadScript(result.uploads[0].id), doc).opened, true);

  const unrelatedHiddenInput = new MockElement(null, { type: 'file', hidden: true });
  assert.equal(runPage(engine.scanScript(), new MockDocument([unrelatedHiddenInput])).uploads.length, 0);
});

test('keeps a stable field signature when dynamic form element IDs change', () => {
  const field = new MockElement(null, { label: 'Email' });
  field.id = 'input-1';
  const doc = new MockDocument([field]);
  const first = runPage(engine.scanScript(), doc).fields[0];
  field.id = 'input-2';
  const second = runPage(engine.scanScript(), doc).fields[0];
  assert.notEqual(first.signature, second.signature);
  assert.equal(first.stableSignature, second.stableSignature);
});

test('fills blank text, dropdown, radio and profile-skill checkbox controls with input events', () => {
  const text = new MockElement(null, { label: 'Email', type: 'email', required: true });
  const select = new MockElement(null, { tag: 'SELECT', label: 'Preferred location', options: [{ value: 'remote', label: 'Remote' }] });
  select.value = '';
  const radioA = new MockElement(null, { type: 'radio', label: 'Remote', name: 'mode', value: 'remote' });
  const radioB = new MockElement(null, { type: 'radio', label: 'Onsite', name: 'mode', value: 'onsite' });
  const checkbox = new MockElement(null, { type: 'checkbox', label: 'Java' });
  const doc = new MockDocument([text, select, radioA, radioB, checkbox]);
  const scan = runPage(engine.scanScript(), doc);
  const radio = scan.fields.find(field => field.type === 'radio');
  const checkboxField = scan.fields.find(field => field.type === 'checkbox');
  const result = runPage(engine.fillScript([
    { id: scan.fields.find(field => field.type === 'email').id, value: 'ada@example.test' },
    { id: scan.fields.find(field => field.type === 'select').id, value: 'remote' },
    { id: radio.id, value: 'onsite' },
    { id: checkboxField.id, value: true }
  ]), doc);
  assert.equal(result.filled, 4);
  assert.equal(text.value, 'ada@example.test');
  assert.equal(select.value, 'remote');
  assert.equal(radioB.checked, true);
  assert.equal(checkbox.checked, true);
  assert.ok(text.events.includes('input') && text.events.includes('change'));
});

test('recognizes a required marker in a visible field label', () => {
  const email = new MockElement(null, { label: 'Email *' });
  const scan = runPage(engine.scanScript(), new MockDocument([email]));
  assert.equal(scan.fields[0].required, true);
});

test('requires completed required fields, review, and a unique submit control', () => {
  const base = { reviewed: true, requiredMissing: 0, submitCandidateCount: 1, blocked: false };
  assert.equal(engine.canSubmit(base).allowed, true);
  assert.equal(engine.canSubmit({ ...base, reviewed: false }).allowed, false);
  assert.equal(engine.canSubmit({ ...base, requiredMissing: 1 }).allowed, false);
  assert.equal(engine.canSubmit({ ...base, unsupportedCount: 1 }).allowed, false);
  assert.equal(engine.canSubmit({ ...base, submitCandidateCount: 2 }).allowed, false);
  assert.equal(engine.canSubmit({ ...base, blocked: true }).allowed, false);
});

test('uses a native file chooser and only recognizes visible submission confirmation', () => {
  const fileInput = new MockElement(null, { type: 'file', label: 'Resume' });
  const form = { querySelector: () => null };
  const submit = new MockElement(null, { tag: 'BUTTON', label: 'Submit application' });
  submit.form = form;
  const doc = new MockDocument([fileInput], [submit], 'Thank you for applying. Your application has been received.');
  const scan = runPage(engine.scanScript(), doc);
  const upload = runPage(engine.clickUploadScript(scan.uploads[0].id), doc);
  assert.equal(upload.opened, true);
  assert.equal(fileInput.clicks, 1);
  const submitId = scan.submitCandidates[0].id;
  assert.equal(runPage(engine.clickSubmitScript(submitId), doc).clicked, true);
  assert.equal(submit.clicks, 1);
  assert.equal(runPage(engine.verifySubmissionScript(), doc).confirmed, true);
  doc.body.innerText = 'Please check the form for errors.';
  assert.equal(runPage(engine.verifySubmissionScript(), doc).confirmed, false);
});

test('does not expose the autofill bridge to websites and keeps the application webview isolated', () => {
  const root = path.resolve(__dirname, '..');
  const main = fs.readFileSync(path.join(root, 'main.js'), 'utf8');
  const preload = fs.readFileSync(path.join(root, 'preload.js'), 'utf8');
  const renderer = fs.readFileSync(path.join(root, 'src/features/job-assistant/job-assistant.js'), 'utf8');
  assert.match(main, /contextIsolation:\s*true/);
  assert.match(main, /nodeIntegration:\s*false/);
  assert.match(main, /sandbox:\s*true/);
  assert.match(main, /delete webPreferences\.preload/);
  assert.doesNotMatch(main, /autofill-application/);
  assert.doesNotMatch(preload, /autofillApplication|ipcRenderer\.send/);
  assert.match(preload, /onEmbeddedBrowserPopup/);
  assert.match(preload, /ipcRenderer\.on\('embedded-browser-popup', listener\)/);
  const submitStart = renderer.indexOf('async function submitAutoApplyForm()');
  const submitEnd = renderer.indexOf('\nfunction applicationRow(a)', submitStart);
  const submitFlow = renderer.slice(submitStart, submitEnd);
  assert.ok(submitFlow.indexOf('window.confirm(') < submitFlow.indexOf('clickSubmitScript('));
  assert.ok(submitFlow.indexOf('verifySubmissionScript()') < submitFlow.indexOf("status:'SUBMITTED'"));
});
