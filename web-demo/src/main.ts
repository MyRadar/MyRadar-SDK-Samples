import mapboxgl from 'mapbox-gl';
import 'mapbox-gl/dist/mapbox-gl.css';
import { HurricanesLayer, LightningLayer, RadarLayer, WarningsLayer, WindLayer, firstSymbolLayerId } from '@myradar/mapbox';
import { LayerControl, TimeSlider, weatherLayerItems } from '@myradar/controls';
import { NM_TO_MI, aircraftTypeName, type Aircraft } from './geo';
import { INITIAL_VIEW, MAPBOX_TOKEN, MAP_STYLE, MYRADAR_API_KEY, MYRADAR_PROXY_HOST } from './config';
import { FlightTracker, flightLabel, type FlightPhase, type TrackedFlight } from './flights';
import { normalizeQuery, searchFlight, suggestFlights, type SearchResult, type Suggestion } from './search';
import './style.css';

const params = new URLSearchParams(location.search);
mapboxgl.accessToken = params.get('token') || MAPBOX_TOKEN;
const apiKey = params.get('apiKey') || MYRADAR_API_KEY;
const debug = params.has('debug');

const $ = <T extends HTMLElement>(id: string): T => {
  const el = document.getElementById(id);
  if (!el) throw new Error(`missing #${id}`);
  return el as T;
};

// ---------------------------------------------------------------------------------------------
// Header clock

const fmtTime = (d: Date): string => new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(d);

function updateClock(): void {
  const now = new Date();
  $('clock-time').textContent = fmtTime(now);
  const zone = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' }).formatToParts(now).find((p) => p.type === 'timeZoneName')?.value;
  const date = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric' }).format(now);
  $('clock-date').textContent = [date, zone].filter(Boolean).join(' · ');
}
updateClock();
setInterval(updateClock, 15_000);

// ---------------------------------------------------------------------------------------------
// Map and layers

// Mapbox throws on map creation without a token, so check first and explain what's missing.
if (!mapboxgl.accessToken) {
  const message = $('toast');
  message.textContent = 'No Mapbox token: set VITE_MAPBOX_TOKEN in .env.local or add ?token=... to the URL';
  message.hidden = false;
  throw new Error('Mapbox token missing');
}

const map = new mapboxgl.Map({
  container: 'map',
  style: MAP_STYLE,
  center: INITIAL_VIEW.center,
  zoom: INITIAL_VIEW.zoom,
  projection: 'mercator',
  hash: true,
  attributionControl: true,
  // north-up keeps the aircraft icon's heading simple
  dragRotate: false,
  pitchWithRotate: false,
});
map.touchZoomRotate.disableRotation();
map.addControl(new mapboxgl.ScaleControl({ unit: 'imperial' }), 'bottom-right');

const common = { apiKey, debug };
// Paused on the newest frame so the map matches the live flight; the time slider plays the loop.
const radar = new RadarLayer({ ...common, product: 'radar', palette: 'dark', opacity: 0.7, playing: false });
const wind = new WindLayer({ ...common, visible: false });
const hurricanes = new HurricanesLayer(common);
// Overlays whose gateway operations lack CORS headers are fetched through the dev-server proxy.
const proxied = params.has('direct') ? common : { ...common, host: MYRADAR_PROXY_HOST };
const warnings = new WarningsLayer({ ...proxied, visible: false });
const lightning = new LightningLayer(proxied);
lightning.attachRadar(radar);
const flights = new FlightTracker(map);

// ---------------------------------------------------------------------------------------------
// Map layers card (controls from @myradar/controls)

const layerControl = new LayerControl({
  panel: false,
  theme: 'light',
  storageKey: 'myradar-demo-flight-layers',
  items: [
    ...weatherLayerItems({ radar, wind, hurricanes, warnings, lightning }),
    { id: 'route', label: 'Flight track & route', swatch: '#4da3ff', target: flights, property: 'showRoute' },
  ],
}).addTo('#layer-control');
new TimeSlider({ radar, panel: false, theme: 'light', globalShortcuts: true }).addTo('#time-slider');

function installLayers(): void {
  const labels = firstSymbolLayerId(map);
  if (!map.getLayer(radar.id)) map.addLayer(radar, labels);
  if (!map.getLayer(wind.id)) map.addLayer(wind, labels);
  // Overlays add their own sources/layers; after a style change they must be re-added.
  warnings.remove();
  warnings.addTo(map, { beforeId: labels, radarLayerId: radar.id });
  hurricanes.remove();
  hurricanes.addTo(map, { beforeId: labels });
  lightning.remove();
  lightning.addTo(map, { beforeId: labels });
  flights.install(labels);
  // re-apply the toggles to the freshly added layers
  layerControl.apply();
}

map.on('style.load', installLayers);

// ---------------------------------------------------------------------------------------------
// Formatting

const fmtInt = (n: number): string => n.toLocaleString(undefined, { maximumFractionDigits: 0 });
const miles = (nm: number): string => `${fmtInt(nm * NM_TO_MI)} mi`;

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string);
}

function duration(ms: number): string {
  const minutes = Math.max(0, Math.round(ms / 60_000));
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

function altitudeText(a: Aircraft): string {
  if (a.altitude === 'ground') return 'On ground';
  if (a.altitude === null) return '—';
  return `${fmtInt(a.altitude)} ft`;
}

/** Transponder codes reserved for emergencies. */
const SQUAWK_EMERGENCIES: Record<string, string> = { '7500': 'Hijack', '7600': 'Radio failure', '7700': 'Emergency' };

const PHASES: Record<FlightPhase, { label: string; tone: string }> = {
  climbing: { label: 'Climbing', tone: 'air' },
  cruising: { label: 'In the air', tone: 'air' },
  descending: { label: 'Descending', tone: 'air' },
  landed: { label: 'Landed', tone: 'done' },
  ground: { label: 'On the ground', tone: 'muted' },
  lost: { label: 'Signal lost', tone: 'alert' },
};

const ICONS = {
  clock: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M12 7v5l3 2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>',
  plane: '<svg viewBox="0 0 64 64" aria-hidden="true"><path fill="currentColor" d="M32 3c-2.2 0-4 3.4-4 7.6v12.9L5 37.3v5.4l23-7.4v12.1l-6.4 4.3v3.8L32 53l10.4 2.5v-3.8L36 47.4V35.3l23 7.4v-5.4L36 23.5V10.6C36 6.4 34.2 3 32 3z"/></svg>',
};

// ---------------------------------------------------------------------------------------------
// Search

const flightCard = $('flight-card');
const detailsCard = $('details-card');
const timelineCard = $('timeline-card');
const searchForm = $<HTMLFormElement>('search');
const searchInput = $<HTMLInputElement>('search-input');
let suggestions: Suggestion[] = [];
/** Suggestions load the first time the empty state shows, so they don't compete with a search from the URL. */
let suggestionsRequested = false;
/** The latest search, so a slow answer to an older one is ignored. */
let searchSeq = 0;

function setUrlFlight(query: string | null): void {
  const url = new URL(location.href);
  if (query) url.searchParams.set('flight', query);
  else url.searchParams.delete('flight');
  history.replaceState(null, '', url);
}

function suggestionsHtml(): string {
  if (suggestions.length === 0) return '';
  return `<div class="eyebrow suggest-title">In the air near the map</div>
    <div class="suggestions">${suggestions
      .map(({ aircraft, route }) => {
        const [o, d] = [route.airports[0], route.airports[route.airports.length - 1]];
        return `<button class="suggestion" data-id="${escapeHtml(aircraft.id)}">
          <span class="suggestion-flight">${escapeHtml(aircraft.flightNumber ?? aircraft.callsign)}</span>
          <span class="suggestion-route">${escapeHtml(o.location ?? o.iata ?? o.icao)} → ${escapeHtml(d.location ?? d.iata ?? d.icao)}</span>
          <span class="suggestion-type">${escapeHtml(aircraftTypeName(aircraft))}</span>
        </button>`;
      })
      .join('')}</div>`;
}

function renderIdle(message?: { title: string; body: string }): void {
  if (!suggestionsRequested) void loadSuggestions();
  detailsCard.hidden = true;
  timelineCard.hidden = true;
  $('live-pill').hidden = true;
  flightCard.innerHTML = `
    <div class="eyebrow">Track a flight</div>
    <h1 class="title">${escapeHtml(message?.title ?? 'Where’s your flight?')}</h1>
    <p class="lede">${message?.body ?? 'Search by airline flight number (<b>AA 1643</b> or <b>AAL1643</b>) or tail number (<b>N101NN</b>) to follow one flight live over MyRadar weather.'}</p>
    ${suggestionsHtml() || '<p class="hint" id="suggest-hint">Finding flights in the air near the map…</p>'}`;
}

function renderSearching(query: string): void {
  flightCard.innerHTML = `
    <div class="eyebrow">Searching</div>
    <h1 class="title">Looking for ${escapeHtml(query)}…</h1>
    <p class="lede">Checking live aircraft positions worldwide. This can take a few seconds.</p>`;
}

function renderChoices(result: SearchResult): void {
  flightCard.innerHTML = `
    <div class="eyebrow">${result.matches.length} aircraft match ${escapeHtml(result.query)}</div>
    <h1 class="title">Which one?</h1>
    <div class="suggestions">${result.matches
      .map(
        (a) => `<button class="suggestion" data-pick="${escapeHtml(a.id)}">
          <span class="suggestion-flight">${escapeHtml(a.flightNumber ?? a.callsign ?? a.registration ?? '')}</span>
          <span class="suggestion-route">${a.origin && a.destination ? `${escapeHtml(a.origin)} → ${escapeHtml(a.destination)} · ` : ''}${escapeHtml(a.registration ?? '')} · ${altitudeText(a)}</span>
          <span class="suggestion-type">${escapeHtml(aircraftTypeName(a))}</span>
        </button>`,
      )
      .join('')}</div>`;
  flightCard.querySelectorAll<HTMLButtonElement>('[data-pick]').forEach((b) =>
    b.addEventListener('click', () => {
      const a = result.matches.find((m) => m.id === b.dataset.pick);
      if (a) void flights.track(a);
    }),
  );
}

async function runSearch(raw: string): Promise<void> {
  const query = normalizeQuery(raw);
  if (!query) return;
  const seq = ++searchSeq;
  setUrlFlight(query);
  flights.clear();
  renderSearching(raw.trim().toUpperCase());
  let result: SearchResult;
  try {
    result = await searchFlight(query);
  } catch (err) {
    if (seq !== searchSeq) return;
    renderIdle({ title: 'Couldn’t reach live flight data', body: `The lookup failed (${escapeHtml((err as Error).message)}). Try again in a moment.` });
    return;
  }
  if (seq !== searchSeq) return;
  const airborne = result.matches.filter((a) => a.altitude !== 'ground');
  if (result.matches.length === 0) {
    renderIdle({
      title: `${query} isn’t flying right now`,
      body: `Flightradar24 has no live flight for ${escapeHtml(query)} at the moment. Try again closer to departure, or pick a flight below.`,
    });
    return;
  }
  if (airborne.length > 1) {
    renderChoices({ ...result, matches: airborne });
    return;
  }
  await flights.track(airborne[0] ?? result.matches[0]);
}

searchForm.addEventListener('submit', (e) => {
  e.preventDefault();
  searchInput.blur();
  void runSearch(searchInput.value);
});

flightCard.addEventListener('click', (e) => {
  const button = (e.target as HTMLElement).closest<HTMLButtonElement>('[data-id]');
  const s = suggestions.find((x) => x.aircraft.id === button?.dataset.id);
  if (!s) return;
  const name = s.aircraft.flightNumber ?? s.aircraft.callsign;
  searchInput.value = name;
  searchSeq++;
  setUrlFlight(name);
  void flights.track(s.aircraft, { route: s.route });
});

async function loadSuggestions(): Promise<void> {
  suggestionsRequested = true;
  try {
    const c = map.getCenter();
    suggestions = await suggestFlights(c.lat + 4, c.lat - 4, c.lng - 6, c.lng + 6);
  } catch {
    suggestions = [];
  }
  if (!flights.current) {
    const hint = document.getElementById('suggest-hint');
    if (hint) hint.outerHTML = suggestionsHtml() || '<p class="hint">No airline flights found near the map right now.</p>';
  }
}

// ---------------------------------------------------------------------------------------------
// Flight cards

function renderFlight(f: TrackedFlight): void {
  const a = f.aircraft;
  const stops = f.route?.airports;
  const origin = stops?.[0];
  const dest = stops?.[stops.length - 1];
  const phase = PHASES[f.phase];
  const label = flightLabel(f);
  const city = (ap: NonNullable<typeof origin>) => ap.location ?? ap.name;
  const code = (ap: NonNullable<typeof origin>) => ap.iata ?? ap.icao;
  const now = Date.now();

  let title = label;
  if (origin && dest) title = f.phase === 'landed' ? `Landed in ${city(dest)}` : `${city(origin)} to ${city(dest)}`;
  const sub = [label !== title ? label : '', aircraftTypeName(a), a.registration].filter(Boolean).map((s) => escapeHtml(String(s))).join(' · ');

  const arrival = f.eta
    ? `<div class="eyebrow">Lands</div><div class="big-time">${fmtTime(f.eta)}</div><div class="small">Estimated from current speed</div>`
    : f.phase === 'landed'
      ? `<div class="eyebrow">Arrived</div><div class="big-time">${escapeHtml(code(dest!))}</div>`
      : '';

  const pct = f.progress === null ? null : Math.round(f.progress * 100);
  const progress =
    origin && dest && pct !== null
      ? `<div class="progress-labels">
          <span><b>${escapeHtml(code(origin))}</b> · ${f.departedAt ? `Departed ${fmtTime(f.departedAt)}` : escapeHtml(city(origin))}</span>
          <span class="progress-pct">${pct}% of the way</span>
          <span><b>${escapeHtml(code(dest))}</b>${f.remainingNm !== null && f.phase !== 'landed' ? ` · ${miles(f.remainingNm)} to go` : ''}</span>
        </div>
        <div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}" aria-label="Flight progress">
          <div class="progress-fill" style="width:${pct}%"></div>
          <div class="progress-knob" style="left:${pct}%"></div>
        </div>`
      : f.rejectedRoute
        ? `<p class="hint">The published route for this flight number (${f.rejectedRoute.airports.map((ap) => escapeHtml(code(ap))).join(' → ')}) doesn’t match where the aircraft is, so progress isn’t shown. Its track is still drawn on the map.</p>`
        : '<p class="hint">No planned route is known for this flight, so progress can’t be shown. Its track is still drawn on the map.</p>';

  const speedMph = a.groundSpeedKnots === null ? null : a.groundSpeedKnots * NM_TO_MI;
  flightCard.innerHTML = `
    <div class="card-head">
      <div class="eyebrow">${escapeHtml(f.airline?.name || 'Flight')}</div>
      <span class="pill pill-${phase.tone}">${f.phase === 'lost' ? ICONS.clock : ICONS.plane}${phase.label}</span>
    </div>
    <div class="flight-main">
      <div class="flight-title">
        <h1 class="title">${escapeHtml(title).replace(/\//g, '/<wbr>')}</h1>
        <div class="subtitle">${sub}</div>
      </div>
      <div class="flight-arrival">${arrival}</div>
    </div>
    ${progress}
    <div class="stats">
      <div><div class="eyebrow">Time to go</div><div class="stat">${f.eta ? duration(f.eta.getTime() - now) : '—'}</div></div>
      <div><div class="eyebrow">Distance</div><div class="stat">${f.remainingNm === null || f.phase === 'landed' ? '—' : miles(f.remainingNm)}</div></div>
      <div><div class="eyebrow">Altitude</div><div class="stat">${altitudeText(a)}</div></div>
      <div><div class="eyebrow">Speed</div><div class="stat">${speedMph === null ? '—' : `${fmtInt(speedMph)} mph`}</div></div>
    </div>
    <div class="actions">
      <button class="chip" data-action="follow" aria-pressed="${flights.follow}">Follow</button>
      <button class="chip" data-action="fit">Show whole route</button>
      <button class="chip" data-action="clear">Stop tracking</button>
    </div>`;

  const vr = a.verticalRateFpm;
  const vrText = vr === null ? '—' : Math.abs(vr) < 200 ? 'Level' : `${vr > 0 ? 'Climbing' : 'Descending'} ${fmtInt(Math.abs(vr))} ft/min`;
  const fact = (label: string, value: string) => `<div><dt>${label}</dt><dd>${value}</dd></div>`;
  detailsCard.hidden = false;
  detailsCard.innerHTML = `
    <div class="eyebrow">About this flight</div>
    <dl class="facts">
      ${origin ? fact('From', escapeHtml(origin.name)) : ''}
      ${dest ? fact('To', escapeHtml(dest.name)) : ''}
      ${fact('Aircraft', escapeHtml(aircraftTypeName(a)))}
      ${fact('Registration', escapeHtml(a.registration ?? '—'))}
      ${fact('Callsign', escapeHtml(a.callsign || '—'))}
      ${fact('Vertical rate', vrText)}
      ${fact('Heading', a.track === null ? '—' : `${fmtInt(a.track)}°`)}
      ${fact('Squawk', `${escapeHtml(a.squawk ?? '—')}${a.squawk && SQUAWK_EMERGENCIES[a.squawk] ? ` · <span class="alert">${SQUAWK_EMERGENCIES[a.squawk]}</span>` : ''}`)}
      ${fact('Position', `${a.lat.toFixed(3)}, ${a.lon.toFixed(3)}`)}
      ${fact('Last report', a.seenSeconds < 5 ? 'Just now' : a.seenSeconds < 60 ? `${fmtInt(a.seenSeconds)} s ago` : `${duration(a.seenSeconds * 1000)} ago`)}
    </dl>`;

  renderTimeline(f);

  const live = $('live-pill');
  live.hidden = false;
  live.classList.toggle('stale', f.phase === 'lost');
  live.querySelector('b')!.textContent = f.phase === 'lost' ? 'LAST SEEN' : 'LIVE';
  $('live-updated').textContent = f.phase === 'lost' ? fmtTime(new Date(now - a.seenSeconds * 1000)) : `Updated ${fmtTime(f.updatedAt)}`;
}

interface Step {
  label: string;
  time: Date | null;
  state: 'done' | 'now' | 'next';
  note?: string;
}

function renderTimeline(f: TrackedFlight): void {
  const stops = f.route?.airports;
  const origin = stops?.[0];
  const dest = stops?.[stops.length - 1];
  const steps: Step[] = [
    // the take-off time comes from adsb.lol's recorded track, when it arrives
    { label: origin ? `Departed ${origin.location ?? origin.iata ?? origin.icao}` : 'Departed', time: f.departedAt, state: 'done', note: 'Done' },
  ];
  if (f.phase === 'landed') {
    steps.push({ label: dest ? `Landed at ${dest.iata ?? dest.icao}` : 'Landed', time: null, state: 'now', note: 'Now' });
  } else {
    if (f.phase === 'descending') steps.push({ label: 'Descending', time: null, state: 'done', note: 'Under way' });
    steps.push({ label: 'Now', time: new Date(), state: 'now' });
    if (f.descentAt) steps.push({ label: 'Begins descent', time: f.descentAt, state: 'next' });
    steps.push({ label: dest ? `Lands at ${dest.iata ?? dest.icao}` : 'Lands', time: f.eta, state: 'next' });
  }
  const nowIndex = steps.findIndex((s) => s.state === 'now');
  const fill = steps.length > 1 ? (nowIndex / (steps.length - 1)) * 100 : 0;
  timelineCard.hidden = false;
  timelineCard.innerHTML = `
    <div class="timeline-intro">
      <div class="eyebrow">What happens next</div>
      <p>Estimates update as the aircraft gets closer</p>
    </div>
    <div class="timeline" role="list" style="--steps:${steps.length}">
      <div class="timeline-track" style="--inset:${50 / steps.length}%"><div class="timeline-fill" style="width:${fill}%"></div></div>
      ${steps
        .map(
          (s) => `<div class="step ${s.state}" role="listitem">
            <div class="step-label">${escapeHtml(s.label)}</div>
            <div class="step-dot"></div>
            <div class="step-time">${s.time ? fmtTime(s.time) : (s.note ?? '—')}</div>
          </div>`,
        )
        .join('')}
    </div>`;
}

flightCard.addEventListener('click', (e) => {
  const action = (e.target as HTMLElement).closest<HTMLButtonElement>('[data-action]')?.dataset.action;
  if (action === 'follow') {
    flights.follow = !flights.follow;
    if (flights.current) renderFlight(flights.current);
  } else if (action === 'fit') {
    flights.follow = false;
    flights.fitView(true);
  } else if (action === 'clear') {
    searchSeq++;
    searchInput.value = '';
    setUrlFlight(null);
    flights.clear();
  }
});

flights.on('flight', (f) => {
  if (f) renderFlight(f);
  else renderIdle();
});

// ---------------------------------------------------------------------------------------------
// Startup

const initialFlight = params.get('flight');
if (initialFlight) {
  searchInput.value = initialFlight;
  void runSearch(initialFlight);
} else {
  renderIdle();
}

// ---------------------------------------------------------------------------------------------
// Errors

const toast = $('toast');
let toastTimer: ReturnType<typeof setTimeout> | null = null;
let stickyMessage = false;
/** Shows a message; `sticky` messages (missing keys) stay up and aren't replaced by later errors. */
function showToast(message: string, sticky = false): void {
  if (stickyMessage) return;
  stickyMessage = sticky;
  toast.textContent = message;
  toast.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  if (!sticky) {
    toastTimer = setTimeout(() => {
      toast.hidden = true;
    }, 6000);
  }
}
radar.on('error', ({ error }) => showToast(error.message));
for (const overlay of [warnings, hurricanes, lightning, wind]) overlay.on('error', ({ error }) => showToast(error.message));
flights.on('error', (error) => showToast(`Flight: ${error.message}`));
map.on('error', (e) => {
  const message = (e as { error?: Error }).error?.message ?? 'Map error';
  if (/access token|401|403/i.test(message)) showToast(`Mapbox: ${message}`);
});
if (!apiKey) showToast('No MyRadar API key: set VITE_MYRADAR_API_KEY in .env.local or add ?apiKey=... to the URL', true);

// For poking around in the console and for the headless smoke test.
Object.assign(window as unknown as Record<string, unknown>, { map, radar, wind, hurricanes, warnings, lightning, flights, layerControl, runSearch });
