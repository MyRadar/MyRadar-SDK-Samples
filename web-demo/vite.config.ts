import { defineConfig } from 'vite';

// Everything the demo loads (weather and flights) comes from the MyRadar SDK gateway, which sends
// CORS headers, so no proxies are needed.
export default defineConfig({
  server: { port: 5173, open: false },
  preview: { port: 4173 },
});
