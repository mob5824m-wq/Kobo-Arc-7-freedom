#!/usr/bin/env python3
"""Bundle the offline optical-transfer companion, without npm, a CDN or network access."""
from pathlib import Path
import argparse
import html

ROOT = Path(__file__).resolve().parent.parent

def render():
    replacements = {
        'ENCODER': ROOT / 'third_party/qrcode-generator/qrcode.js',
        'DECODER': ROOT / 'third_party/jsqr/jsQR.js',
        'PROTOCOL': ROOT / 'tools/qr-transfer/protocol.js',
        'BROWSER': ROOT / 'tools/qr-transfer/browser.js',
    }
    text = (ROOT / 'tools/qr-transfer/template.html').read_text()
    for key, path in replacements.items():
        text = text.replace('@@' + key + '@@', path.read_text().replace('</script', '<\\/script'))
    licenses = '\n\n'.join((ROOT / 'third_party' / name / 'LICENSE').read_text() for name in ('qrcode-generator', 'jsqr'))
    return text.replace('@@LICENSES@@', html.escape(licenses))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    output = ROOT / 'tools/qr-transfer.html'
    text = render()
    if args.check:
        if not output.is_file() or output.read_text() != text:
            raise SystemExit('Offline QR companion is stale; run scripts/build-qr-companion.py')
        print('PASS: self-contained offline QR companion matches sources and vendored dependencies')
    else:
        output.write_text(text)
        print(f'Wrote {output.relative_to(ROOT)} ({len(text.encode())} bytes, no external requests)')

if __name__ == '__main__':
    main()
