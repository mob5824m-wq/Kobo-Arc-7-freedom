# Host-only Android JSON substitutes

`org/json/` contains the existing verifier's tiny JSON fakes, shared with the
compiled Morphe engine protocol test. They support only the methods/fixtures used
by the tests, not a general JSON implementation. They now serialize requests so
the fixture gateway can check the real client payload, headers and URL.

These files are never included in APKs. Production uses Android's `org.json`.
