/**
 * Mapbox access token for the demo. Set `VITE_MAPBOX_TOKEN` in `.env.local` (see
 * `.env.example`) or pass `?token=pk....`.
 */
export const MAPBOX_TOKEN = (import.meta.env.VITE_MAPBOX_TOKEN as string | undefined) || '';

export const MAP_STYLE = 'mapbox://styles/mapbox/dark-v11';

/**
 * MyRadar SDK API key for the gateway at https://sdkwrapperapi.azure-api.net. Set
 * `VITE_MYRADAR_API_KEY` in `.env.local` (see `.env.example`) or pass `?apiKey=...`.
 */
export const MYRADAR_API_KEY = (import.meta.env.VITE_MYRADAR_API_KEY as string | undefined) || '';

/** Over the central US: plenty of flights and usually some weather. */
export const INITIAL_VIEW = { center: [-89.5, 38.8] as [number, number], zoom: 5.3 };

/**
 * Same-origin prefix proxied to the MyRadar SDK gateway. The gateway's `/live/alerts/v2/geojson`
 * and `/v2/lightning/flashes/*` operations do not return `Access-Control-Allow-Origin` yet, so the
 * warnings and lightning overlays go through the dev-server proxy; set to `undefined` (or pass
 * `?direct`) once CORS is enabled on those operations.
 */
export const MYRADAR_PROXY_HOST: string | undefined = '/myradar-api';

/** Same-origin prefixes proxied to Flightradar24's feed and adsb.lol's trace files (see `vite.config.ts`). */
export const FR24_API = '/fr24';
export const ADSB_TRACES = '/adsb-traces';

/** How often the tracked flight's position is refreshed. */
export const AIRCRAFT_POLL_MS = 6000;
