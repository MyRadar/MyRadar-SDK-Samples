/**
 * Flight data types, great-circle geometry and aircraft type names shared by the tracker.
 */

export interface Aircraft {
  /** Flightradar24's id for this flight (one leg of one aircraft). */
  id: string;
  /** ICAO 24-bit address, lower case hex, when known. */
  hex: string;
  /** Callsign as broadcast (`AAL1643`). */
  callsign: string;
  /** Airline flight number (`AA1643`). */
  flightNumber?: string;
  /** Operator's ICAO code (`AAL`). */
  airlineIcao?: string;
  /** Origin and destination IATA codes, as Flightradar24 has them. */
  origin?: string;
  destination?: string;
  registration?: string;
  /** ICAO aircraft type designator (`B738`). */
  type?: string;
  lat: number;
  lon: number;
  /** Barometric altitude in feet, or 'ground'. */
  altitude: number | 'ground' | null;
  groundSpeedKnots: number | null;
  /** True track over ground, degrees. */
  track: number | null;
  verticalRateFpm: number | null;
  squawk?: string;
  /** Seconds since the last position report. */
  seenSeconds: number;
}

export interface Airport {
  icao: string;
  iata?: string;
  name: string;
  location?: string;
  lat: number;
  lon: number;
}

export interface Route {
  airports: Airport[];
}

export interface Airline {
  name: string;
  iata?: string;
  icao?: string;
  /** Flight number as printed on a ticket (`AA 1643`). */
  flightNumber?: string;
}

export interface TrackPoint {
  lat: number;
  lon: number;
  timestamp: number;
  altitude: number | null;
  onGround: boolean;
}

// ---------------------------------------------------------------------------------------------
// Geometry helpers (nautical miles, degrees)

const EARTH_RADIUS_NM = 3440.065;
const rad = (d: number) => (d * Math.PI) / 180;
const deg = (r: number) => (r * 180) / Math.PI;

export const NM_TO_MI = 1.15078;

export function distanceNm(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const dLat = rad(bLat - aLat);
  const dLon = rad(bLon - aLon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_NM * Math.asin(Math.min(1, Math.sqrt(h)));
}

export function initialBearing(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const y = Math.sin(rad(bLon - aLon)) * Math.cos(rad(bLat));
  const x = Math.cos(rad(aLat)) * Math.sin(rad(bLat)) - Math.sin(rad(aLat)) * Math.cos(rad(bLat)) * Math.cos(rad(bLon - aLon));
  const b = deg(Math.atan2(y, x));
  return b < 0 ? b + 360 : b;
}

/** Points along the great circle from a to b (inclusive), as [lon, lat] pairs. */
export function greatCircle(aLat: number, aLon: number, bLat: number, bLon: number, segments = 64): Array<[number, number]> {
  const lat1 = rad(aLat);
  const lon1 = rad(aLon);
  const lat2 = rad(bLat);
  const lon2 = rad(bLon);
  const d = distanceNm(aLat, aLon, bLat, bLon) / EARTH_RADIUS_NM;
  if (d < 1e-9) return [[aLon, aLat], [bLon, bLat]];
  const out: Array<[number, number]> = [];
  for (let i = 0; i <= segments; i++) {
    const f = i / segments;
    const A = Math.sin((1 - f) * d) / Math.sin(d);
    const B = Math.sin(f * d) / Math.sin(d);
    const x = A * Math.cos(lat1) * Math.cos(lon1) + B * Math.cos(lat2) * Math.cos(lon2);
    const y = A * Math.cos(lat1) * Math.sin(lon1) + B * Math.cos(lat2) * Math.sin(lon2);
    const z = A * Math.sin(lat1) + B * Math.sin(lat2);
    let lon = deg(Math.atan2(y, x));
    // keep the line continuous across the antimeridian
    if (out.length > 0) {
      const prev = out[out.length - 1][0];
      while (lon - prev > 180) lon -= 360;
      while (prev - lon > 180) lon += 360;
    }
    out.push([lon, deg(Math.atan2(z, Math.sqrt(x * x + y * y)))]);
  }
  return out;
}

/** Readable names for common ICAO aircraft type codes. */
export const AIRCRAFT_TYPE_NAMES: Record<string, string> = {
  A19N: 'Airbus A319neo', A20N: 'Airbus A320neo', A21N: 'Airbus A321neo', A318: 'Airbus A318', A319: 'Airbus A319',
  A320: 'Airbus A320', A321: 'Airbus A321', A332: 'Airbus A330-200', A333: 'Airbus A330-300', A338: 'Airbus A330-800neo',
  A339: 'Airbus A330-900neo', A359: 'Airbus A350-900', A35K: 'Airbus A350-1000', A388: 'Airbus A380-800',
  B37M: 'Boeing 737 MAX 7', B38M: 'Boeing 737 MAX 8', B39M: 'Boeing 737 MAX 9', B712: 'Boeing 717-200',
  B733: 'Boeing 737-300', B734: 'Boeing 737-400', B735: 'Boeing 737-500', B736: 'Boeing 737-600', B737: 'Boeing 737-700',
  B738: 'Boeing 737-800', B739: 'Boeing 737-900', B744: 'Boeing 747-400', B748: 'Boeing 747-8', B752: 'Boeing 757-200',
  B753: 'Boeing 757-300', B762: 'Boeing 767-200', B763: 'Boeing 767-300', B764: 'Boeing 767-400', B772: 'Boeing 777-200',
  B77L: 'Boeing 777-200LR', B773: 'Boeing 777-300', B77W: 'Boeing 777-300ER', B788: 'Boeing 787-8', B789: 'Boeing 787-9',
  B78X: 'Boeing 787-10', MD11: 'McDonnell Douglas MD-11', MD88: 'McDonnell Douglas MD-88', E170: 'Embraer E170',
  E175: 'Embraer E175', E190: 'Embraer E190', E195: 'Embraer E195', E75L: 'Embraer E175', E45X: 'Embraer ERJ-145',
  CRJ2: 'Bombardier CRJ-200', CRJ7: 'Bombardier CRJ-700', CRJ9: 'Bombardier CRJ-900', BCS1: 'Airbus A220-100',
  BCS3: 'Airbus A220-300', DH8D: 'Dash 8 Q400', AT76: 'ATR 72-600', C172: 'Cessna 172', C208: 'Cessna Caravan',
  PC12: 'Pilatus PC-12', SR22: 'Cirrus SR22', C56X: 'Cessna Citation Excel', CL35: 'Challenger 350', GLF6: 'Gulfstream G650',
  C25B: 'Citation CJ3', BE20: 'King Air 200', B350: 'King Air 350', H60: 'Sikorsky H-60', EC35: 'Airbus H135',
};

export function aircraftTypeName(a: Pick<Aircraft, 'type'>): string {
  if (a.type) return AIRCRAFT_TYPE_NAMES[a.type] ?? a.type;
  return 'Unknown type';
}
