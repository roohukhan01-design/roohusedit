const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('ClipForgeDesktop', Object.freeze({
  platform: 'windows',
  nativeVideoExport: false,
  note: 'Desktop edition uses browser-supported media features. Android-only hardware export and offline Vosk captions are not available.'
}));
