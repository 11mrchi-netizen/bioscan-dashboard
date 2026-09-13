// Service worker for Web Push notifications.
// Quick-log actions write straight to Supabase via the quick-log Edge Function
// (using the per-subscription token embedded in the push payload) so a tap
// never has to open the dashboard. Anything else opens/focuses it.

const QUICK_LOG_URL = 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/quick-log';
const DASHBOARD_URL = 'https://11mrchi-netizen.github.io/bioscan-dashboard/';

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
  if (!event.data) return;

  let payload;
  try {
    payload = event.data.json();
  } catch {
    return;
  }

  const { title, body, tag, actions, data } = payload;

  event.waitUntil(
    self.registration.showNotification(title || 'Bioscan', {
      body: body || '',
      tag: tag || undefined,
      actions: Array.isArray(actions) ? actions : [],
      data: data || {},
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  const { action, notification } = event;
  notification.close();

  // No action (body tap) or the explicit "open app" action: focus/open the dashboard.
  if (!action || action === 'open_app') {
    event.waitUntil(openOrFocusDashboard(notification.data));
    return;
  }

  // Everything else is a quick-log intent: write directly, no page needed.
  const token = notification.data && notification.data.quicklogToken;
  if (!token) return;

  event.waitUntil(
    fetch(QUICK_LOG_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, intent: action }),
    }).catch(() => {
      // Best-effort; if the write fails there's no page open to report it to.
    })
  );
});

async function openOrFocusDashboard(data) {
  const targetUrl = (data && data.url) || DASHBOARD_URL;
  const allClients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  for (const client of allClients) {
    if (client.url.startsWith(DASHBOARD_URL) && 'focus' in client) {
      return client.focus();
    }
  }
  return self.clients.openWindow(targetUrl);
}
