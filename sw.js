// Retired (web transition plan, phase 5, Nov 2026). This file used to be the old dashboard's Web Push worker. It stays only
// so a browser that still has it registered picks up this version on its next update check, cancels its push subscription
// and unregisters itself. The phone sends the notifications now. Nothing registers this worker any more.
self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    try {
      const sub = await self.registration.pushManager.getSubscription()
      if (sub) await sub.unsubscribe()
    } catch { /* nothing subscribed */ }
    await self.registration.unregister()
  })())
})
