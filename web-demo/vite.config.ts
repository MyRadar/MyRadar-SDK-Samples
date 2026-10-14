import { defineConfig, type ProxyOptions } from 'vite';

/**
 * Flightradar24's feed only sends CORS headers for flightradar24.com, and adsb.lol's trace files send
 * none, so the dev and preview servers proxy them on the page's origin. A deployment needs an equivalent proxy (or a route for it on the
 * MyRadar SDK gateway).
 */
const proxyTo = (target: string, prefix: string, headers?: Record<string, string>): ProxyOptions => ({
  target,
  changeOrigin: true,
  secure: true,
  headers,
  rewrite: (path) => path.slice(prefix.length),
});

const proxy: Record<string, ProxyOptions> = {
  // The gateway's alerts and lightning operations do not send CORS headers yet; see config.ts.
  '/myradar-api': proxyTo('https://sdkwrapperapi.azure-api.net', '/myradar-api'),
  // FR24 only allows https://www.flightradar24.com as a CORS origin, and answers 451 to requests
  // referred from localhost, so the page's Referer and Origin aren't passed on.
  '/fr24': {
    ...proxyTo('https://data-cloud.flightradar24.com', '/fr24'),
    configure: (proxy) => {
      proxy.on('proxyReq', (req) => {
        req.removeHeader('referer');
        req.removeHeader('origin');
      });
    },
  },
  // recorded tracks; adsb.lol serves them to its own map
  '/adsb-traces': proxyTo('https://adsb.lol', '/adsb-traces', { Referer: 'https://adsb.lol/' }),
};

export default defineConfig({
  server: { port: 5173, open: false, proxy },
  preview: { port: 4173, proxy },
});
