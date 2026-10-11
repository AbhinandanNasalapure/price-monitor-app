self.addEventListener('push', (event) => {
  let payload = {};
  try { payload = event.data ? event.data.json() : {}; } catch { payload = { body: event.data?.text() ?? '' }; }
  event.waitUntil(self.registration.showNotification(payload.title || 'Price Monitor', {
    body: payload.body || 'A tracked product price changed.',
    data: { url: payload.url || '/' },
    tag: 'price-monitor-alert',
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const requested = new URL(event.notification.data?.url || '/', self.location.origin);
  const target = requested.origin === self.location.origin ? requested.href : self.location.origin;
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
    const existing = clients.find((client) => 'focus' in client);
    if (existing) return existing.navigate(target).then(() => existing.focus());
    return self.clients.openWindow(target);
  }));
});
