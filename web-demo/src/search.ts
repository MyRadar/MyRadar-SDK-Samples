/**
 * Flight search: turns what a person types (an airline flight number such as `AA 1643`, the
 * callsign `AAL1643`, or a registration such as `N101NN`) into the live flight, using
 * Flightradar24's feed filters.
 */
import { aircraftByCallsign, aircraftByFlightNumber, aircraftByRegistration, aircraftInBounds } from './fr24';
import type { Aircraft, Route } from './geo';
import { routeFor } from './lookup';

export interface SearchResult {
  /** The query, normalized (upper case, no spaces). */
  query: string;
  /** Live flights matching the query, best match first. Empty when nothing is flying it now. */
  matches: Aircraft[];
}

export function normalizeQuery(query: string): string {
  return query.toUpperCase().replace(/[\s-]+/g, '');
}

/** Prefers aircraft that are airborne and reported recently. */
function rank(list: Aircraft[]): Aircraft[] {
  const score = (a: Aircraft) => (a.altitude === 'ground' ? 1000 : 0) + a.seenSeconds;
  return [...list].sort((x, y) => score(x) - score(y));
}

export async function searchFlight(raw: string): Promise<SearchResult> {
  const query = normalizeQuery(raw);
  if (!query) return { query, matches: [] };

  // The query could be any of the three; the feed answers each quickly, so ask all at once.
  const results = await Promise.allSettled([aircraftByFlightNumber(query), aircraftByCallsign(query), aircraftByRegistration(query)]);
  const found = results.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
  // "not flying" would be wrong when the lookups failed, so say that instead
  const failure = results.find((r): r is PromiseRejectedResult => r.status === 'rejected');
  if (found.length === 0 && failure) throw failure.reason instanceof Error ? failure.reason : new Error(String(failure.reason));

  const seen = new Set<string>();
  const matches = rank(found).filter((a) => {
    if (seen.has(a.id)) return false;
    seen.add(a.id);
    return true;
  });
  return { query, matches };
}

export interface Suggestion {
  aircraft: Aircraft;
  route: Route;
}

/** ICAO type designators of airliners (Airbus, Boeing, Embraer E-jets, CRJs, A220, Q400, ATR), so suggestions skip business jets. */
const AIRLINER = /^(A3|A2|BCS|B7|B3|E1[79]|E7|E29|CRJ|DH8|AT7)/;

/**
 * A few airline flights in the air inside a box, with known routes, to give people something to
 * search for. Picks airliners at cruise so they stay interesting for a while.
 */
export async function suggestFlights(north: number, south: number, west: number, east: number, count = 5): Promise<Suggestion[]> {
  const nearby = await aircraftInBounds(north, south, west, east);
  const candidates = nearby.filter(
    (a) =>
      a.flightNumber && a.origin && a.destination && AIRLINER.test(a.type ?? '') && typeof a.altitude === 'number' && a.altitude > 20000 && a.seenSeconds < 60,
  );
  // one per airline first, so the list isn't five flights from the same carrier
  const airlines = new Set<string>();
  const picked: Aircraft[] = [];
  for (const a of candidates) {
    const code = a.airlineIcao ?? a.callsign.slice(0, 3);
    if (airlines.has(code)) continue;
    airlines.add(code);
    picked.push(a);
    if (picked.length === count) break;
  }
  for (const a of candidates) {
    if (picked.length >= count) break;
    if (!picked.includes(a)) picked.push(a);
  }
  const routes = await Promise.all(picked.map((a) => routeFor(a)));
  return picked.flatMap((aircraft, i) => {
    const route = routes[i];
    return route ? [{ aircraft, route }] : [];
  });
}
