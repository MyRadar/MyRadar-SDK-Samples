/**
 * Live flights from Flightradar24's web map feed (data-cloud.flightradar24.com). It only sends
 * CORS headers for flightradar24.com, so it goes through the same-origin proxy (see
 * `vite.config.ts`). Besides an area, the feed filters by flight number, callsign or
 * registration, which is what the search uses.
 */
import { FR24_API } from './config';
import type { Aircraft } from './geo';

/**
 * One feed row: [hex, lat, lon, track, altitude ft, ground speed kt, squawk, radar, type,
 * registration, timestamp s, origin IATA, destination IATA, flight number, on ground,
 * vertical rate fpm, callsign, glider, airline ICAO].
 */
type Fr24Row = [string, number, number, number, number, number, string, string, string, string, number, string, string, string, number, number, string, number, string];

const FEED_PARAMS = {
  faa: '1',
  satellite: '1',
  mlat: '1',
  flarm: '1',
  adsb: '1',
  gnd: '1',
  air: '1',
  vehicles: '0',
  estimated: '1',
  maxage: '14400',
  gliders: '1',
  stats: '0',
};

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

function toAircraft(id: string, row: Fr24Row, nowSeconds: number): Aircraft | null {
  const [hex, lat, lon, track, altitude, speed, squawk, , type, registration, timestamp, origin, destination, flightNumber, onGround, verticalRate, callsign, , airline] =
    row;
  if (typeof lat !== 'number' || typeof lon !== 'number') return null;
  return {
    id,
    hex: (hex || '').toLowerCase(),
    callsign: (callsign || flightNumber || '').trim(),
    flightNumber: flightNumber || undefined,
    airlineIcao: airline || undefined,
    origin: origin || undefined,
    destination: destination || undefined,
    registration: registration || undefined,
    type: type || undefined,
    lat,
    lon,
    altitude: onGround ? 'ground' : typeof altitude === 'number' ? altitude : null,
    groundSpeedKnots: typeof speed === 'number' ? speed : null,
    track: typeof track === 'number' ? track : null,
    verticalRateFpm: typeof verticalRate === 'number' ? verticalRate : null,
    squawk: squawk || undefined,
    seenSeconds: typeof timestamp === 'number' ? Math.max(0, nowSeconds - timestamp) : 0,
  };
}

async function feed(filter: Record<string, string>): Promise<Aircraft[]> {
  const query = new URLSearchParams({ ...FEED_PARAMS, ...filter });
  const response = await fetch(`${FR24_API}/zones/fcgi/feed.js?${query}`);
  if (!response.ok) throw new Error(`HTTP ${response.status} from Flightradar24`);
  const data = (await response.json()) as Record<string, unknown>;
  const now = Date.now() / 1000;
  const list: Aircraft[] = [];
  for (const [id, value] of Object.entries(data)) {
    // rows are arrays; full_count, version and stats are not
    if (!Array.isArray(value)) continue;
    const aircraft = toAircraft(id, value as Fr24Row, now);
    if (aircraft) list.push(aircraft);
  }
  return list;
}

/** Aircraft inside a bounding box, in degrees. */
export function aircraftInBounds(north: number, south: number, west: number, east: number): Promise<Aircraft[]> {
  const bounds = [clamp(north, -90, 90), clamp(south, -90, 90), clamp(west, -180, 180), clamp(east, -180, 180)].map((v) => v.toFixed(3)).join(',');
  return feed({ bounds });
}

/** Flights with an airline flight number (`AA1643`), anywhere. */
export function aircraftByFlightNumber(flightNumber: string): Promise<Aircraft[]> {
  return feed({ flight: flightNumber });
}

/** Flights broadcasting a callsign (`AAL1643`), anywhere. */
export function aircraftByCallsign(callsign: string): Promise<Aircraft[]> {
  return feed({ callsign });
}

/** Flights by an aircraft registration (`N101NN`), anywhere. */
export function aircraftByRegistration(registration: string): Promise<Aircraft[]> {
  return feed({ reg: registration });
}

/**
 * The latest report for a flight being tracked, or null when the feed no longer has it. Asks by
 * callsign (a small answer), falling back to a box around the last position.
 */
export async function latestPosition(last: Aircraft): Promise<Aircraft | null> {
  const list = last.callsign
    ? await aircraftByCallsign(last.callsign)
    : await aircraftInBounds(last.lat + 1.5, last.lat - 1.5, last.lon - 2, last.lon + 2);
  return list.find((a) => a.id === last.id) ?? null;
}
