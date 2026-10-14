# MyRadar Flight Tracker demo

A single-flight tracker built on `@myradar/mapbox` and `@myradar/controls`: search for a flight and
follow it live over MyRadar weather (radar, wind, hurricanes, watches and warnings, and lightning
strikes and flashes, each switchable).

```sh
cd web-demo
cp .env.example .env.local   # then fill in your keys (see below)
npm install
npm run dev                  # http://localhost:5173
```

`@myradar/mapbox` and `@myradar/controls` are the MyRadar web SDK packages; the demo expects them to
be installable from your package registry.

## What it shows

- **Search** in the header: an airline flight number as printed on a ticket (`AA 1643`), the
  callsign the aircraft broadcasts (`AAL1643`), or a registration (`N101NN`), looked up in
  Flightradar24's live feed anywhere in the world. When nothing is flying it, the card says so.
  Before a search, it suggests a few airline flights in the air near the map.
- **One flight on the map**, polled every 6 s: the track it has flown (white), the planned
  route ahead (blue dashes) and already flown (faint dashes), the airports, and the aircraft
  with its flight number and altitude. The view frames the whole trip until you pan or zoom.
  A **LIVE** badge shows when the position was last updated, or when it was last seen.
- **Flight card**: airline, origin → destination, aircraft type and registration, status
  (climbing, in the air, descending, landed, signal lost), estimated landing time from the
  remaining distance and ground speed, a progress bar, and time to go, distance, altitude
  and speed. **Follow** keeps the aircraft centred; **Show whole route** frames the trip again.
- **About this flight**: airports, callsign, vertical rate, heading, squawk, position and the
  age of the last report.
- **What happens next**: departure, now, the estimated top of descent (3 nmi per 1,000 ft),
  and the estimated landing.
- **Map layers** card: a `LayerControl` to turn radar, wind, hurricanes, watches & warnings,
  lightning strikes and flashes, and the flight track & route on and off, and the `TimeSlider`
  (the radar starts paused on the newest frame to match the live flight). Choices are
  remembered in `localStorage`.

## Flight data

Search, positions, flight numbers, callsigns, registrations, aircraft types, and origin and
destination airport codes come from [Flightradar24](https://www.flightradar24.com)'s web map
feed. The page shows a flight as soon as the feed answers.

The feed has no history, so the track flown before tracking started (and the take-off time)
comes from [adsb.lol](https://adsb.lol)'s recorded trace files (ODbL), loaded in the background.
adsb.lol is often slow or unavailable, so nothing waits on it: requests time out after 15 s and
are retried quietly a few times, and until a trace arrives the track starts where tracking began.

The feed only has codes, so airport names and coordinates and airline names come from tables in
`src/data/` (airports from [OurAirports](https://ourairports.com/data/), public domain; airlines
from [OpenFlights](https://openflights.org/data.php), ODbL), loaded on first use. A route that doesn't fit the aircraft's position
(a diversion or a stale flight number) isn't used for progress.

## Keys and proxies

Copy `.env.example` to `.env.local` (git-ignored) and set `VITE_MAPBOX_TOKEN` and
`VITE_MYRADAR_API_KEY`. Both can also be passed in the URL (`?token=...`, `?apiKey=...`).

`vite.config.ts` proxies on the page's origin, for both `vite` and `vite preview`:

| Prefix | Target | Why |
|---|---|---|
| `/fr24` | `https://data-cloud.flightradar24.com` | CORS only allows flightradar24.com; the dev proxy drops the page's `Referer`, as FR24 answers 451 to `localhost` |
| `/adsb-traces` | `https://adsb.lol` (recorded traces) | no CORS headers |
| `/myradar-api` | the MyRadar SDK gateway | the alerts and lightning operations do not send CORS headers yet; `?direct` bypasses this |

A deployment needs equivalent proxies (or routes for these hosts on the gateway).

## URL parameters

`?flight=AA1643` searches for a flight on load (the page keeps it up to date, so the URL can be
shared), `?token=pk....` Mapbox token, `?apiKey=...` MyRadar key, `?direct` to call the gateway directly
for every overlay, `?debug` for console logs. The map position is kept in the URL hash.
