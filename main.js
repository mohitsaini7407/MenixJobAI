const { app, BrowserWindow, shell, ipcMain, session, Menu } = require('electron');
const path = require('node:path');
const fs = require('node:fs');

const APPLICATION_PARTITION = 'persist:menix-application-sessions';
let applicationWindow = null;
let mainWindow = null;

function platformStorePath() {
  return path.join(app.getPath('userData'), 'application-platforms.json');
}
function readPlatforms() {
  try {
    const parsed = JSON.parse(fs.readFileSync(platformStorePath(), 'utf8'));
    return Array.isArray(parsed) ? parsed.filter(x => typeof x === 'string') : [];
  } catch { return []; }
}
function rememberPlatform(hostname) {
  const known = readPlatforms();
  if (!known.includes(hostname)) {
    known.push(hostname);
    fs.mkdirSync(path.dirname(platformStorePath()), { recursive: true });
    fs.writeFileSync(platformStorePath(), JSON.stringify(known, null, 2), { mode: 0o600 });
  }
}
function validHttpsUrl(rawUrl) {
  try {
    const url = new URL(rawUrl);
    return url.protocol === 'https:' && url.hostname && !url.username && !url.password ? url : null;
  } catch { return null; }
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 850,
    minWidth: 980,
    minHeight: 650,
    frame: false,
    title: 'Menix Job Assistant',
    icon: path.join(__dirname, 'assets', 'menix.ico'),
    backgroundColor: '#0b1020',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webviewTag: true
    }
  });
  mainWindow = win;
  win.on('closed', () => { if (mainWindow === win) mainWindow = null; });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => {
    const safe = validHttpsUrl(url);
    if (safe) openApplicationBrowser(safe.href, true);
    return { action: 'deny' };
  });
  win.webContents.on('will-attach-webview', (event, webPreferences, params) => {
    const sourceUrl = validHttpsUrl(params.src || '');
    if (!sourceUrl || params.partition !== APPLICATION_PARTITION) {
      event.preventDefault();
      return;
    }
    webPreferences.nodeIntegration = false;
    webPreferences.contextIsolation = true;
    webPreferences.sandbox = true;
    webPreferences.webSecurity = true;
    delete webPreferences.preload;
    webPreferences.allowRunningInsecureContent = false;
    webPreferences.webviewTag = false;
  });
  win.webContents.on('did-attach-webview', (_event, guest) => {
    guest.setWindowOpenHandler(({ url }) => {
      const safe = validHttpsUrl(url);
      if (safe) openApplicationBrowser(safe.href, true);
      return { action: 'deny' };
    });
    guest.on('will-navigate', (event, url) => {
      if (!validHttpsUrl(url)) event.preventDefault();
    });
    guest.on('will-redirect', (event, url) => {
      if (!validHttpsUrl(url)) event.preventDefault();
    });
  });
}

function openApplicationBrowser(url, isPopup = false) {
  const safeUrl = validHttpsUrl(url);
  if (!safeUrl) throw new Error('For safety, application links must use HTTPS.');

  if (applicationWindow && !applicationWindow.isDestroyed() && !isPopup) {
    applicationWindow.loadURL(safeUrl.href);
    applicationWindow.show();
    applicationWindow.focus();
  } else {
    const browser = new BrowserWindow({
      width: 1180,
      height: 850,
      minWidth: 800,
      minHeight: 600,
      icon: path.join(__dirname, 'assets', 'menix.ico'),
      title: `Job application — ${safeUrl.hostname}`,
      backgroundColor: '#ffffff',
      webPreferences: {
        partition: APPLICATION_PARTITION,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    });
    if (!isPopup) applicationWindow = browser;
    browser.webContents.setWindowOpenHandler(({ url: popupUrl }) => {
      const popupSafe = validHttpsUrl(popupUrl);
      if (popupSafe) openApplicationBrowser(popupSafe.href, true);
      return { action: 'deny' };
    });
    browser.webContents.on('will-navigate', (event, nextUrl) => {
      if (!validHttpsUrl(nextUrl)) event.preventDefault();
    });
    browser.on('closed', () => {
      if (applicationWindow !== browser) return;
      applicationWindow = null;
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('application-window-closed');
    });
    browser.loadURL(safeUrl.href);
  }
  rememberPlatform(safeUrl.hostname);
  return { opened: true, hostname: safeUrl.hostname };
}

ipcMain.handle('application-platform-status', (_event, rawUrl) => {
  const url = validHttpsUrl(rawUrl);
  if (!url) return { valid: false, reason: 'Only HTTPS application links are supported.' };
  const known = readPlatforms();
  return { valid: true, hostname: url.hostname, firstVisit: !known.includes(url.hostname) };
});
ipcMain.handle('window:minimize', event => {
  BrowserWindow.fromWebContents(event.sender)?.minimize();
});
ipcMain.handle('window:toggle-maximize', event => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (!win) return false;
  if (win.isMaximized()) win.unmaximize();
  else win.maximize();
  return win.isMaximized();
});
ipcMain.handle('window:close', event => {
  BrowserWindow.fromWebContents(event.sender)?.close();
});
ipcMain.handle('open-application', (_event, rawUrl) => openApplicationBrowser(rawUrl));
ipcMain.handle('close-application-window', () => {
  if (!applicationWindow || applicationWindow.isDestroyed()) return { closed: false };
  applicationWindow.close();
  return { closed: true };
});
ipcMain.handle('application-page-info', () => {
  if (!applicationWindow || applicationWindow.isDestroyed()) return { available: false };
  try { const page = new URL(applicationWindow.webContents.getURL()); return { available: page.protocol === 'https:', hostname: page.hostname }; }
  catch { return { available: false }; }
});
ipcMain.handle('autofill-application', async (_event, rawProfile) => {
  if (!applicationWindow || applicationWindow.isDestroyed()) throw new Error('Open a job application page first.');
  const allowed = ['fullName', 'email', 'phone', 'linkedinUrl', 'githubUrl', 'portfolioUrl', 'headline', 'skills', 'preferredTitles', 'preferredLocations', 'education', 'projects', 'experience'];
  const profile = Object.fromEntries(allowed.map(key => [key, String(rawProfile?.[key] || '').slice(0, 4000)]));
  if (applicationWindow.webContents.isLoading()) await new Promise(resolve => applicationWindow.webContents.once('did-finish-load', resolve));
  const payload = JSON.stringify(profile);
  return applicationWindow.webContents.executeJavaScript(`(() => {
    const profile = ${payload};
    const fields = [...document.querySelectorAll('input, textarea')].filter(el => {
      const type = (el.type || 'text').toLowerCase();
      return !el.disabled && !el.readOnly && !el.value && el.getClientRects().length &&
        (el.tagName === 'TEXTAREA' || ['text', 'email', 'tel', 'url'].includes(type));
    });
    const used = new Set(); let filled = 0;
    for (const el of fields) {
      const label = [el.name, el.id, el.placeholder, el.getAttribute('aria-label'), el.autocomplete,
        ...(el.labels ? [...el.labels].map(label => label.textContent) : [])].join(' ').toLowerCase();
      let key = null;
      if (/email|e-mail/.test(label)) key = 'email';
      else if (/phone|mobile|telephone/.test(label)) key = 'phone';
      else if (/full.?name|your name|candidate name/.test(label)) key = 'fullName';
      else if (/linkedin/.test(label)) key = 'linkedinUrl';
      else if (/github/.test(label)) key = 'githubUrl';
      else if (/portfolio|personal website|website url/.test(label)) key = 'portfolioUrl';
      else if (/skill|technology|tech stack/.test(label)) key = 'skills';
      else if (/education|degree|university|college/.test(label)) key = 'education';
      else if (/project|portfolio description/.test(label)) key = 'projects';
      else if (/experience|work history|employment history|professional background/.test(label)) key = 'experience';
      else if (/preferred title|target title|professional headline|current title/.test(label)) key = 'headline';
      else if (/city|location|country/.test(label)) key = 'preferredLocations';
      if (!key || used.has(key) || !profile[key]) continue;
      const setter = Object.getOwnPropertyDescriptor(el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value')?.set;
      setter?.call(el, profile[key]);
      el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
      used.add(key); filled++;
    }
    return { filled };
  })()`);
});
ipcMain.handle('open-external-help', async (_event, rawUrl) => {
  const url = validHttpsUrl(rawUrl);
  if (!url) throw new Error('Only HTTPS links are supported.');
  await shell.openExternal(url.href);
  return { opened: true };
});

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  // Initialize the persistent partition early so its cookie store is ready before use.
  session.fromPartition(APPLICATION_PARTITION);
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
