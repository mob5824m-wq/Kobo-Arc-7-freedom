#!/usr/bin/env python3
"""
Generates:
  1. High-resolution project logo banner (`docs/logo-banner.png`, 960x240)
  2. Header emblem drawable (`app/src/main/res/drawable-xhdpi/arc7_logo_header.png`, 96x96)
  3. 8 distinct multi-DPI (`mdpi`=48, `hdpi`=72, `xhdpi`=96, `xxhdpi`=144) launcher icons
     for `KoboArc7Freedom.apk`:
       - ic_launcher.png   (Kobo Arc 7 Freedom — Unlocked Cyan Hardware Shield)
       - ic_browser.png    (Arc7 Browser — Cobalt Globe + Emerald TLS 1.3 Badge)
       - ic_ereader.png    (Arc7 E-Reader — Warm Amber Open Book + Bookmark Ribbon)
       - ic_audio.png      (Arc7 Audio — Royal Violet Headphones + EQ Bars)
       - ic_clock.png      (Arc7 Clock — Teal Chronometer Dial + Twin Alarm Bells)
       - ic_wallpaper.png  (Arc7 Wallpapers — Sunset Frame + Mountain Peaks + Sun)
       - ic_appmgr.png     (Arc7 App Manager — Indigo 2x2 App Grid + Check Accent)
       - ic_terminal.png   (Arc7 Terminal — Obsidian Console + Neon `>_` Prompt)
  4. Multi-DPI launcher icon (`ic_launcher_home.png`) for `VanillaAndroid4Home.apk`.

Zero external dependencies (pure Python 3 standard library `zlib` + `struct` with 2x SSAA).
"""
import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write_rgba_png(path, w, h, pixels):
    """pixels: bytearray of length w * h * 4 (RGBA)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)  # Filter type 0 (None)
        row_start = y * stride
        raw.extend(pixels[row_start:row_start + stride])

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


class Canvas:
    """Normalized [0..1]x[0..1] vector helper backed by pixel buffer, downsampled 2x for crisp AA."""

    def __init__(self, size):
        self.out_size = size
        self.n = size * 2
        self.buf = bytearray(self.n * self.n * 4)

    def blend(self, px, py, r, g, b, a):
        if px < 0 or py < 0 or px >= self.n or py >= self.n or a <= 0:
            return
        idx = (py * self.n + px) * 4
        sa = a / 255.0
        da = self.buf[idx + 3] / 255.0
        out_a = sa + da * (1.0 - sa)
        if out_a <= 1e-6:
            return
        self.buf[idx] = int((r * sa + self.buf[idx] * da * (1.0 - sa)) / out_a + 0.5)
        self.buf[idx + 1] = int((g * sa + self.buf[idx + 1] * da * (1.0 - sa)) / out_a + 0.5)
        self.buf[idx + 2] = int((b * sa + self.buf[idx + 2] * da * (1.0 - sa)) / out_a + 0.5)
        self.buf[idx + 3] = int(min(255.0, out_a * 255.0 + 0.5))

    def rounded_tile(self, top_rgb, bot_rgb, border_rgb=None):
        n = self.n
        margin = 0.06 * n
        rad = 0.21 * n
        x0, y0, x1, y1 = margin, margin, n - margin, n - margin
        for py in range(n):
            t = py / max(1.0, n - 1.0)
            r = int(top_rgb[0] * (1.0 - t) + bot_rgb[0] * t)
            g = int(top_rgb[1] * (1.0 - t) + bot_rgb[1] * t)
            b = int(top_rgb[2] * (1.0 - t) + bot_rgb[2] * t)
            for px in range(n):
                cx = min(max(px + 0.5, x0 + rad), x1 - rad)
                cy = min(max(py + 0.5, y0 + rad), y1 - rad)
                dist = math.hypot((px + 0.5) - cx, (py + 0.5) - cy)
                if dist <= rad:
                    self.blend(px, py, r, g, b, 255)
                    # Subtle top glass highlight
                    if py < margin + 0.14 * n:
                        self.blend(px, py, 255, 255, 255, 26)
                    if border_rgb and dist >= rad - max(1.8, 0.022 * n):
                        self.blend(px, py, border_rgb[0], border_rgb[1], border_rgb[2], 190)

    def circle(self, ux, uy, ur, color, stroke_u=0.0):
        n = self.n
        cx, cy, rad = ux * n, uy * n, ur * n
        sw = stroke_u * n
        r, g, b, a = color
        pad = rad + sw + 2
        for py in range(max(0, int(cy - pad)), min(n, int(cy + pad) + 1)):
            for px in range(max(0, int(cx - pad)), min(n, int(cx + pad) + 1)):
                d = math.hypot((px + 0.5) - cx, (py + 0.5) - cy)
                if stroke_u <= 0:
                    if d <= rad:
                        self.blend(px, py, r, g, b, a)
                else:
                    if abs(d - rad) <= sw * 0.5:
                        self.blend(px, py, r, g, b, a)

    def arc(self, ux, uy, ur, deg_start, deg_end, stroke_u, color):
        n = self.n
        cx, cy, rad = ux * n, uy * n, ur * n
        sw = stroke_u * n
        r, g, b, a = color
        pad = rad + sw + 2
        for py in range(max(0, int(cy - pad)), min(n, int(cy + pad) + 1)):
            for px in range(max(0, int(cx - pad)), min(n, int(cx + pad) + 1)):
                dx, dy = (px + 0.5) - cx, (py + 0.5) - cy
                d = math.hypot(dx, dy)
                if abs(d - rad) <= sw * 0.5:
                    ang = (math.degrees(math.atan2(dy, dx)) + 360.0) % 360.0
                    s = deg_start % 360.0
                    e = deg_end % 360.0
                    ok = (s <= ang <= e) if s <= e else (ang >= s or ang <= e)
                    if ok:
                        self.blend(px, py, r, g, b, a)

    def rect(self, ux0, uy0, ux1, uy1, color, radius_u=0.0):
        n = self.n
        x0, y0, x1, y1 = ux0 * n, uy0 * n, ux1 * n, uy1 * n
        rad = radius_u * n
        r, g, b, a = color
        for py in range(max(0, int(y0)), min(n, int(y1) + 1)):
            for px in range(max(0, int(x0)), min(n, int(x1) + 1)):
                if rad > 0:
                    cx = min(max(px + 0.5, x0 + rad), x1 - rad)
                    cy = min(max(py + 0.5, y0 + rad), y1 - rad)
                    if math.hypot((px + 0.5) - cx, (py + 0.5) - cy) > rad:
                        continue
                elif not (x0 <= px + 0.5 <= x1 and y0 <= py + 0.5 <= y1):
                    continue
                self.blend(px, py, r, g, b, a)

    def line(self, ux0, uy0, ux1, uy1, stroke_u, color):
        n = self.n
        x0, y0, x1, y1 = ux0 * n, uy0 * n, ux1 * n, uy1 * n
        hw = max(0.9, stroke_u * n * 0.5)
        r, g, b, a = color
        minx = max(0, int(min(x0, x1) - hw - 2))
        maxx = min(n, int(max(x0, x1) + hw + 2))
        miny = max(0, int(min(y0, y1) - hw - 2))
        maxy = min(n, int(max(y0, y1) + hw + 2))
        vx, vy = x1 - x0, y1 - y0
        l2 = vx * vx + vy * vy
        for py in range(miny, maxy):
            for px in range(minx, maxx):
                wx, wy = (px + 0.5) - x0, (py + 0.5) - y0
                t = 0.0 if l2 <= 1e-6 else max(0.0, min(1.0, (wx * vx + wy * vy) / l2))
                dx = (px + 0.5) - (x0 + t * vx)
                dy = (py + 0.5) - (y0 + t * vy)
                if dx * dx + dy * dy <= hw * hw:
                    self.blend(px, py, r, g, b, a)

    def triangle(self, p0, p1, p2, color):
        n = self.n
        ax, ay = p0[0] * n, p0[1] * n
        bx, by = p1[0] * n, p1[1] * n
        cx, cy = p2[0] * n, p2[1] * n
        r, g, b, a = color
        minx = max(0, int(min(ax, bx, cx) - 1))
        maxx = min(n, int(max(ax, bx, cx) + 2))
        miny = max(0, int(min(ay, by, cy) - 1))
        maxy = min(n, int(max(ay, by, cy) + 2))

        def edge(x1, y1, x2, y2, px, py):
            return (px - x1) * (y2 - y1) - (py - y1) * (x2 - x1)

        area = edge(ax, ay, bx, by, cx, cy)
        for py in range(miny, maxy):
            for px in range(minx, maxx):
                e0 = edge(ax, ay, bx, by, px + 0.5, py + 0.5)
                e1 = edge(bx, by, cx, cy, px + 0.5, py + 0.5)
                e2 = edge(cx, cy, ax, ay, px + 0.5, py + 0.5)
                if (area >= 0 and e0 >= 0 and e1 >= 0 and e2 >= 0) or (
                    area < 0 and e0 <= 0 and e1 <= 0 and e2 <= 0
                ):
                    self.blend(px, py, r, g, b, a)

    def downsample(self):
        out = bytearray(self.out_size * self.out_size * 4)
        for y in range(self.out_size):
            for x in range(self.out_size):
                r = g = b = a = 0
                for dy in (0, 1):
                    for dx in (0, 1):
                        idx = ((y * 2 + dy) * self.n + (x * 2 + dx)) * 4
                        r += self.buf[idx]
                        g += self.buf[idx + 1]
                        b += self.buf[idx + 2]
                        a += self.buf[idx + 3]
                oi = (y * self.out_size + x) * 4
                out[oi] = r >> 2
                out[oi + 1] = g >> 2
                out[oi + 2] = b >> 2
                out[oi + 3] = a >> 2
        return out


def icon_freedom(sz):
    c = Canvas(sz)
    c.rounded_tile((12, 42, 74), (7, 20, 38), (51, 181, 229))
    # Open cyan shackle at top (unlocked freedom!)
    c.arc(0.45, 0.40, 0.14, 180, 355, 0.065, (120, 226, 255, 255))
    c.line(0.31, 0.40, 0.31, 0.50, 0.065, (120, 226, 255, 255))
    # Shield / padlock body
    c.rect(0.26, 0.46, 0.74, 0.80, (51, 181, 229, 255), 0.07)
    # Keyhole
    c.circle(0.50, 0.59, 0.055, (10, 26, 46, 255))
    c.rect(0.475, 0.60, 0.525, 0.72, (10, 26, 46, 255), 0.015)
    return c.downsample()


def icon_browser(sz):
    c = Canvas(sz)
    c.rounded_tile((21, 101, 192), (10, 46, 108), (100, 181, 246))
    # Globe circle + meridians + parallels
    c.circle(0.50, 0.50, 0.30, (255, 255, 255, 245), 0.045)
    c.line(0.20, 0.50, 0.80, 0.50, 0.036, (187, 222, 251, 240))
    c.line(0.25, 0.36, 0.75, 0.36, 0.030, (187, 222, 251, 210))
    c.line(0.25, 0.64, 0.75, 0.64, 0.030, (187, 222, 251, 210))
    c.line(0.50, 0.20, 0.50, 0.80, 0.036, (187, 222, 251, 240))
    c.arc(0.36, 0.50, 0.33, 308, 52, 0.032, (255, 255, 255, 220))
    c.arc(0.64, 0.50, 0.33, 128, 232, 0.032, (255, 255, 255, 220))
    # Green TLS 1.3 security badge bottom-right
    c.circle(0.73, 0.73, 0.13, (46, 204, 113, 255))
    c.line(0.675, 0.73, 0.715, 0.77, 0.038, (255, 255, 255, 255))
    c.line(0.715, 0.77, 0.79, 0.685, 0.038, (255, 255, 255, 255))
    return c.downsample()


def icon_ereader(sz):
    c = Canvas(sz)
    c.rounded_tile((217, 119, 36), (146, 64, 14), (251, 191, 36))
    # Left & right open pages
    c.rect(0.19, 0.25, 0.49, 0.76, (255, 250, 240, 255), 0.04)
    c.rect(0.51, 0.25, 0.81, 0.76, (250, 242, 226, 255), 0.04)
    # Text lines on left & right page
    for y in (0.36, 0.46, 0.56, 0.66):
        c.line(0.25, y, 0.44, y, 0.028, (120, 90, 60, 210))
        c.line(0.56, y, 0.75, y, 0.028, (120, 90, 60, 210))
    # Crimson ribbon bookmark on right page
    c.rect(0.66, 0.23, 0.74, 0.50, (220, 38, 38, 255), 0.01)
    return c.downsample()


def icon_audio(sz):
    c = Canvas(sz)
    c.rounded_tile((124, 58, 237), (67, 20, 138), (196, 181, 253))
    # Headphones arch + ear cups
    c.arc(0.50, 0.50, 0.28, 180, 360, 0.065, (255, 255, 255, 255))
    c.rect(0.18, 0.48, 0.29, 0.73, (255, 255, 255, 255), 0.045)
    c.rect(0.71, 0.48, 0.82, 0.73, (255, 255, 255, 255), 0.045)
    # Equalizer bars inside
    bars = [(0.37, 0.54, 0.72), (0.455, 0.42, 0.72), (0.545, 0.48, 0.72), (0.63, 0.57, 0.72)]
    for x, top, bot in bars:
        c.rect(x - 0.026, top, x + 0.026, bot, (56, 226, 255, 255), 0.018)
    return c.downsample()


def icon_clock(sz):
    c = Canvas(sz)
    c.rounded_tile((13, 148, 136), (15, 62, 68), (94, 234, 212))
    # Twin alarm bells at top corners
    c.line(0.24, 0.28, 0.34, 0.19, 0.055, (255, 224, 130, 255))
    c.line(0.76, 0.28, 0.66, 0.19, 0.055, (255, 224, 130, 255))
    # Crisp white clock dial + rim
    c.circle(0.50, 0.53, 0.28, (255, 255, 255, 250))
    c.circle(0.50, 0.53, 0.28, (20, 83, 88, 255), 0.035)
    # Hour hand (10:10 look), minute hand, coral sweep hand
    c.line(0.50, 0.53, 0.38, 0.44, 0.046, (15, 23, 42, 255))
    c.line(0.50, 0.53, 0.65, 0.40, 0.038, (15, 23, 42, 255))
    c.line(0.50, 0.53, 0.55, 0.72, 0.026, (239, 68, 68, 255))
    c.circle(0.50, 0.53, 0.032, (239, 68, 68, 255))
    return c.downsample()


def icon_wallpaper(sz):
    c = Canvas(sz)
    c.rounded_tile((190, 24, 93), (49, 16, 86), (251, 146, 60))
    # Golden sun
    c.circle(0.67, 0.35, 0.10, (253, 224, 71, 255))
    # Twin layered mountains inside rounded frame
    c.triangle((0.14, 0.78), (0.43, 0.40), (0.72, 0.78), (244, 244, 255, 245))
    c.triangle((0.40, 0.78), (0.66, 0.48), (0.87, 0.78), (199, 210, 254, 240))
    c.rect(0.14, 0.17, 0.86, 0.83, (255, 255, 255, 0))
    return c.downsample()


def icon_appmgr(sz):
    c = Canvas(sz)
    c.rounded_tile((55, 65, 130), (26, 32, 68), (129, 140, 248))
    # 2x2 crisp rounded app tiles
    c.rect(0.23, 0.23, 0.46, 0.46, (96, 165, 250, 255), 0.05)
    c.rect(0.54, 0.23, 0.77, 0.46, (251, 191, 36, 255), 0.05)
    c.rect(0.23, 0.54, 0.46, 0.77, (192, 132, 252, 255), 0.05)
    c.rect(0.54, 0.54, 0.77, 0.77, (52, 211, 153, 255), 0.05)
    # Check mark on bottom-right tile
    c.line(0.595, 0.655, 0.64, 0.705, 0.036, (15, 35, 28, 255))
    c.line(0.64, 0.705, 0.72, 0.61, 0.036, (15, 35, 28, 255))
    return c.downsample()


def icon_terminal(sz):
    c = Canvas(sz)
    c.rounded_tile((24, 30, 38), (9, 12, 16), (74, 222, 128))
    # Window dots
    c.circle(0.25, 0.24, 0.030, (239, 68, 68, 255))
    c.circle(0.35, 0.24, 0.030, (250, 204, 21, 255))
    c.circle(0.45, 0.24, 0.030, (74, 222, 128, 255))
    # Crisp green `>_` prompt
    c.line(0.25, 0.42, 0.44, 0.55, 0.055, (74, 222, 128, 255))
    c.line(0.44, 0.55, 0.25, 0.68, 0.055, (74, 222, 128, 255))
    c.line(0.50, 0.68, 0.74, 0.68, 0.055, (74, 222, 128, 255))
    return c.downsample()


def icon_home(sz):
    c = Canvas(sz)
    c.rounded_tile((37, 138, 214), (16, 68, 128), (130, 210, 255))
    # Roof triangle + house walls + glowing doorway
    c.triangle((0.18, 0.50), (0.50, 0.21), (0.82, 0.50), (255, 255, 255, 255))
    c.rect(0.28, 0.48, 0.72, 0.79, (255, 255, 255, 255), 0.03)
    c.rect(0.43, 0.58, 0.57, 0.79, (21, 94, 160, 255), 0.02)
    return c.downsample()


FONT_5X7 = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "G": ["01111", "10000", "10000", "10011", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "J": ["00111", "00010", "00010", "00010", "10010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "0": ["01110", "10011", "10101", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "10000", "11110", "00001", "00001", "11110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
    "/": ["00001", "00010", "00100", "00100", "01000", "10000", "00000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}


def draw_text_block(buf, w, h, x, y, text, scale, rgb):
    cx = x
    for ch in text.upper():
        glyph = FONT_5X7.get(ch, FONT_5X7[" "])
        for gy in range(7):
            for gx in range(5):
                if glyph[gy][gx] == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            px, py = cx + gx * scale + sx, y + gy * scale + sy
                            if 0 <= px < w and 0 <= py < h:
                                idx = (py * w + px) * 4
                                buf[idx] = rgb[0]
                                buf[idx + 1] = rgb[1]
                                buf[idx + 2] = rgb[2]
                                buf[idx + 3] = 255
        cx += 6 * scale


def generate_logo_banner():
    w, h = 960, 250
    try:
        from PIL import Image, ImageDraw, ImageFont

        img = Image.new("RGBA", (w, h), (10, 20, 36, 255))
        px = img.load()
        for y in range(h):
            t = y / (h - 1.0)
            for x in range(w):
                sx = x / (w - 1.0)
                if y < 4 or y >= h - 4:
                    px[x, y] = (51, 181, 229, 255)
                else:
                    px[x, y] = (
                        min(255, int(8 + 14 * (1.0 - t) + 18 * sx)),
                        min(255, int(18 + 30 * (1.0 - t) + 26 * sx)),
                        min(255, int(36 + 58 * (1.0 - t) + 44 * sx)),
                        255,
                    )

        # Left large unlocked shield emblem
        emb_raw = icon_freedom(176)
        emb_img = Image.frombytes("RGBA", (176, 176), bytes(emb_raw))
        img.alpha_composite(emb_img, (32, 37))

        bold_candidates = [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        ]
        reg_candidates = [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        ]
        f_title = f_sub = f_tag = ImageFont.load_default()
        for p in bold_candidates:
            if os.path.exists(p):
                f_title = ImageFont.truetype(p, 46)
                f_tag = ImageFont.truetype(p, 17)
                break
        for p in reg_candidates:
            if os.path.exists(p):
                f_sub = ImageFont.truetype(p, 21)
                break

        d = ImageDraw.Draw(img)
        d.text((236, 34), "KOBO ARC 7 FREEDOM", fill=(255, 255, 255, 255), font=f_title)
        d.text(
            (238, 92),
            "Android 4.2 Jelly Bean  •  2026 TLS 1.3  •  Alarms & Wallpapers",
            fill=(104, 214, 255, 255),
            font=f_sub,
        )
        d.text(
            (238, 124),
            "ZERO-BRICK VERIFIED SUITE  v1.7.0   •   MT8125 & TEGRA 3",
            fill=(134, 239, 172, 255),
            font=f_tag,
        )

        # Row of all 8 crisp suite app icons right inside the banner!
        suite_fns = [
            icon_freedom,
            icon_browser,
            icon_ereader,
            icon_audio,
            icon_clock,
            icon_wallpaper,
            icon_appmgr,
            icon_terminal,
        ]
        for idx, fn in enumerate(suite_fns):
            mini = Image.frombytes("RGBA", (54, 54), bytes(fn(54)))
            img.alpha_composite(mini, (238 + idx * 66, 160))

        os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
        img.save(os.path.join(ROOT, "docs", "logo-banner.png"), "PNG")
        return
    except Exception:
        pass

    buf = bytearray(w * h * 4)
    for y in range(h):
        t = y / (h - 1.0)
        for x in range(w):
            sx = x / (w - 1.0)
            idx = (y * w + x) * 4
            buf[idx] = min(255, int(8 + 14 * (1.0 - t) + 18 * sx))
            buf[idx + 1] = min(255, int(18 + 32 * (1.0 - t) + 28 * sx))
            buf[idx + 2] = min(255, int(34 + 62 * (1.0 - t) + 46 * sx))
            buf[idx + 3] = 255
            if y < 4 or y >= h - 4:
                buf[idx], buf[idx + 1], buf[idx + 2] = 51, 181, 229
    emblem = icon_freedom(168)
    ox, oy = 32, 36
    for ey in range(168):
        for ex in range(168):
            si = (ey * 168 + ex) * 4
            sa = emblem[si + 3] / 255.0
            if sa <= 0:
                continue
            di = ((oy + ey) * w + (ox + ex)) * 4
            buf[di] = int(emblem[si] * sa + buf[di] * (1.0 - sa))
            buf[di + 1] = int(emblem[si + 1] * sa + buf[di + 1] * (1.0 - sa))
            buf[di + 2] = int(emblem[si + 2] * sa + buf[di + 2] * (1.0 - sa))
            buf[di + 3] = 255
    draw_text_block(buf, w, h, 232, 56, "KOBO ARC 7 FREEDOM", 6, (255, 255, 255))
    draw_text_block(buf, w, h, 232, 122, "ANDROID 4.2 + ROOT + TLS 1.3 + APPS", 3, (88, 206, 250))
    draw_text_block(buf, w, h, 232, 160, "ZERO-BRICK VERIFIED SUITE V1.7.0", 3, (134, 239, 172))
    write_rgba_png(os.path.join(ROOT, "docs", "logo-banner.png"), w, h, buf)


def main():
    densities = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144)]
    icons = [
        ("ic_launcher.png", icon_freedom),
        ("ic_browser.png", icon_browser),
        ("ic_ereader.png", icon_ereader),
        ("ic_audio.png", icon_audio),
        ("ic_clock.png", icon_clock),
        ("ic_wallpaper.png", icon_wallpaper),
        ("ic_appmgr.png", icon_appmgr),
        ("ic_terminal.png", icon_terminal),
    ]
    for dpi, sz in densities:
        for fname, fn in icons:
            out = os.path.join(ROOT, "app", "src", "main", "res", "drawable-" + dpi, fname)
            write_rgba_png(out, sz, sz, fn(sz))
        home_out = os.path.join(ROOT, "launcher-android4", "res", "drawable-" + dpi, "ic_launcher_home.png")
        write_rgba_png(home_out, sz, sz, icon_home(sz))

    # Dedicated header emblem badge inside MainActivity
    write_rgba_png(
        os.path.join(ROOT, "app", "src", "main", "res", "drawable-xhdpi", "arc7_logo_header.png"),
        96,
        96,
        icon_freedom(96),
    )
    generate_logo_banner()
    print("Generated project logo banner + 8 multi-DPI Freedom app icons + Android 4 Home icon OK!")


if __name__ == "__main__":
    main()
