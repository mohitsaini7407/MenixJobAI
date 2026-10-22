(function exposeAutofillEngine(root) {
  const profileFields = [
    ['email', /\b(e-?mail|email address)\b/i, 8],
    ['phone', /\b(phone|mobile|telephone|cell number)\b/i, 8],
    ['fullName', /\b(full name|candidate name|your name|first name|given name|last name|family name|surname)\b/i, 8],
    ['linkedinUrl', /\blinked[\s-]?in\b/i, 8],
    ['githubUrl', /\bgithub\b/i, 8],
    ['portfolioUrl', /\b(portfolio|personal website|website url)\b/i, 8],
    ['skills', /\b(skills?|technologies|tech stack)\b/i, 6],
    ['degree', /\b(degree|qualification|major)\b/i, 6],
    ['graduationYear', /\b(graduation year|year of graduation|graduated in)\b/i, 7],
    ['education', /\b(education|university|college)\b/i, 6],
    ['projects', /\b(projects?|project description)\b/i, 6],
    ['experience', /\b(work history|employment history|professional background|experience summary)\b/i, 6],
    ['headline', /\b(headline|professional title|current title|target title)\b/i, 5],
    ['currentLocation', /\b(current location|current city|your location|city|country|location)\b/i, 5],
    ['preferredLocations', /\b(preferred location|work location|location preference)\b/i, 8],
    ['noticePeriod', /\b(notice period|availability to join|joining time)\b/i, 6]
  ];
  const providerAdapters = [
    { name: 'Google Forms', pattern: /(^|\.)forms\.gle$|(^|\.)docs\.google\.com$/i },
    { name: 'Greenhouse', pattern: /(^|\.)greenhouse\.io$/i },
    { name: 'Lever', pattern: /(^|\.)lever\.co$/i },
    { name: 'Ashby', pattern: /(^|\.)ashbyhq\.com$/i },
    { name: 'Workday', pattern: /(^|\.)myworkdayjobs\.com$/i },
    { name: 'Workable', pattern: /(^|\.)workable\.com$/i }
  ];
  const blockedHosts = [/^([a-z0-9-]+\.)*linkedin\.com$/i];
  const sensitiveTerms = /\b(work authorization|authorized to work|visa|sponsor(ship)?|salary|compensation|gender|sex|ethnicity|race|disabilit(y|ies)|veteran|citizenship|date of birth|birth ?date|age|social security|ssn|tax id|criminal|conviction|background check|security clearance|consent|agree|terms and conditions|legally authorized|are you|do you|will you|have you ever|how many years)\b/i;
  const challengeTerms = /\b(captcha|recaptcha|hcaptcha|verify you are human|not a robot|robot verification|security challenge|security check|unusual traffic)\b/i;

  function normalize(value) {
    return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  }

  function classifyUrl(rawUrl) {
    let url;
    try {
      url = new URL(rawUrl);
    } catch {
      return { allowed: false, reason: 'Open a valid HTTPS application page first.' };
    }
    if (url.protocol !== 'https:' || url.username || url.password) {
      return { allowed: false, reason: 'Autofill is available only on public HTTPS pages.' };
    }
    if (blockedHosts.some(pattern => pattern.test(url.hostname))) {
      return { allowed: false, reason: 'Automated assistance is disabled on this platform. Use manual entry.' };
    }
    const provider = providerAdapters.find(item => item.pattern.test(url.hostname));
    return {
      allowed: true,
      adapter: 'accessible-form',
      provider: provider?.name || null,
      providerSpecificAdapter: false,
      supportedControls: ['text', 'email', 'tel', 'url', 'date', 'select', 'radio'],
      resumeUpload: 'user-selected-file-only',
      multiStep: 'rescan-after-each-step',
      notice: provider?.name === 'Google Forms'
        ? 'Google Forms may use custom or cross-origin controls; only visible accessible controls can be inspected. Respect the form owner’s permissions.'
        : provider
        ? `${provider.name} is recognized, but its provider-specific adapter is not validated; accessible generic fields only.`
        : 'Generic accessible form only; custom controls may need manual entry.'
    };
  }

  function mapField(field, profile) {
    if (!field || field.sensitive) return { status: 'manual', value: '', confidence: 0, reason: 'Sensitive or legally significant question requires your own answer.' };
    const label = `${field.label || ''} ${field.name || ''} ${field.elementId || ''} ${field.placeholder || ''} ${field.autocomplete || ''}`;
    if (sensitiveTerms.test(label)) return { status: 'manual', value: '', confidence: 0, reason: 'Sensitive or legally significant question requires your own answer.' };
    if (field.type === 'checkbox') {
      const skills = String(profile?.skills || '').split(/[,;|\n]/).map(normalize).filter(Boolean);
      if (skills.includes(normalize(field.label))) return { status: 'mapped', key: 'skills', value: true, confidence: 0.9 };
      return { status: 'manual', value: '', confidence: 0, reason: 'This checkbox is not an exact match for a saved skill.' };
    }
    const matches = profileFields.filter(([, pattern]) => pattern.test(label));
    if (!matches.length) return { status: 'unmapped', value: '', confidence: 0, reason: 'No matching fact exists in the saved candidate profile.' };
    matches.sort((a, b) => b[2] - a[2]);
    if (matches.length > 1 && matches[0][2] - matches[1][2] < 2) {
      return { status: 'ambiguous', value: '', confidence: 0, reason: 'The label matches multiple profile fields; choose manually.' };
    }
    const key = matches[0][0];
    let value = String(profile?.[key] || '').trim();
    if (key === 'currentLocation' && !value) value = String(profile?.preferredLocations || '').trim();
    if (key === 'fullName') {
      if (/\b(first|given)\s+name\b/i.test(label) && !/\blast\b/i.test(label)) value = value.split(/\s+/)[0] || '';
      else if (/\b(last|family|surname)\s+name\b/i.test(label)) value = value.split(/\s+/).slice(1).join(' ');
      else if (/\b(first|given)\s+name\b/i.test(label) || /\b(last|family|surname)\s+name\b/i.test(label)) {
        return { status: 'ambiguous', value: '', confidence: 0, reason: 'The name field does not identify first name or last name clearly.' };
      }
    }
    if (!value) return { status: 'unmapped', value: '', confidence: 0, reason: `No saved value exists for ${key}.` };
    if (field.type === 'select' || field.type === 'radio') {
      const option = (field.options || []).find(item => normalize(item.label) === normalize(value) || normalize(item.value) === normalize(value));
      if (!option) return { status: 'ambiguous', value: '', confidence: 0, reason: 'The saved value does not match any available form option.' };
      value = option.value;
    }
    return { status: 'mapped', key, value, confidence: Math.min(0.98, matches[0][2] / 6), reason: 'Exact profile field match.' };
  }

  function canSubmit({ reviewed, permissionConfirmed, requiredMissing, validationIssues = 0, unsupportedCount = 0, submitCandidateCount, blocked }) {
    if (blocked) return { allowed: false, reason: 'The page is blocked for automated interaction.' };
    if (!permissionConfirmed) return { allowed: false, reason: 'Site permission has not been confirmed.' };
    if (!reviewed) return { allowed: false, reason: 'Review the completed form first.' };
    if (Number(requiredMissing) > 0) return { allowed: false, reason: 'Required fields are incomplete.' };
    if (Number(validationIssues) > 0) return { allowed: false, reason: 'One or more fields have validation errors.' };
    if (Number(unsupportedCount) > 0) return { allowed: false, reason: 'Unsupported controls remain; complete the whole application manually and verify it on the employer page.' };
    if (Number(submitCandidateCount) !== 1) return { allowed: false, reason: 'A unique submit control could not be identified.' };
    return { allowed: true, reason: '' };
  }

  function scanPage() {
    const sensitiveTerms = /\b(work authorization|authorized to work|visa|sponsor(ship)?|salary|compensation|gender|sex|ethnicity|race|disabilit(y|ies)|veteran|citizenship|date of birth|birth ?date|age|social security|ssn|tax id|criminal|conviction|background check|security clearance|consent|agree|terms and conditions|legally authorized|are you|do you|will you|have you ever|how many years)\b/i;
    const challengeTerms = /\b(captcha|recaptcha|hcaptcha|verify you are human|not a robot|robot verification|security challenge|security check|unusual traffic)\b/i;
    const documents = [];
    let inaccessibleFrames = 0;
    const collect = doc => {
      documents.push(doc);
      for (const frame of doc.querySelectorAll('iframe,frame')) {
        try {
          if (frame.contentDocument?.documentElement) collect(frame.contentDocument);
        } catch {
          inaccessibleFrames++;
        }
      }
    };
    collect(document);
    const bodyText = documents.map(doc => String(doc.body?.innerText || '').slice(0, 30000)).join(' ');
    const challengeFrame = documents.some(doc => [...doc.querySelectorAll('iframe')].some(frame => /captcha|challenge/i.test(`${frame.src || ''} ${frame.title || ''}`)));
    if (challengeTerms.test(bodyText) || challengeFrame) return { blocked: true, reason: 'A CAPTCHA or security challenge is present. Complete it manually; autofill is paused.', fields: [], uploads: [], submitCandidates: [] };
    const fields = [];
    const uploads = [];
    const unsupported = [];
    const submitCandidates = [];
    const authForms = new Set(documents.flatMap(doc => [...doc.querySelectorAll('form')])
      .filter(form => form.querySelector('input[type="password"]')));
    let sequence = 0;
    const text = node => String(node?.innerText || node?.textContent || '').replace(/\s+/g, ' ').trim();
    const visible = element => {
      const style = element.ownerDocument.defaultView.getComputedStyle(element);
      return style.display !== 'none' && style.visibility !== 'hidden' && element.getClientRects().length > 0;
    };
    const labelFor = element => {
      const associated = element.labels ? [...element.labels].map(text).filter(Boolean).join(' / ') : '';
      if (associated) return associated;
      const labelledBy = element.getAttribute('aria-labelledby')?.split(/\s+/)
        .map(id => text(element.ownerDocument.getElementById(id))).filter(Boolean).join(' ');
      return labelledBy || element.getAttribute('aria-label') || text(element.closest('label')) || element.name || element.placeholder || '';
    };
    const signatureFor = field => JSON.stringify([field.type, field.elementId, field.name, field.label, field.context, field.placeholder, field.autocomplete, field.options]);
    for (const doc of documents) {
      doc.querySelectorAll('[data-menix-autofill-id]').forEach(element => element.removeAttribute('data-menix-autofill-id'));
      const allNodes = [...doc.querySelectorAll('input,textarea,select')];
      const nodes = allNodes.filter(element => !authForms.has(element.form || element.closest('form')));
      const radioGroups = new Set();
      for (const element of nodes) {
        if (element.disabled || element.readOnly || !visible(element)) continue;
        const type = element.tagName === 'TEXTAREA' ? 'textarea' : element.tagName === 'SELECT' ? 'select' : String(element.type || 'text').toLowerCase();
        if (type === 'file') {
          const id = `u${sequence++}`;
          element.setAttribute('data-menix-autofill-id', id);
          uploads.push({ id, label: labelFor(element), accept: element.accept || '', multiple: Boolean(element.multiple), required: Boolean(element.required || element.getAttribute('aria-required') === 'true'), blank: !element.files?.length, invalid: element.getAttribute('aria-invalid') === 'true' || Boolean(element.files?.length && element.validity?.valid === false) });
          continue;
        }
        if (type === 'password') continue;
        if (type === 'submit' || type === 'button') continue;
        if (!['text', 'email', 'tel', 'url', 'date', 'number', 'textarea', 'select', 'radio', 'checkbox'].includes(type)) {
          unsupported.push({ label: labelFor(element), type, required: Boolean(element.required || element.getAttribute('aria-required') === 'true'), reason: `The ${type} input type is not supported for autofill.` });
          continue;
        }
        if (type === 'select' && element.multiple) {
          unsupported.push({ label: labelFor(element), type: 'multi-select', required: Boolean(element.required || element.getAttribute('aria-required') === 'true'), reason: 'Multi-select controls are not supported; select values manually.' });
          continue;
        }
        if (type === 'radio') {
          const groupName = element.name || element.id;
          if (!groupName || radioGroups.has(groupName)) continue;
          radioGroups.add(groupName);
          const group = nodes.filter(item => item.type === 'radio' && (item.name || item.id) === groupName && visible(item));
          const legend = text(element.closest('fieldset')?.querySelector('legend'));
          const label = legend || labelFor(element);
          const options = group.map(item => ({ value: item.value, label: labelFor(item) || item.value }));
          const id = `f${sequence++}`;
          group.forEach(item => item.setAttribute('data-menix-autofill-id', id));
          const selected = group.find(item => item.checked);
          const field = { id, type, label, name: element.name || '', elementId: element.id || '', context: legend, placeholder: '', required: group.some(item => item.required || item.getAttribute('aria-required') === 'true'), value: selected?.value || '', blank: !selected, invalid: Boolean(selected && (selected.getAttribute('aria-invalid') === 'true' || selected.validity?.valid === false)), maxLength: 5000, options };
          field.signature = signatureFor(field);
          field.sensitive = sensitiveTerms.test(`${label} ${field.name} ${field.context}`);
          fields.push(field);
          continue;
        }
        for (const element of doc.querySelectorAll('[role="combobox"],[role="listbox"],[role="checkbox"],[role="radio"],[role="textbox"],[role="spinbutton"],[contenteditable="true"]')) {
          if (!visible(element) || element.disabled) continue;
          if (element.matches?.('input,textarea,select')) continue;
          const label=labelFor(element)||text(element);
          unsupported.push({label:label||'Unlabeled custom control',type:element.getAttribute('role')||'contenteditable',required:element.getAttribute('aria-required')==='true',reason:'This custom widget is not safely writable; complete it on the employer page.'});
        }
        const label = labelFor(element);
        const context = [text(element.closest('fieldset')?.querySelector('legend')), element.getAttribute('aria-describedby')?.split(/\s+/).map(id => text(doc.getElementById(id))).filter(Boolean).join(' '), element.title].filter(Boolean).join(' ');
        const id = `f${sequence++}`;
        element.setAttribute('data-menix-autofill-id', id);
        const options = type === 'select'
          ? [...element.options].filter(option => !option.disabled && option.value && option.label && !/^(select|choose|please select|--)/i.test(option.label.trim())).map(option => ({ value: option.value, label: option.label.trim() }))
          : [];
        const field = {
          id, type, label, name: element.name || '', elementId: element.id || '', autocomplete: element.autocomplete || '', context,
          placeholder: element.placeholder || '', required: Boolean(element.required || element.getAttribute('aria-required') === 'true'),
          value: type === 'checkbox' ? String(element.checked) : String(element.value || ''),
          blank: type === 'checkbox' ? !element.checked : type === 'select'
            ? (!element.value || element.selectedIndex === 0 && /^(select|choose|please select|--)/i.test(text(element.options[element.selectedIndex])))
            : !String(element.value || '').trim(),
          invalid: element.getAttribute('aria-invalid') === 'true' || Boolean(String(element.value || '').trim() && element.validity?.valid === false),
          maxLength: element.maxLength > 0 ? Math.min(5000, element.maxLength) : 5000,
          options
        };
        field.signature = signatureFor(field);
        field.sensitive = sensitiveTerms.test(`${label} ${field.name} ${context} ${field.placeholder}`);
        fields.push(field);
      }
      for (const element of doc.querySelectorAll('button[type="submit"],input[type="submit"],[role="button"][aria-label*="submit" i]')) {
        if (!visible(element) || element.disabled) continue;
        if (authForms.has(element.form || element.closest('form'))) continue;
        const label = text(element) || element.value || element.getAttribute('aria-label') || '';
        const id = `s${sequence++}`;
        element.setAttribute('data-menix-autofill-id', id);
        submitCandidates.push({ id, label });
      }
    }
    return { blocked: false, fields, uploads, unsupported, submitCandidates, inaccessibleFrames };
  }

  function fillPage(answers) {
    const documents = [];
    const collect = doc => {
      documents.push(doc);
      for (const frame of doc.querySelectorAll('iframe,frame')) {
        try {
          if (frame.contentDocument?.documentElement) collect(frame.contentDocument);
        } catch {}
      }
    };
    collect(document);
    let filled = 0;
    for (const answer of answers || []) {
      if (!answer || !answer.id || answer.value === undefined || answer.value === null) continue;
      const targets = documents.flatMap(doc => [...doc.querySelectorAll(`[data-menix-autofill-id="${CSS.escape(answer.id)}"]`)]);
      if (!targets.length || targets.some(target => target.disabled || target.readOnly)) continue;
      const first = targets[0];
      if (first.type === 'radio') {
        if (targets.some(target => target.checked)) continue;
        const option = targets.find(target => target.value === String(answer.value));
        if (!option) continue;
        option.checked = true;
        option.dispatchEvent(new Event('input', { bubbles: true }));
        option.dispatchEvent(new Event('change', { bubbles: true }));
        filled++;
        continue;
      }
      if (first.type === 'checkbox') {
        if (first.checked || answer.value !== true) continue;
        first.checked = true;
        first.dispatchEvent(new Event('input', { bubbles: true }));
        first.dispatchEvent(new Event('change', { bubbles: true }));
        filled++;
        continue;
      }
      if (String(first.value || '').trim()) continue;
      const value = String(answer.value);
      if (first.maxLength >= 0 && value.length > first.maxLength) continue;
      if (first.tagName === 'SELECT') {
        const option = [...first.options].find(item => !item.disabled && item.value === value);
        if (!option) continue;
        first.value = option.value;
      } else {
        const view = first.ownerDocument.defaultView;
        const prototype = first.tagName === 'TEXTAREA' ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype;
        const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
        if (!setter) continue;
        setter.call(first, value);
      }
      const view = first.ownerDocument.defaultView;
      first.dispatchEvent(new view.Event('input', { bubbles: true }));
      first.dispatchEvent(new view.Event('change', { bubbles: true }));
      filled++;
    }
    return { filled };
  }

  function clickUpload(id) {
    const documents = [];
    const collect = doc => {
      documents.push(doc);
      for (const frame of doc.querySelectorAll('iframe,frame')) {
        try {
          if (frame.contentDocument?.documentElement) collect(frame.contentDocument);
        } catch {}
      }
    };
    collect(document);
    const input = documents.map(doc => doc.querySelector(`input[type="file"][data-menix-autofill-id="${CSS.escape(id)}"]`)).find(Boolean);
    if (!input || input.disabled) return { opened: false, reason: 'The resume upload control is no longer available.' };
    input.click();
    return { opened: true };
  }

  function clickSubmit(id) {
    const documents = [];
    const collect = doc => {
      documents.push(doc);
      for (const frame of doc.querySelectorAll('iframe,frame')) {
        try {
          if (frame.contentDocument?.documentElement) collect(frame.contentDocument);
        } catch {}
      }
    };
    collect(document);
    const target = documents.map(doc => doc.querySelector(`[data-menix-autofill-id="${CSS.escape(id)}"]`)).find(Boolean);
    if (!target || target.disabled || !target.isConnected) return { clicked: false, reason: 'The submit control is no longer available.' };
    const form = target.form || target.closest('form');
    if (!form) return { clicked: false, reason: 'The submit control is not part of a form.' };
    if (form.querySelector('input[type="password"]')) return { clicked: false, reason: 'Sign-in forms cannot be submitted by AutoApply.' };
    target.click();
    return { clicked: true };
  }

  function verifySubmission() {
    const documents = [];
    const collect = doc => {
      documents.push(doc);
      for (const frame of doc.querySelectorAll('iframe,frame')) {
        try {
          if (frame.contentDocument?.documentElement) collect(frame.contentDocument);
        } catch {}
      }
    };
    collect(document);
    const text = documents.map(doc => String(doc.body?.innerText || '').slice(0, 30000)).join(' ');
    const phrases = [
      /application (has been )?submitted/i,
      /application (has been )?received/i,
      /thank you for applying/i,
      /thanks for applying/i,
      /we('ve| have) received your application/i,
      /successfully applied/i
    ];
    const pattern = phrases.find(item => item.test(text));
    const match = pattern ? text.match(pattern) : null;
    return match ? { confirmed: true, evidence: match[0], location: location.href } : { confirmed: false, evidence: '', location: location.href };
  }

  const api = {
    classifyUrl,
    mapField,
    canSubmit,
    scanScript: () => `(${scanPage.toString()})()`,
    fillScript: answers => `(${fillPage.toString()})(${JSON.stringify(answers)})`,
    clickUploadScript: id => `(${clickUpload.toString()})(${JSON.stringify(id)})`,
    clickSubmitScript: id => `(${clickSubmit.toString()})(${JSON.stringify(id)})`,
    verifySubmissionScript: () => `(${verifySubmission.toString()})()`
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MenixAutofillEngine = api;
})(typeof window === 'undefined' ? globalThis : window);
