/* FakeJIRA service worker: offline app shell, cached assets and push notifications. */
const CACHE = 'fakejira-v4';
/** Last good response of each API read, for browsing while offline. Cleared on logout. */
const API_CACHE = 'fakejira-api-v1';
const SHELL = ['/', '/favicon.svg', '/manifest.json', '/icons/icon-192.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE && key !== API_CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/')) {
    if (cacheableApi(url.pathname)) event.respondWith(networkFirstApi(request));
    // Other API calls (live events, auth, public endpoints) always go straight to the network.
    return;
  }

  if (request.mode === 'navigate') {
    // Network first so deploys show up immediately; the cached shell keeps the app opening offline.
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          if (response.ok) caches.open(CACHE).then((cache) => cache.put('/', copy));
          return response;
        })
        .catch(() => caches.match('/')),
    );
    return;
  }

  if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/')) {
    // Hashed build assets never change: cache first.
    event.respondWith(
      caches.match(request).then((cached) => cached || fetch(request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })),
    );
  }
});

function cacheableApi(path) {
  if (path === '/api/auth/me') return true;
  return !/^\/api\/(events|auth|public|health|metrics|inbound|calendar\/feed)/.test(path) && !path.includes('/download');
}

/** Network first; when the network is down, the last copy is served with an X-FakeJIRA-Offline marker. */
async function networkFirstApi(request) {
  try {
    const response = await fetch(request);
    if (response.ok && (response.headers.get('Content-Type') || '').includes('json')) {
      const copy = response.clone();
      caches.open(API_CACHE).then((cache) => cache.put(request.url, copy));
    } else if (response.status === 401) {
      caches.delete(API_CACHE);
    }
    return response;
  } catch (error) {
    const cached = await caches.match(request.url, { cacheName: API_CACHE });
    if (!cached) throw error;
    const headers = new Headers(cached.headers);
    headers.set('X-FakeJIRA-Offline', '1');
    return new Response(await cached.blob(), { status: 200, headers });
  }
}

self.addEventListener('message', (event) => {
  if (event.data === 'clear-api-cache') event.waitUntil(caches.delete(API_CACHE));
});

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { body: event.data ? event.data.text() : '' };
  }
  event.waitUntil(self.registration.showNotification(data.title || 'FakeJIRA', {
    body: data.body || '',
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    tag: data.tag,
    data: { url: data.url || '/notifications' },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = new URL(event.notification.data?.url || '/', self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => w.url.startsWith(self.location.origin));
      if (open) {
        return open.focus().then((w) => (w && 'navigate' in w ? w.navigate(target) : w));
      }
      return self.clients.openWindow(target);
    }),
  );
});
