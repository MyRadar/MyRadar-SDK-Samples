# MyRadar Flight Tracker demo

A single-flight tracker built on `@myradar/mapbox` and `@myradar/controls`: enter a flight number and
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

- **Search** in the header: an airline flight number as printed on a ticket (`UA 1`,
  `AA 1643`) or the callsign the aircraft broadcasts (`UAL1`).
- **The flight on the map**, drawn by the SDK's `showFlight`: the track it has flown (white),
  the planned route ahead (blue dashes) and already flown (faint dashes), the airports, and the
  aircraft with its flight number and altitude (hollow when its position is estimated, as it
  often is over oceans). The SDK keeps it updated every minute until it lands.
- **Flight card**: status, origin → destination, aircraft type and registration, landing time,
  a progress bar, and time to go, altitude, speed and heading. **Follow** keeps the aircraft
  centred; **Show whole route** frames the trip again.
- **About this flight** and a **timeline** of departure, now and landing.
- **Map layers** card: a `LayerControl` to turn radar, wind, hurricanes, watches & warnings,
  and lightning strikes and flashes on and off, and the `TimeSlider` (the radar starts paused on
  the newest frame to match the live flight). Choices are remembered in `localStorage`.

## How the flight works

The demo has no flight-data code. It passes the flight number to the SDK and renders the
`FlightInfo` it reports:

```ts
const flight = showFlight(map, 'UA1', { apiKey });
flight.on('data', ({ flight }) => renderFlight(flight));   // on load and every update
flight.on('error', ({ error }) => showMessage(error.reason));
flight.remove();                                            // Stop tracking
```

The SDK looks the flight up, draws it, and fetches updates through the MyRadar API gateway with
your key, like the weather layers. No proxies or other services are involved.

## URL parameters

`?flight=UA1` shows a flight on load (the page keeps it up to date, so the URL can be
shared), `?token=pk....` Mapbox token, `?apiKey=...` MyRadar key, `?debug` for console logs. The map position is kept in the URL hash.
