/**
 * Recorded tracks from adsb.lol's trace files (ODbL). Flightradar24's feed has no history, so
 * these fill in the part of the flight before tracking started. adsb.lol is often slow or down,
 * so every request has a timeout and callers treat a failure as "no history yet".
 */
import { ADSB_TRACES } from './config';
import type { TrackPoint } from './geo';

const TIMEOUT_MS = 15_000;

/**
 * Recorded positions for the aircraft's current leg from one adsb.lol trace file: `recent` is a
 * few KB covering the last minutes, `full` covers the whole day (UTC). Legs split on the
 * "new leg" flag, on becoming airborne, and on gaps longer than 30 minutes.
 */
export async function traceFor(hex: string, kind: 'full' | 'recent'): Promise<TrackPoint[]> {
  const lower = hex.toLowerCase();
  const response = await fetch(`${ADSB_TRACES}/data/traces/${lower.slice(-2)}/trace_${kind}_${lower}.json`, { signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!response.ok) throw new Error(`HTTP ${response.status} from adsb.lol`);
  const data = (await response.json()) as { timestamp?: number; trace?: unknown[][] };
  const base = data.timestamp ?? 0;
  const points: TrackPoint[] = [];
  for (const row of data.trace ?? []) {
    const [offset, lat, lon, altRaw] = row;
    if (typeof offset !== 'number' || typeof lat !== 'number' || typeof lon !== 'number') continue;
    const onGround = altRaw === 'ground';
    const altitude = typeof altRaw === 'number' ? altRaw : null;
    const flags = typeof row[6] === 'number' ? row[6] : 0;
    const timestamp = (base + offset) * 1000;
    const previous = points[points.length - 1];
    let newLeg = (flags & 2) !== 0 || (previous?.onGround === true && !onGround);
    if (previous && timestamp - previous.timestamp > 30 * 60_000) newLeg = true;
    if (newLeg) points.length = 0;
    points.push({ lat, lon, timestamp, altitude, onGround });
  }
  const firstAirborne = points.findIndex((p) => !p.onGround);
  return firstAirborne > 0 ? points.slice(firstAirborne) : points;
}
