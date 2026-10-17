# MyRadar Flight Tracker demo

A single-flight tracker built on `@myradar/mapbox` and `@myradar/controls`: search for a flight and
follow it live over MyRadar weather (radar, wind, hurricanes, watches and warnings, and lightning
strikes and flashes, each switchable).

## Quickstart (command line)

With [Node.js](https://nodejs.org) 20.19 or later, these commands clone the
sample, install it, write your keys to `.env.local` and start the dev server.
Replace `your-myradar-api-key` and `pk.your-mapbox-token`.

```sh
export MYRADAR_API_KEY=your-myradar-api-key
export MAPBOX_TOKEN=pk.your-mapbox-token
git clone https://github.com/MyRadar/MyRadar-SDK-Samples.git
cd MyRadar-SDK-Samples/web-demo
npm install
printf 'VITE_MYRADAR_API_KEY=%s\nVITE_MAPBOX_TOKEN=%s\n' "$MYRADAR_API_KEY" "$MAPBOX_TOKEN" > .env.local
npm run dev
```

Then open <http://localhost:5173>.

`.env.local` is git-ignored. Restart `npm run dev` after changing it.

## Run it

You need [Node.js](https://nodejs.org) 20.19 or later, a MyRadar API key from the
[developer portal](https://myradar.com/developer_portal), and a
[Mapbox access token](https://account.mapbox.com/access-tokens/) (the default public token works).

1. Get the code and install the packages, including the MyRadar web SDK (`@myradar/mapbox` and
   `@myradar/controls`) from npm:

   ```sh
   git clone https://github.com/MyRadar/MyRadar-SDK-Samples.git
   cd MyRadar-SDK-Samples/web-demo
   npm install
   ```

2. Add your keys. Copy the template to `.env.local`, which git ignores:

   ```sh
   cp .env.example .env.local
   ```

   and replace the placeholders:

   ```sh
   VITE_MAPBOX_TOKEN=pk.your-mapbox-token
   VITE_MYRADAR_API_KEY=your-myradar-sdk-api-key
   ```

   The demo passes the MyRadar key to the SDK as `apiKey` (see `src/config.ts` and `src/main.ts`),
   and the SDK sends it with every request to the MyRadar API gateway.

3. Start the dev server and open <http://localhost:5173>:

   ```sh
   npm run dev
   ```

Radar should start drawing within a few seconds. If the map shows but no radar or warnings, the
MyRadar key is missing or was rejected: the page shows "No MyRadar API key" when it is missing,
and the browser console shows `401` responses from `sdkwrapperapi.azure-api.net` when it was
rejected. Restart `npm run dev` after editing `.env.local`.

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

Keys come from `.env.local` (see [Run it](#run-it)) or the URL (`?token=...`, `?apiKey=...`).

MyRadar weather data comes straight from the MyRadar API gateway. The flight data services need
same-origin proxies, which `vite.config.ts` sets up for both `vite` and `vite preview`:

| Prefix | Target | Why |
|---|---|---|
| `/fr24` | `https://data-cloud.flightradar24.com` | CORS only allows flightradar24.com; the dev proxy drops the page's `Referer`, as FR24 answers 451 to `localhost` |
| `/adsb-traces` | `https://adsb.lol` (recorded traces) | no CORS headers |

A deployment needs equivalent proxies.

## URL parameters

`?flight=AA1643` searches for a flight on load (the page keeps it up to date, so the URL can be
shared), `?token=pk....` Mapbox token, `?apiKey=...` MyRadar key, `?debug` for console logs. The map position is kept in the URL hash.
