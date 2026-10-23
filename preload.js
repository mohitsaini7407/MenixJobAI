const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('menixDesktop', {
  platform: process.platform,
  appName: 'Menix Job Assistant',
  minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
  toggleMaximizeWindow: () => ipcRenderer.invoke('window:toggle-maximize'),
  closeWindow: () => ipcRenderer.invoke('window:close'),
  openApplication: (url) => ipcRenderer.invoke('open-application', url),
  openExternalHelp: (url) => ipcRenderer.invoke('open-external-help', url),
  onEmbeddedBrowserPopup: (callback) => {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, url) => {
      if (typeof url === 'string') callback(url);
    };
    ipcRenderer.on('embedded-browser-popup', listener);
    return () => ipcRenderer.removeListener('embedded-browser-popup', listener);
  }
});
