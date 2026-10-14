/**
 * Airport and airline names for the codes in Flightradar24's feed, from tables in `src/data/`
 * (OurAirports and OpenFlights). They're loaded on first use so
 * they don't weigh down the initial page.
 */
import type { Aircraft, Airline, Airport, Route } from './geo';

type AirportRow = [icao: string, name: string, city: string, lat: number, lon: number];
type AirlineRow = [name: string, iata: string];

let airports: Promise<Record<string, AirportRow>> | null = null;
let airlines: Promise<Record<string, AirlineRow>> | null = null;

const loadAirports = () => (airports ??= import('./data/airports.json').then((m) => m.default as unknown as Record<string, AirportRow>));
const loadAirlines = () => (airlines ??= import('./data/airlines.json').then((m) => m.default as unknown as Record<string, AirlineRow>));

export async function airportByIata(iata: string): Promise<Airport | null> {
  const row = (await loadAirports())[iata.toUpperCase()];
  if (!row) return null;
  const [icao, name, city, lat, lon] = row;
  return { icao, iata: iata.toUpperCase(), name, location: city || undefined, lat, lon };
}

/** The route from the feed's origin and destination codes, when both airports are known. */
export async function routeFor(a: Pick<Aircraft, 'origin' | 'destination'>): Promise<Route | null> {
  if (!a.origin || !a.destination) return null;
  const [origin, destination] = await Promise.all([airportByIata(a.origin), airportByIata(a.destination)]);
  return origin && destination ? { airports: [origin, destination] } : null;
}

/** `AA1643` → `AA 1643`, the way it's printed on tickets. */
function formatFlightNumber(flightNumber: string, iata?: string): string {
  if (iata && flightNumber.startsWith(iata)) return `${iata} ${flightNumber.slice(iata.length)}`;
  const m = /^([A-Z0-9]{2})(\d+[A-Z]?)$/.exec(flightNumber);
  return m ? `${m[1]} ${m[2]}` : flightNumber;
}

/** The operating airline, from the feed's ICAO code (or the callsign's first three letters). */
export async function airlineFor(a: Pick<Aircraft, 'airlineIcao' | 'callsign' | 'flightNumber'>): Promise<Airline | null> {
  const icao = a.airlineIcao ?? (/^[A-Z]{3}\d/.test(a.callsign) ? a.callsign.slice(0, 3) : undefined);
  const row = icao ? (await loadAirlines())[icao] : undefined;
  const flightNumber = a.flightNumber ? formatFlightNumber(a.flightNumber, row?.[1] || undefined) : undefined;
  if (!row) return flightNumber ? { name: '', flightNumber } : null;
  return { name: row[0], iata: row[1] || undefined, icao, flightNumber };
}
