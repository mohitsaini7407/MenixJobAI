const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('menixDesktop', {
  platform: process.platform,
  appName: 'Menix Job Assistant',
  minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
  toggleMaximizeWindow: () => ipcRenderer.invoke('window:toggle-maximize'),
  closeWindow: () => ipcRenderer.invoke('window:close'),
  getApplicationPlatformStatus: (url) => ipcRenderer.invoke('application-platform-status', url),
  openApplication: (url) => ipcRenderer.invoke('open-application', url),
  closeApplicationWindow: () => ipcRenderer.invoke('close-application-window'),
  onApplicationWindowClosed: (callback) => {
    const listener = () => callback();
    ipcRenderer.on('application-window-closed', listener);
    return () => ipcRenderer.removeListener('application-window-closed', listener);
  },
  getApplicationPageInfo: () => ipcRenderer.invoke('application-page-info'),
  autofillApplication: (profile) => ipcRenderer.invoke('autofill-application', profile),
  openExternalHelp: (url) => ipcRenderer.invoke('open-external-help', url)
});
