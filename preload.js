const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('menixDesktop', {
  platform: process.platform,
  appName: 'Menix Job Assistant',
  minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
  toggleMaximizeWindow: () => ipcRenderer.invoke('window:toggle-maximize'),
  closeWindow: () => ipcRenderer.invoke('window:close'),
  openApplication: (url) => ipcRenderer.invoke('open-application', url),
  openExternalHelp: (url) => ipcRenderer.invoke('open-external-help', url)
});
