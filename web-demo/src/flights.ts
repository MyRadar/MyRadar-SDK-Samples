/**
 * Single-flight tracker: follows one aircraft, polls its position, and draws the track it has
 * flown, the planned route (already flown and ahead), the airports and the aircraft itself.
 */
import type { Feature, FeatureCollection, LineString } from 'geojson';
import mapboxgl, { type GeoJSONSource, type Map as MapboxMap } from 'mapbox-gl';
import { AIRCRAFT_POLL_MS } from './config';
import { traceFor } from './adsb';
import { latestPosition } from './fr24';
import { distanceNm, greatCircle, type Aircraft, type Airline, type Route, type TrackPoint } from './geo';
import { airlineFor, routeFor } from './lookup';

export type FlightPhase = 'climbing' | 'cruising' | 'descending' | 'landed' | 'ground' | 'lost';

export interface TrackedFlight {
  aircraft: Aircraft;
  route: Route | null;
  /** A published route that doesn't fit where the aircraft is (stale or reused flight numbers), so it isn't used. */
  rejectedRoute: Route | null;
  airline: Airline | null;
  /** adsb.lol's recorded track once it arrives, followed by the positions received while tracking. */
  track: TrackPoint[];
  phase: FlightPhase;
  /** Nautical miles flown from the origin and remaining to the destination, when the route is known. */
  flownNm: number | null;
  remainingNm: number | null;
  /** Share of the trip flown, 0–1, when the route is known. */
  progress: number | null;
  /** Take-off time, when adsb.lol's recorded track reaches back to it. */
  departedAt: Date | null;
  /** Estimated start of the descent (3 nmi per 1,000 ft) and arrival, from ground speed. */
  descentAt: Date | null;
  eta: Date | null;
  /** When the position was last refreshed. */
  updatedAt: Date;
}

export interface FlightTrackerEvents {
  flight: (flight: TrackedFlight | null) => void;
  error: (error: Error) => void;
}

const SOURCE_TRACK = 'demo-track';
const SOURCE_ROUTE = 'demo-route';
/** adsb.lol is unreliable: the history is asked for a few times, this far apart, then given up on. */
const HISTORY_ATTEMPTS = 4;
const HISTORY_RETRY_MS = 30_000;
/** Positions older than this mean the aircraft is out of coverage, or has landed and switched off. */
const LOST_AFTER_S = 90;

const PLANE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" aria-hidden="true">
  <path fill="currentColor" d="M32 3c-2.2 0-4 3.4-4 7.6v12.9L5 37.3v5.4l23-7.4v12.1l-6.4 4.3v3.8L32 53l10.4 2.5v-3.8L36 47.4V35.3l23 7.4v-5.4L36 23.5V10.6C36 6.4 34.2 3 32 3z"/>
</svg>`;

const escapeHtml = (s: string): string =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string);

/**
 * Whether the aircraft is plausibly flying a route: the detour via its position is small next
 * to the route itself. Flight numbers get reused and route databases go stale, and a wrong
 * route would make the progress and arrival time confidently wrong.
 */
function routeFits(route: Route, a: Aircraft): boolean {
  const stops = route.airports;
  for (let i = 1; i < stops.length; i++) {
    const [o, d] = [stops[i - 1], stops[i]];
    const direct = distanceNm(o.lat, o.lon, d.lat, d.lon);
    const via = distanceNm(o.lat, o.lon, a.lat, a.lon) + distanceNm(a.lat, a.lon, d.lat, d.lon);
    if (via - direct <= Math.max(150, direct * 0.25)) return true;
  }
  return false;
}

export function flightLabel(f: Pick<TrackedFlight, 'aircraft' | 'airline'>): string {
  return f.airline?.flightNumber ?? (f.aircraft.callsign || f.aircraft.registration || f.aircraft.hex.toUpperCase());
}

export class FlightTracker {
  private map: MapboxMap;
  private timer: ReturnType<typeof setInterval> | null = null;
  private flight: TrackedFlight | null = null;
  private listeners: { [K in keyof FlightTrackerEvents]: Array<FlightTrackerEvents[K]> } = { flight: [], error: [] };
  private routeVisible = true;
  private followFlight = false;
  private installed = false;
  private inFlight = false;
  /** Set once the person pans or zooms, so a late-arriving route doesn't yank the view. */
  private userMoved = false;
  private planeMarker: mapboxgl.Marker | null = null;
  private airportMarkers: mapboxgl.Marker[] = [];
  private airportKey = '';

  constructor(map: MapboxMap) {
    this.map = map;
    // only gestures carry an originalEvent; fitBounds and easeTo don't
    const onMove = (e: object) => {
      if ('originalEvent' in e && e.originalEvent) this.userMoved = true;
    };
    map.on('dragstart', onMove);
    map.on('zoomstart', onMove);
  }

  on<K extends keyof FlightTrackerEvents>(event: K, listener: FlightTrackerEvents[K]): void {
    this.listeners[event].push(listener);
  }

  private emit<K extends keyof FlightTrackerEvents>(event: K, ...args: Parameters<FlightTrackerEvents[K]>): void {
    for (const l of this.listeners[event]) (l as (...a: unknown[]) => void)(...args);
  }

  /** Adds the line sources and layers; call on every `style.load`. */
  install(beforeId?: string): void {
    const map = this.map;
    const empty: FeatureCollection = { type: 'FeatureCollection', features: [] };
    map.addSource(SOURCE_ROUTE, { type: 'geojson', data: empty });
    map.addSource(SOURCE_TRACK, { type: 'geojson', data: empty });

    map.addLayer(
      {
        id: 'demo-route-flown',
        type: 'line',
        source: SOURCE_ROUTE,
        filter: ['==', ['get', 'part'], 'flown'],
        layout: { 'line-cap': 'butt' },
        paint: { 'line-color': '#4da3ff', 'line-width': 1.5, 'line-dasharray': [3, 3], 'line-opacity': 0.5 },
      },
      beforeId,
    );
    map.addLayer(
      {
        id: 'demo-route-ahead',
        type: 'line',
        source: SOURCE_ROUTE,
        filter: ['==', ['get', 'part'], 'ahead'],
        layout: { 'line-cap': 'butt' },
        paint: { 'line-color': '#4da3ff', 'line-width': 2.5, 'line-dasharray': [2, 1.5] },
      },
      beforeId,
    );
    map.addLayer(
      {
        id: 'demo-track-casing',
        type: 'line',
        source: SOURCE_TRACK,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#000000', 'line-width': 6, 'line-opacity': 0.35, 'line-blur': 2 },
      },
      beforeId,
    );
    map.addLayer(
      {
        id: 'demo-track-line',
        type: 'line',
        source: SOURCE_TRACK,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#ffffff', 'line-width': 3 },
      },
      beforeId,
    );
    this.installed = true;
    this.applyVisibility();
    this.pushLines();
  }

  get showRoute(): boolean {
    return this.routeVisible;
  }

  set showRoute(value: boolean) {
    this.routeVisible = value;
    this.applyVisibility();
  }

  get follow(): boolean {
    return this.followFlight;
  }

  set follow(value: boolean) {
    this.followFlight = value;
    if (value && this.flight) this.map.easeTo({ center: [this.flight.aircraft.lon, this.flight.aircraft.lat], duration: 600 });
  }

  get current(): TrackedFlight | null {
    return this.flight;
  }

  /** Starts tracking a flight (as found by the search) and looks up its route and airline. */
  async track(aircraft: Aircraft, hints: { route?: Route | null } = {}): Promise<void> {
    const flight: TrackedFlight = {
      aircraft,
      route: null,
      rejectedRoute: null,
      airline: null,
      track: [],
      phase: 'cruising',
      flownNm: null,
      remainingNm: null,
      progress: null,
      departedAt: null,
      descentAt: null,
      eta: null,
      updatedAt: new Date(),
    };
    this.flight = flight;
    this.addTrackPoint(flight, aircraft);
    if (hints.route) this.offerRoute(flight, hints.route);
    this.userMoved = false;
    this.followFlight = false;
    this.updateDerived();
    this.render();
    this.fitView();
    this.start();
    // in the background: nothing waits on adsb.lol
    void this.loadHistory(flight);

    // the airport and airline tables load on first use; draw what we have meanwhile
    const [route, airline] = await Promise.all([hints.route ? null : routeFor(aircraft), airlineFor(aircraft)]);
    if (this.flight !== flight) return;
    if (route) this.offerRoute(flight, route);
    flight.airline = airline;
    this.updateDerived();
    this.render();
    this.fitView();
  }

  /** Uses a route only when it fits the aircraft's position (diversions, stale flight numbers). */
  private offerRoute(flight: TrackedFlight, route: Route): void {
    if (routeFits(route, flight.aircraft)) {
      flight.route = route;
      flight.rejectedRoute = null;
    } else if (!flight.route) {
      flight.rejectedRoute = route;
    }
  }

  /**
   * Fills in the track from before tracking started with adsb.lol's recorded trace, quietly
   * retrying while it's unavailable. The small recent trace usually lands first; the full-day
   * trace reaches back to take-off and replaces it.
   */
  private async loadHistory(flight: TrackedFlight): Promise<void> {
    const hex = flight.aircraft.hex;
    if (!hex) return;
    for (let attempt = 0; attempt < HISTORY_ATTEMPTS; attempt++) {
      if (attempt > 0) await new Promise((resolve) => setTimeout(resolve, HISTORY_RETRY_MS));
      if (this.flight !== flight) return;
      let gotFull = false;
      await Promise.allSettled([
        traceFor(hex, 'recent').then((trace) => {
          if (!gotFull) this.applyHistory(flight, trace);
        }),
        traceFor(hex, 'full').then((trace) => {
          if (trace.length === 0) return;
          gotFull = true;
          this.applyHistory(flight, trace);
          // the full trace starts at take-off when this leg began today (UTC)
          const first = trace[0];
          if (first.altitude !== null && first.altitude < 5000) flight.departedAt = new Date(first.timestamp);
          this.render();
        }),
      ]);
      if (gotFull) return;
    }
  }

  /** Puts a recorded trace in front of the positions received live since it ends. */
  private applyHistory(flight: TrackedFlight, trace: TrackPoint[]): void {
    if (this.flight !== flight || trace.length === 0) return;
    const end = trace[trace.length - 1].timestamp;
    flight.track = [...trace, ...flight.track.filter((p) => p.timestamp > end)];
    this.render();
  }

  /** Adds a live report to the track (the feed itself has no history). */
  private addTrackPoint(flight: TrackedFlight, a: Aircraft): void {
    const timestamp = Date.now() - a.seenSeconds * 1000;
    const last = flight.track[flight.track.length - 1];
    // the feed repeats the last report until a new one arrives
    if (last && Math.abs(last.timestamp - timestamp) < 1000 && last.lat === a.lat && last.lon === a.lon) return;
    flight.track.push({ lat: a.lat, lon: a.lon, timestamp, altitude: typeof a.altitude === 'number' ? a.altitude : null, onGround: a.altitude === 'ground' });
  }

  /** Stops tracking and clears the map. */
  clear(): void {
    this.stop();
    this.flight = null;
    this.followFlight = false;
    this.render();
  }

  /** Frames the origin, destination and aircraft (or just the aircraft when the route isn't known). */
  fitView(force = false): void {
    const f = this.flight;
    if (!f || (this.userMoved && !force)) return;
    const points: Array<[number, number]> = [[f.aircraft.lon, f.aircraft.lat]];
    for (const ap of f.route?.airports ?? []) points.push([ap.lon, ap.lat]);
    if (points.length === 1) {
      this.map.easeTo({ center: points[0], zoom: Math.max(this.map.getZoom(), 6), duration: 800 });
      return;
    }
    const bounds = new mapboxgl.LngLatBounds(points[0], points[0]);
    for (const p of points) bounds.extend(p);
    // room for the legend (bottom left) and the labels around the airports
    this.map.fitBounds(bounds, { padding: { top: 90, bottom: 70, left: 120, right: 120 }, maxZoom: 8, duration: 900 });
  }

  private start(): void {
    this.stop();
    this.timer = setInterval(() => void this.poll(), AIRCRAFT_POLL_MS);
  }

  private stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private applyVisibility(): void {
    if (!this.installed) return;
    for (const id of ['demo-route-flown', 'demo-route-ahead', 'demo-track-casing', 'demo-track-line']) {
      if (this.map.getLayer(id)) this.map.setLayoutProperty(id, 'visibility', this.routeVisible ? 'visible' : 'none');
    }
    for (const m of this.airportMarkers) m.getElement().hidden = !this.routeVisible;
  }

  private async poll(): Promise<void> {
    const flight = this.flight;
    if (this.inFlight || !flight) return;
    this.inFlight = true;
    try {
      const latest = await latestPosition(flight.aircraft);
      if (this.flight !== flight) return;
      const now = Date.now();
      if (latest) {
        const routeChanged = latest.origin !== flight.aircraft.origin || latest.destination !== flight.aircraft.destination;
        flight.aircraft = latest;
        this.addTrackPoint(flight, latest);
        // a diversion shows up as a new destination
        if (routeChanged) {
          const route = await routeFor(latest);
          if (this.flight !== flight) return;
          flight.route = null;
          if (route) this.offerRoute(flight, route);
        }
      } else {
        // not in the feed: age the last report so the phase turns to "lost"
        flight.aircraft = { ...flight.aircraft, seenSeconds: flight.aircraft.seenSeconds + (now - flight.updatedAt.getTime()) / 1000 };
      }
      flight.updatedAt = new Date(now);
      this.updateDerived();
      this.render();
      if (this.followFlight) this.map.easeTo({ center: [flight.aircraft.lon, flight.aircraft.lat], duration: AIRCRAFT_POLL_MS * 0.9, easing: (t) => t });
    } catch (err) {
      this.emit('error', err instanceof Error ? err : new Error(String(err)));
    } finally {
      this.inFlight = false;
    }
  }

  private updateDerived(): void {
    const f = this.flight;
    if (!f) return;
    const a = f.aircraft;
    const stops = f.route?.airports;
    const origin = stops?.[0];
    const dest = stops?.[stops.length - 1];
    const gs = a.groundSpeedKnots;
    const now = Date.now();

    if (origin && dest) {
      f.flownNm = distanceNm(origin.lat, origin.lon, a.lat, a.lon);
      f.remainingNm = distanceNm(a.lat, a.lon, dest.lat, dest.lon);
      f.progress = f.flownNm + f.remainingNm > 0 ? f.flownNm / (f.flownNm + f.remainingNm) : null;
    } else {
      f.flownNm = f.remainingNm = f.progress = null;
    }

    const vr = a.verticalRateFpm ?? 0;
    if (a.seenSeconds > LOST_AFTER_S) f.phase = 'lost';
    else if (a.altitude === 'ground') f.phase = f.remainingNm !== null && f.remainingNm < 15 ? 'landed' : 'ground';
    else if (vr > 500) f.phase = 'climbing';
    else if (vr < -500) f.phase = 'descending';
    else f.phase = 'cruising';
    if (f.phase === 'landed') f.progress = 1;

    const airborne = f.phase === 'climbing' || f.phase === 'cruising' || f.phase === 'descending';
    f.eta = airborne && f.remainingNm !== null && gs && gs > 50 ? new Date(now + (f.remainingNm / gs) * 3_600_000) : null;
    // rule of thumb: descend 3 nmi for every 1,000 ft; only meaningful before the descent starts
    const descentNm = typeof a.altitude === 'number' ? (a.altitude / 1000) * 3 : null;
    f.descentAt =
      (f.phase === 'climbing' || f.phase === 'cruising') && descentNm !== null && f.remainingNm !== null && f.remainingNm > descentNm && gs && gs > 50
        ? new Date(now + ((f.remainingNm - descentNm) / gs) * 3_600_000)
        : null;
  }

  private render(): void {
    this.pushLines();
    this.updateMarkers();
    this.emit('flight', this.flight);
  }

  private pushLines(): void {
    const track = this.map.getSource(SOURCE_TRACK) as GeoJSONSource | undefined;
    const route = this.map.getSource(SOURCE_ROUTE) as GeoJSONSource | undefined;
    if (!track || !route) return;
    const f = this.flight;
    const trackFeatures: Array<Feature<LineString>> = [];
    const routeFeatures: Array<Feature<LineString>> = [];
    if (f) {
      const a = f.aircraft;
      if (f.track.length > 0) {
        const coords = f.track.map((p) => [p.lon, p.lat] as [number, number]);
        coords.push([a.lon, a.lat]);
        trackFeatures.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: coords }, properties: {} });
      }
      const stops = f.route?.airports;
      if (stops && stops.length >= 2) {
        const origin = stops[0];
        const dest = stops[stops.length - 1];
        routeFeatures.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: greatCircle(origin.lat, origin.lon, a.lat, a.lon) }, properties: { part: 'flown' } });
        if (f.phase !== 'landed') {
          routeFeatures.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: greatCircle(a.lat, a.lon, dest.lat, dest.lon) }, properties: { part: 'ahead' } });
        }
      }
    }
    track.setData({ type: 'FeatureCollection', features: trackFeatures });
    route.setData({ type: 'FeatureCollection', features: routeFeatures });
  }

  /** The aircraft and airports are HTML markers, so their labels can be styled like the rest of the page. */
  private updateMarkers(): void {
    const f = this.flight;
    if (!f) {
      this.planeMarker?.remove();
      this.planeMarker = null;
      for (const m of this.airportMarkers) m.remove();
      this.airportMarkers = [];
      this.airportKey = '';
      return;
    }

    const a = f.aircraft;
    if (!this.planeMarker) {
      const el = document.createElement('div');
      el.className = 'map-plane';
      el.innerHTML = `<div class="map-plane-icon">${PLANE_SVG}</div><div class="map-label map-plane-label"></div>`;
      this.planeMarker = new mapboxgl.Marker({ element: el, anchor: 'center' }).setLngLat([a.lon, a.lat]).addTo(this.map);
    }
    this.planeMarker.setLngLat([a.lon, a.lat]);
    const el = this.planeMarker.getElement();
    el.classList.toggle('stale', f.phase === 'lost');
    const icon = el.querySelector<HTMLElement>('.map-plane-icon');
    // the marker itself doesn't rotate so the label stays upright; only the icon does (the map is kept north-up)
    if (icon) icon.style.transform = `rotate(${a.track ?? 0}deg)`;
    const label = el.querySelector<HTMLElement>('.map-plane-label');
    if (label) {
      const alt = a.altitude === 'ground' ? 'On ground' : a.altitude === null ? '' : `${Math.round(a.altitude).toLocaleString()} ft`;
      label.innerHTML = `<b>${escapeHtml(flightLabel(f))}</b>${alt ? ` ${alt}` : ''}`;
    }

    const stops = f.route?.airports ?? [];
    const key = stops.map((s) => s.icao).join('>');
    if (key === this.airportKey) return;
    this.airportKey = key;
    for (const m of this.airportMarkers) m.remove();
    this.airportMarkers = stops.map((ap, i) => {
      const dot = document.createElement('div');
      dot.className = 'map-airport';
      const city = ap.location ?? ap.name;
      // origin label on the left, the rest on the right, so the two rarely collide with the route
      dot.innerHTML = `<div class="map-label map-airport-label ${i === 0 ? 'left' : 'right'}"><b>${escapeHtml(ap.iata ?? ap.icao)}</b> ${escapeHtml(city)}</div>`;
      dot.title = ap.name;
      dot.hidden = !this.routeVisible;
      return new mapboxgl.Marker({ element: dot, anchor: 'center' }).setLngLat([ap.lon, ap.lat]).addTo(this.map);
    });
  }
}
