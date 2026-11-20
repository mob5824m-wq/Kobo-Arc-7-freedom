# ZXing QR decoder

59-source QR encoder/reader dependency closure from ZXing 3.3.3, Apache-2.0.
Source: https://codeload.github.com/zxing/zxing/tar.gz/refs/tags/zxing-3.3.3
Archive SHA-256: `4fe54268349a1bf9f72976472e064764b9b40674348fa6d8fb46d340bef87921`

Only the QR encoder/decoder, luminance/binarizer and their dependencies are vendored under
`app/src/main/java/com/google/zxing/`. No network, scanner app, Play Services or native
code is required. Retained upstream copyright/license headers apply.

Local compatibility patch: `FinderPatternFinder.CenterComparator` uses an equivalent
three-way integer comparison instead of `Integer.compare` (introduced in Android API 19).
All other upstream source files are unmodified.

Upstream NOTICE is retained in source and APK license assets for completeness; the
vendored QR subset does not include Barcode4J or JCommander.
