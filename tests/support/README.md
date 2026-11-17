# Host-only Android JSON substitutes

`org/json/` contains the existing verifier's tiny JSON fakes, shared with the
compiled Morphe engine protocol test. They support only the methods/fixtures used
by the tests, not a general JSON implementation. They now serialize requests so
the fixture gateway can check the real client payload, headers and URL.

`scripts/test-weather.py` also uses these JSON substitutes to execute the real
Weather models, per-city store, TLS transport, background service, widget and radar
math. It generates synthetic Open-Meteo, ECCC, NWS and RainViewer schema fixtures in
a temporary directory and supplies small Android-service fakes. It does not contact
providers, render Android UI, or claim physical Kobo testing.

These files are never included in APKs. Production uses Android's `org.json`.
