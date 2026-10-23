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
