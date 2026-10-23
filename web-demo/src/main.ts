import mapboxgl from 'mapbox-gl';
import 'mapbox-gl/dist/mapbox-gl.css';
import {
  HurricanesLayer,
  LightningLayer,
  RadarLayer,
  WarningsLayer,
  WindLayer,
  firstSymbolLayerId,
  showFlight,
  type FlightInfo,
  type FlightLayer,
} from '@myradar/mapbox';
import { LayerControl, TimeSlider, weatherLayerItems } from '@myradar/controls';
import { INITIAL_VIEW, MAPBOX_TOKEN, MAP_STYLE, MYRADAR_API_KEY } from './config';
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
// Map and weather layers

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
const warnings = new WarningsLayer({ ...common, visible: false });
const lightning = new LightningLayer(common);
lightning.attachRadar(radar);

const layerControl = new LayerControl({
  panel: false,
  theme: 'light',
  storageKey: 'myradar-demo-flight-layers',
  items: weatherLayerItems({ radar, wind, hurricanes, warnings, lightning }),
}).addTo('#layer-control');
new TimeSlider({ radar, panel: false, theme: 'light', globalShortcuts: true }).addTo('#time-slider');

map.on('style.load', () => {
  const labels = firstSymbolLayerId(map);
  if (!map.getLayer(radar.id)) map.addLayer(radar, labels);
  if (!map.getLayer(wind.id)) map.addLayer(wind, labels);
  // Overlays add their own sources and layers; after a style change they must be re-added.
  warnings.remove();
  warnings.addTo(map, { beforeId: labels, radarLayerId: radar.id });
  hurricanes.remove();
  hurricanes.addTo(map, { beforeId: labels });
  lightning.remove();
  lightning.addTo(map, { beforeId: labels });
  layerControl.apply();
});

// ---------------------------------------------------------------------------------------------
// The flight: the SDK looks it up, draws it and keeps it updated until it lands

const flightCard = $('flight-card');
const detailsCard = $('details-card');
const timelineCard = $('timeline-card');
const searchForm = $<HTMLFormElement>('search');
const searchInput = $<HTMLInputElement>('search-input');
let flight: FlightLayer | null = null;
let follow = false;

function track(ident: string): void {
  const query = ident.trim();
  if (!query) return;
  flight?.remove();
  setUrlFlight(query);
  renderMessage('Searching', `Looking for ${query.toUpperCase()}…`, 'Finding the flight and its route.');
  const shown = showFlight(map, query, { apiKey });
  flight = shown;
  shown.on('data', ({ flight: info }) => {
    renderFlight(info);
    if (follow && info.position) map.easeTo({ center: [info.position.lng, info.position.lat] });
  });
  shown.on('error', ({ error }) => {
    // A failed update keeps the last data on the map; only a failed first load replaces the card.
    if (shown.flight) return showToast(`Flight: ${error.message}`);
    const messages: Record<string, string> = {
      invalid: 'Enter an airline flight number like <b>UA 1</b> or <b>AA 1643</b>, or a callsign like <b>UAL1</b>.',
      'not-found': `No flight ${escapeHtml(query.toUpperCase())} was found. Check the number and try again.`,
      'key-rejected': 'The MyRadar API key was missing or not accepted.',
      unavailable: `Flight data isn’t available right now (${escapeHtml(error.message)}). Try again in a moment.`,
    };
    renderMessage('Track a flight', 'Couldn’t show that flight', messages[error.reason] ?? escapeHtml(error.message));
  });
}

function stopTracking(): void {
  flight?.remove();
  flight = null;
  searchInput.value = '';
  setUrlFlight(null);
  renderMessage();
}

searchForm.addEventListener('submit', (e) => {
  e.preventDefault();
  searchInput.blur();
  track(searchInput.value);
});

function setUrlFlight(query: string | null): void {
  const url = new URL(location.href);
  if (query) url.searchParams.set('flight', query);
  else url.searchParams.delete('flight');
  history.replaceState(null, '', url);
}

// ---------------------------------------------------------------------------------------------
// Cards, built from the FlightInfo the SDK reports

const fmtInt = (n: number): string => n.toLocaleString(undefined, { maximumFractionDigits: 0 });

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

const PLANE_ICON =
  '<svg viewBox="0 0 64 64" aria-hidden="true"><path fill="currentColor" d="M32 3c-2.2 0-4 3.4-4 7.6v12.9L5 37.3v5.4l23-7.4v12.1l-6.4 4.3v3.8L32 53l10.4 2.5v-3.8L36 47.4V35.3l23 7.4v-5.4L36 23.5V10.6C36 6.4 34.2 3 32 3z"/></svg>';

function renderMessage(eyebrow = 'Track a flight', title = 'Where’s your flight?', body?: string): void {
  detailsCard.hidden = true;
  timelineCard.hidden = true;
  $('live-pill').hidden = true;
  flightCard.innerHTML = `
    <div class="eyebrow">${escapeHtml(eyebrow)}</div>
    <h1 class="title">${escapeHtml(title)}</h1>
    <p class="lede">${body ?? 'Enter an airline flight number (<b>UA 1</b> or <b>AA 1643</b>) or a callsign (<b>UAL1</b>) to follow it over MyRadar weather.'}</p>`;
}

function phase(f: FlightInfo): { label: string; tone: string } {
  if (f.cancelled) return { label: 'Cancelled', tone: 'alert' };
  if (f.landed) return { label: 'Landed', tone: 'done' };
  if (f.airborne) return { label: 'In the air', tone: 'air' };
  return { label: 'Scheduled', tone: 'muted' };
}

function renderFlight(f: FlightInfo): void {
  const o = f.origin;
  const d = f.destination;
  const p = phase(f);
  const now = Date.now();
  const title = o && d ? (f.landed ? `Landed in ${d.city}` : `${o.city} to ${d.city}`) : f.ident;
  const sub = [f.ident !== title ? f.ident : '', f.aircraftType, f.registration]
    .filter((s): s is string => !!s)
    .map(escapeHtml)
    .join(' · ');

  const arrival =
    f.landed && f.actualArrival
      ? `<div class="eyebrow">Landed</div><div class="big-time">${fmtTime(f.actualArrival)}</div>`
      : f.estimatedArrival
        ? `<div class="eyebrow">Lands</div><div class="big-time">${fmtTime(f.estimatedArrival)}</div>`
        : f.scheduledArrival
          ? `<div class="eyebrow">Scheduled to land</div><div class="big-time">${fmtTime(f.scheduledArrival)}</div>`
          : '';

  const pct = f.landed ? 100 : f.progress === null ? null : Math.round(f.progress * 100);
  const progress =
    o && d && pct !== null
      ? `<div class="progress-labels">
          <span><b>${escapeHtml(o.code)}</b> · ${f.actualDeparture ? `Departed ${fmtTime(f.actualDeparture)}` : escapeHtml(o.city)}</span>
          <span class="progress-pct">${pct}% of the way</span>
          <span><b>${escapeHtml(d.code)}</b> · ${escapeHtml(d.city)}</span>
        </div>
        <div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}" aria-label="Flight progress">
          <div class="progress-fill" style="width:${pct}%"></div>
          <div class="progress-knob" style="left:${pct}%"></div>
        </div>`
      : '';

  // After landing the last position is on the taxiway, so the in-flight stats are blank.
  const pos = f.landed ? null : f.position;
  const speed = pos?.groundSpeedKnots == null ? '—' : `${fmtInt(pos.groundSpeedKnots * 1.15078)} mph`;
  flightCard.innerHTML = `
    <div class="card-head">
      <div class="eyebrow">${escapeHtml(f.status || 'Flight')}</div>
      <span class="pill pill-${p.tone}">${PLANE_ICON}${p.label}</span>
    </div>
    <div class="flight-main">
      <div class="flight-title">
        <h1 class="title">${escapeHtml(title)}</h1>
        <div class="subtitle">${sub}</div>
      </div>
      <div class="flight-arrival">${arrival}</div>
    </div>
    ${progress}
    <div class="stats">
      <div><div class="eyebrow">Time to go</div><div class="stat">${f.airborne && f.estimatedArrival ? duration(f.estimatedArrival.getTime() - now) : '—'}</div></div>
      <div><div class="eyebrow">Altitude</div><div class="stat">${pos?.altitudeFt ? `${fmtInt(pos.altitudeFt)} ft` : '—'}</div></div>
      <div><div class="eyebrow">Speed</div><div class="stat">${speed}</div></div>
      <div><div class="eyebrow">Heading</div><div class="stat">${pos?.heading == null ? '—' : `${fmtInt(pos.heading)}°`}</div></div>
    </div>
    <div class="actions">
      <button class="chip" data-action="follow" aria-pressed="${follow}">Follow</button>
      <button class="chip" data-action="fit">Show whole route</button>
      <button class="chip" data-action="clear">Stop tracking</button>
    </div>`;

  const fact = (label: string, value: string) => `<div><dt>${label}</dt><dd>${value}</dd></div>`;
  detailsCard.hidden = false;
  detailsCard.innerHTML = `
    <div class="eyebrow">About this flight</div>
    <dl class="facts">
      ${o ? fact('From', escapeHtml(o.name)) : ''}
      ${d ? fact('To', escapeHtml(d.name)) : ''}
      ${fact('Aircraft', escapeHtml(f.aircraftType ?? '—'))}
      ${fact('Registration', escapeHtml(f.registration ?? '—'))}
      ${fact('Callsign', escapeHtml(f.identIcao ?? '—'))}
      ${fact('Position', f.position ? `${f.position.lat.toFixed(3)}, ${f.position.lng.toFixed(3)}${f.position.estimated ? ' (estimated)' : ''}` : '—')}
      ${fact('Last report', f.position ? `${duration(now - f.position.time.getTime())} ago` : '—')}
    </dl>`;

  renderTimeline(f);

  $('live-pill').hidden = !f.airborne;
  $('live-updated').textContent = `Updated ${fmtTime(new Date())}`;
}

interface Step {
  label: string;
  time?: Date;
  state: 'done' | 'now' | 'next';
}

function renderTimeline(f: FlightInfo): void {
  const from = f.origin?.code ?? '';
  const to = f.destination?.code ?? '';
  const steps: Step[] = [
    f.actualDeparture
      ? { label: `Departed ${from}`, time: f.actualDeparture, state: 'done' }
      : { label: `Departs ${from}`, time: f.scheduledDeparture, state: 'next' },
  ];
  if (f.landed) {
    steps.push({ label: `Landed at ${to}`, time: f.actualArrival, state: 'now' });
  } else {
    if (f.actualDeparture) steps.push({ label: 'Now', time: new Date(), state: 'now' });
    steps.push({ label: `Lands at ${to}`, time: f.estimatedArrival ?? f.scheduledArrival, state: 'next' });
  }
  const nowIndex = Math.max(0, steps.findIndex((s) => s.state === 'now'));
  const fill = steps.length > 1 ? (nowIndex / (steps.length - 1)) * 100 : 0;
  timelineCard.hidden = false;
  timelineCard.innerHTML = `
    <div class="timeline-intro">
      <div class="eyebrow">Timeline</div>
      <p>Times update as the flight goes</p>
    </div>
    <div class="timeline" role="list" style="--steps:${steps.length}">
      <div class="timeline-track" style="--inset:${50 / steps.length}%"><div class="timeline-fill" style="width:${fill}%"></div></div>
      ${steps
        .map(
          (s) => `<div class="step ${s.state}" role="listitem">
            <div class="step-label">${escapeHtml(s.label)}</div>
            <div class="step-dot"></div>
            <div class="step-time">${s.time ? fmtTime(s.time) : '—'}</div>
          </div>`,
        )
        .join('')}
    </div>`;
}

flightCard.addEventListener('click', (e) => {
  const action = (e.target as HTMLElement).closest<HTMLButtonElement>('[data-action]')?.dataset.action;
  if (action === 'follow') {
    follow = !follow;
    if (flight?.flight) renderFlight(flight.flight);
  } else if (action === 'fit') {
    follow = false;
    flight?.fit();
  } else if (action === 'clear') {
    stopTracking();
  }
});

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
map.on('error', (e) => {
  const message = (e as { error?: Error }).error?.message ?? 'Map error';
  if (/access token|401|403/i.test(message)) showToast(`Mapbox: ${message}`);
});
if (!apiKey) showToast('No MyRadar API key: set VITE_MYRADAR_API_KEY in .env.local or add ?apiKey=... to the URL', true);

// ---------------------------------------------------------------------------------------------
// Startup

const initialFlight = params.get('flight');
if (initialFlight) {
  searchInput.value = initialFlight;
  track(initialFlight);
} else {
  renderMessage();
}

// For poking around in the console and for the headless smoke test.
Object.assign(window as unknown as Record<string, unknown>, { map, radar, wind, hurricanes, warnings, lightning, layerControl, track, currentFlight: () => flight });
