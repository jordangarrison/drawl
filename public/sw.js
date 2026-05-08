// drawl service worker — app-shell precache + offline navigation fallback.
// Bump CACHE_VERSION on every release that ships new shell assets.

const CACHE_VERSION = "drawl-v1";
const SHELL = [
  "/",
  "/index.html",
  "/styles.css",
  "/js/main.js",
  "/manifest.webmanifest",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-maskable-512.png",
  "/icons/apple-touch-icon-180.png",
  "/icons/favicon-32.png",
  "/icons/favicon-16.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION).then((cache) => cache.addAll(SHELL)),
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k)),
      ),
    ).then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;

  // SPA navigations: try network, fall back to cached /index.html offline.
  if (req.mode === "navigate") {
    event.respondWith(
      fetch(req).catch(() =>
        caches.match("/index.html").then((r) => r || Response.error()),
      ),
    );
    return;
  }

  // Same-origin assets: cache-first, populate on miss.
  const url = new URL(req.url);
  if (url.origin === self.location.origin) {
    event.respondWith(
      caches.match(req).then((cached) =>
        cached ||
        fetch(req).then((res) => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE_VERSION).then((c) => c.put(req, copy));
          }
          return res;
        }),
      ),
    );
  }
});
