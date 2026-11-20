# Weather — expanded Android 4.x app

This updates the existing **Weather** app (`ca.arc7freedom.weather`), not a separate
website or a copy running inside Arc7 Freedom. It keeps Android **4.0.3–4.2.2** support
(minSdk 15 / targetSdk 17, ARMv7, Dalvik DEX 035) and the permanent signing certificate.
This build is **2.6.5 / 265**; the matching GitHub release requires a successful main-branch workflow.

## Install this build

- **Weather only:** install the generated `releases/apps/Weather.apk` in place. It is
  included in the branch's GitHub Actions APK artifact and local Wi-Fi installer portal.
  The individual default-app APKs are generated outputs, intentionally not Git-tracked;
  an older published “latest” Weather download is not this branch build.
- **Complete offline bundle:** install [KoboArc7Freedom.apk](KoboArc7Freedom.apk), open
  Arc7 Freedom once, then **PATCHES → Apply / Retry All Patches**.
  Its 38-APK verified offline catalog includes this exact signed Weather APK. Root can
  apply in-place updates; without root, explicitly confirm pending APKs in Android.
  The [v2.6.5 upgrader](Upgrade-My-Kobo-To-v2.6.5.apk) embeds the same complete suite.
- Keep app data. Do not uninstall or clear Android's Weather data just to update. The
  maintenance pipeline detects same-version patches by SHA-256 and verifies read-back;
  it preserves newer installed versions and never uninstalls on a failed normal update.
- Prefer a new **Weather-supplied** widget. Legacy suite widgets can now read a small,
  signature-protected snapshot from Weather and request its refresh; Weather still owns
  all network jobs. Update both APKs, open Weather and select a city. If a legacy widget
  stays unregistered/stale after updating, remove/re-add it; install-order and ROM-specific
  launcher behaviour still need tablet testing. The widget explicitly shows **High / Low**.

See [all-method installation instructions](INSTALL-RUNTIME-FIXES.md) and
[SHA256SUMS.txt](SHA256SUMS.txt). Back up important data before updating.

## What's included

| Area | Features |
| --- | --- |
| **Today** | Current model temperature, feels-like, day/night condition icon, today's high/low, precipitation chance, humidity, wind speed/direction/gusts, sea-level pressure and cloud cover. Dew point, visibility and UV are clearly labelled as hourly estimates. Current-estimate and download times are shown separately. |
| **Hourly** | The next **48 hours**, starting with the ongoing hour rather than midnight. Native interactive temperature, precipitation-chance and wind charts; tap any hour for humidity, dew point, cloud cover, visibility, pressure, UV, snowfall and more. Cached hours are filtered again as time advances. |
| **Daily** | Up to **14 days**, common-scale high/low range bars, conditions, chance/amount of precipitation and maximum wind. Day details include feels-like range, snow, precipitation hours, gusts/direction, UV, sun times and the day's returned hourly breakdown. |
| **Places** | Search returns up to ten named matches with region/country; explicitly choose the right one. Save up to **12 cities**, switch, rename, remove or move to the top. Coordinates can be entered without geocoding or location hardware. Each place has a separate cache. |
| **Locate** | Optional one-time Android network/GPS lookup, with a **20-second timeout**. Recent usable last-known fixes may be offered; stale/invalid/very imprecise fixes are rejected. Review coordinates, accuracy and alert region before saving. Leaving Weather removes location listeners. No continuous or background tracking. |
| **Air** | US or European AQI, condition category, PM2.5, PM10, ozone, NO₂, SO₂, CO and a 24-hour outlook sampled every three hours. Six regional pollen types when supplied. Missing pollen is explicitly **not zero**. |
| **Sun & moon** | Sunrise, sunset, daylight duration, model sunshine duration, UV severity and a labelled approximate moon phase/illumination. Missing sun times can reflect polar day/night. Moonrise/moonset are not supplied. |
| **Alerts** | Official **Canada (ECCC GeoMet)** and **US (NWS)** alerts, severity/risk colour, area, expiry, text and instructions. Canadian polygon regions are filtered to the selected coordinates, including polygon holes. Tests/cancellations/expired notices are not shown as current warnings. Unsupported regions, failed checks, old caches and incomplete responses are marked explicitly. |
| **Notifications** | **Off by default.** Optional notifications for new/updated official alerts in the **selected city**, deduplicated across refreshes. Optional Android notification sound. These are polled on refresh, not push/emergency broadcasts; they may be delayed or unavailable. |
| **Radar** | Native RainViewer **past precipitation** map, last six returned past frames, Previous/Play/Pause/Next, drag to pan, zoom and recenter. A red marker shows the saved city when visible. OpenStreetMap base tiles and attribution, explicit timestamps, unavailable/partial-tile states, private tile cache capped at approximately **12 MB**. Loaded only when Radar is opened. |
| **Units** | Celsius/Fahrenheit; km/h, mph, m/s or knots; mm/cm or inches; km/miles; hPa/inHg; 12-/24-hour time; US/European AQI. Conversions use stored provider/SI measurements and require no new request. |
| **Settings & sharing** | Manual refresh; manual-only / 30-minute / hourly / 3-hour automatic updates; optional Wi-Fi/Ethernet-only background requests; plain-text forecast sharing with source and validity/download times. Clear downloaded caches without removing saved cities or settings. |
| **Widget** | Selected city, real current/feels-like readings, today's high/low/chance, download timestamp, stale/error status, tap-to-open and a **separate Refresh target** that actually fetches. No hard-coded sample temperature or city. |

### Background update behaviour

- Automatic in-app refresh runs only while Weather is visible, when an automatic interval
  is selected. Background polling runs only if a Weather widget or optional alert
  notifications are enabled. Both use the selected saved city, not device tracking.
- The app restores its own schedule after boot/update/clock/network changes. It uses
  **non-waking inexact alarms**, does not enable Wi-Fi, and holds a time-bounded wake lock
  only while an actual service update is in progress. Android/device sleep can delay work.
- **Manual only** disables background polling; explicit app/widget Refresh can use the
  currently active connection. Rapid widget taps are coalesced. Rendering a widget does
  not recursively start another fetch.
- Forecast, air-quality and alert errors are independent. A failing forecast endpoint
  does not prevent attempts to retrieve official alerts. Requests have connection/read,
  total-read and response-size bounds and can be cancelled when leaving the screen.

## Honest data, privacy and limitations

- No account, API key, ads, analytics, Play Services or root requirement. All connections
  use **Weather's own UID and TLS engine**, never the suite's old universal proxy.
- City names/coordinates and your IP are sent to the relevant providers for requests.
  Radar requests reveal the viewed map area to RainViewer/OpenStreetMap. Display-unit
  changes use local data. Weather caches/settings are in the owning app's private storage
  (subject to Android's normal backup settings).
- The old **private** city/cache preferences are migrated with **unknown download age**,
  not re-labelled fresh. `/sdcard/Arc7Freedom/weather_cache.json` is no longer read or
  written; any obsolete file is left untouched rather than deleting shared storage.
- Empty, malformed, rate-limited or failed responses cannot replace a usable cache.
  Missing measurements are **—**, never a made-up temperature, zero AQI, clear sky or
  “no warnings”. Fresh-install UI starts with location setup, not fictitious Kitchener data.
- Stale data and wrong-clock symptoms are visible. Local times use the selected city's
  timezone, including DST where Android's timezone database supports it; radar frames are
  explicitly UTC. Keep the tablet's clock accurate. Very old ROM timezone rules can differ
  from current local legislation.
- Weather and air are **model estimates**, not observations from a sensor at your home.
  Later forecasts are less certain and provider variables/coverage can be missing. Hourly
  precipitation totals refer to the preceding hour ending at their timestamp; this is
  **not minute-by-minute rain nowcasting**. Pollen coverage is primarily European.
- Official in-app alerts are limited to **Canada and the US**. Search provides the country;
  for manual/device coordinates, set the appropriate alert region yourself. Other regions
  link to national weather services. Never interpret unavailable/old/incomplete data as an
  all-clear. **Do not rely on this app for emergency warning delivery.**
- RainViewer's public API supplies **past radar only**, with PNG/Universal Blue tiles up to
  zoom **7**. No satellite imagery, future radar, or unsupported free nowcast is claimed.
  Blank/transparent areas can mean no echo **or no coverage**. Old metadata and partial
  tiles are marked; a retained image after a failed reload is not a current map.
- Kobo/ROMs without a usable network/GPS provider fall back to city search/coordinates.
  Location cannot be guaranteed by adding a manifest permission.

## Validation

The final local full build and **`make verify` passed**, including the focused Weather
regressions, shipped Weather APK payload checks and exact 38-APK bundle reconstruction.
These are host/build results, not the device/live-provider checks listed below.

Run the focused regression suite:

```sh
make test-weather
```

It executes production Java models, per-city storage, HTTP/TLS-gateway adapter, background
service, widget, boot receiver and radar math on a host JVM, with **synthetic provider
responses and fake Android services**. It covers missing/null data, full 48h/14d parsing,
DST/local dates, conversions, search disambiguation, location-fix validation, AQ/pollen,
ECCC polygon/hole filtering, NWS watch/expiry/test/cancel cases, incomplete pagination,
offline/error cache preservation, old-city/out-of-order/cancel/clear/remove races,
notification opt-in/deduplication, non-waking/manual scheduling and active cancellation.

`make verify` includes these tests plus existing API17/signing/DEX/ARMv7, app isolation,
maintenance catalog reconstruction, installer/F-Droid and offline media gates. Android UI
lifecycle bindings are source-audited and API17-compiled; fake-service tests are not an
Android UI renderer. **No physical Kobo, emulator or live weather/radar/alert service was
available for validation here.** Current provider availability, actual widget scheduling,
location fixes, map rendering/touch behaviour and notification delivery still need device
checks. The same applies to earlier ROM/media and device-specific network limitations. The shared translucent glass surfaces are API17-compiled and recording-graphics-tested, not GPU/physical-screen validation.

### Suggested Kobo smoke test

1. Update Weather in place, open it, search **London**, and choose the intended country.
   Save another city, switch rapidly during Refresh, then rename/remove one. No previous
   city's conditions should appear under the new name.
2. Check Today details, the next 48 hours, all three charts, a day detail, Air & sun and
   units. Verify local times and that missing fields use **—**. Share a forecast.
3. Turn off Wi-Fi/PAN, reopen both cities and inspect saved timestamps/errors. A failed
   refresh must retain each city's own cached data. Clear caches; city names/settings stay.
4. Try Locate. Review the fix or verify the timeout/provider guidance, then leave Weather
   during a lookup/refresh. It must stop listening and not display a late dialog.
5. Add the standalone Weather widget. Tap Refresh, change the selected city/units, then
   verify timestamps/ranges and manual-only versus automatic schedules after reboot.
6. Open Radar while online. Verify the saved-city marker, timestamps, pan/zoom, frame
   controls, attribution and failure/partial states offline. Blank radar is not an all-clear.
7. For a CA/US city, compare Alerts against the linked authority. Enable notifications only
   if wanted; refresh twice and verify duplicate publications do not repeatedly notify.
   Do not manufacture an emergency or assume an empty feed proves an absence of warnings.

## Provider contracts used

- [Open-Meteo forecast schema](https://github.com/open-meteo/open-meteo/blob/main/openapi/forecast.yml)
- [Open-Meteo air-quality schema](https://github.com/open-meteo/open-meteo/blob/main/openapi/air-quality.yml)
- [ECCC collection configuration](https://github.com/ECCC-MSC/msc-pygeoapi/blob/master/deploy/default/msc-pygeoapi-config.yml)
  and [current bilingual weather-alert properties](https://github.com/ECCC-MSC/msc-pygeoapi/blob/master/msc_pygeoapi/loader/alerts_realtime.py)
- [RainViewer official public API examples and limitations](https://github.com/rainviewer/rainviewer-api-example)
- National authorities: [ECCC](https://weather.gc.ca/warnings/index_e.html),
  [US NWS](https://www.weather.gov/). Map data: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).

These contracts inform parsing and honest fallbacks; consulting them is not live endpoint
validation. Attribution is also visible inside Weather.
