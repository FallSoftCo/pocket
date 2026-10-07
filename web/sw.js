// Installability only. No authenticated responses, transcripts, recordings or drafts
// enter CacheStorage. Offline execution and closed-app web push are unavailable.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',()=>{});
