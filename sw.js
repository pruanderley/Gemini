const CACHE_NAME = 'gemini-api-v13';
const ASSETS = [
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-512-maskable.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Cache-first só para os arquivos do próprio app (casca do PWA).
// Chamadas para Ollama (localhost) e Gemini (API do Google) sempre vão direto pra rede,
// nunca passam pelo cache — são conversas em tempo real, não fazem sentido offline.
self.addEventListener('fetch', (event) => {
  // Navegações (abrir o app pela URL raiz, recarregar a página):
  // serve o index.html do cache — é isso que faz o PWA abrir offline.
  if (event.request.mode === 'navigate') {
    event.respondWith(
      caches.match('./index.html').then((cached) => cached || fetch(event.request))
    );
    return;
  }

  const url = event.request.url;
  const isAppShell = ASSETS.some((asset) => url.endsWith(asset.replace('./', '')));

  if (!isAppShell) return; // deixa passar direto pra rede

  event.respondWith(
    caches.match(event.request).then((cached) => {
      return cached || fetch(event.request);
    })
  );
});
