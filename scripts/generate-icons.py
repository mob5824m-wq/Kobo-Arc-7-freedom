#!/usr/bin/env python3
"""
Generates ALL custom Kobo Arc 7 OS Skin vector assets & Reskinned System App Icon Pack:
  1. High-resolution project logo banner (`releases/logo-banner.png`, 960x240 — NO `docs/`!)
  2. Header emblem drawable (`app/src/main/res/drawable-xhdpi/arc7_logo_header.png`, 96x96)
  3. 28 crisp 2x-SSAA multi-DPI (`mdpi`=48, `hdpi`=72, `xhdpi`=96, `xxhdpi`=144) squircle
     icons generated into BOTH `app/src/main/res/drawable-*/` AND `launcher-android4/res/drawable-*/`
     reskinning EVERY built-in app AND EVERY default Android 4.2 / Kobo system app:
       - ic_launcher.png        (Kobo Arc 7 Freedom Control Center — Unlocked Cyan Shield)
       - ic_launcher_home.png   (Arc7 Home Launcher — Glass House + Dock Dots)
       - ic_browser.png         (Browser — Cobalt Globe + Emerald TLS 1.3 Lock)
       - ic_ereader.png         (E-Reader / Kobo Books — Amber Parchment Open Book)
       - ic_audio.png           (Audio / Music — Royal Violet Studio Headphones + EQ)
       - ic_clock.png           (Clock & Alarms — Teal Chronometer + Twin Bells)
       - ic_wallpaper.png       (Wallpapers & Skin Studio — Sunset Peaks + Swatch)
       - ic_appmgr.png          (App Manager — Indigo 2x2 Squircle Grid + Check)
       - ic_terminal.png        (Root Terminal — Obsidian Console + Neon `>_`)
       - ic_wired_net.png       (Chromebook Wired Internet — Electric Cyan USB + Ethernet Globe)
       - ic_calendar.png        (Calendar & Agenda — Crisp Coral Top Header + `22` Grid)
       - ic_calculator.png      (Scientific Calculator — Slate Pad + Orange `=` Accent)
       - ic_notes.png           (Notes & Sketchpad — Warm Canary Pad + Fountain Pen)
       - ic_files.png           (Files & ZIP Explorer — Sky Folder + Tab Bevel)
       - ic_photos.png          (Photos & Editor — Prismatic Pinwheel Flower Blossom)
       - ic_camera.png          (Arc7 HD Camera — Charcoal Body + Sapphire Multi-Coated Lens)
       - ic_apk_installer.png   (Custom APK Installer — Emerald Package Box + Down Arrow)
       - ic_vlc.png             (VLC Media Player — Iconic Orange & White Traffic Cone)
       - ic_aurora.png          (Aurora Store — Northern Lights Borealis Shopping Bag)
       - ic_fdroid.png          (F-Droid Classic — Open-Source Cobalt Robot Emblem)
       - ic_sys_settings.png    (Android System Settings — Precision Titanium 8-Tooth Gear)
       - ic_sys_downloads.png   (System Downloads — Teal Tray + Crisp Down Arrow)
       - ic_sys_email.png       (System Email — Sky Envelope + Folded Seal)
       - ic_sys_contacts.png    (System Contacts / People — Azure Portrait Silhouette)
       - ic_sys_recorder.png    (System Sound Recorder — Studio Ruby Condenser Mic)
       - ic_sys_search.png      (System Quick Search — Frosted Magnifying Lens)
       - ic_sys_music.png       (System Music — Crimson Eighth-Note Vinyl Tile)
       - ic_sys_video.png       (System Videos — Cinema Clapper + Play Triangle)

Zero external dependencies (pure Python 3 standard library `zlib` + `struct` with 2x SSAA).
"""
import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write_rgba_png(path, w, h, pixels):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
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
        margin = 0.055 * n
        rad = 0.225 * n
        x0, y0, x1, y1 = margin, margin, n - margin, n - margin
        for py in range(n):
            t = py / max(1.0, n - 1.0)
            r = int(top_rgb[0] * (1.0 - t) + bot_rgb[0] * t)
            g = int(top_rgb[1] * (1.0 - t) + bot_rgb[1] * t)
            b = int(top_rgb[2] * (1.0 - t) + bot_rgb[2] * t)
            for py_off in (0,):
                pass
            for px in range(n):
                cx = min(max(px + 0.5, x0 + rad), x1 - rad)
                cy = min(max(py + 0.5, y0 + rad), y1 - rad)
                dist = math.hypot((px + 0.5) - cx, (py + 0.5) - cy)
                if dist <= rad:
                    self.blend(px, py, r, g, b, 255)
                    if py < margin + 0.16 * n:
                        self.blend(px, py, 255, 255, 255, 30)
                    elif py > y1 - 0.10 * n:
                        self.blend(px, py, 0, 0, 0, 34)
                    if border_rgb and dist >= rad - max(1.8, 0.024 * n):
                        self.blend(px, py, border_rgb[0], border_rgb[1], border_rgb[2], 205)

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
                elif abs(d - rad) <= sw * 0.5:
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

        def edge(x0, y0, x1, y1, xp, yp):
            return (xp - x0) * (y1 - y0) - (yp - y0) * (x1 - x0)

        area = edge(ax, ay, bx, by, cx, cy)
        if abs(area) < 1e-6:
            return
        for py in range(miny, maxy):
            for px in range(minx, maxx):
                xp, yp = px + 0.5, py + 0.5
                w0 = edge(bx, by, cx, cy, xp, yp)
                w1 = edge(cx, cy, ax, ay, xp, yp)
                w2 = edge(ax, ay, bx, by, xp, yp)
                if (w0 >= 0 and w1 >= 0 and w2 >= 0) or (w0 <= 0 and w1 <= 0 and w2 <= 0):
                    self.blend(px, py, r, g, b, a)

    def downsample(self):
        s = self.out_size
        out = bytearray(s * s * 4)
        for y in range(s):
            for x in range(s):
                r = g = b = a = 0
                for dy in (0, 1):
                    for dx in (0, 1):
                        i = ((y * 2 + dy) * self.n + (x * 2 + dx)) * 4
                        r += self.buf[i]
                        g += self.buf[i + 1]
                        b += self.buf[i + 2]
                        a += self.buf[i + 3]
                o = (y * s + x) * 4
                out[o] = r // 4
                out[o + 1] = g // 4
                out[o + 2] = b // 4
                out[o + 3] = a // 4
        return out


W = (255, 255, 255, 255)
W_SOFT = (235, 245, 255, 220)


# ==================== 28 CUSTOM SQUIRCLE ICON DRAWERS ====================

def draw_ic_launcher(c):
    c.rounded_tile((12, 34, 62), (7, 16, 30), (56, 189, 248))
    c.triangle((0.50, 0.18), (0.80, 0.30), (0.20, 0.30), (14, 165, 233, 255))
    c.rect(0.20, 0.30, 0.80, 0.58, (14, 165, 233, 255), 0.05)
    c.triangle((0.20, 0.56), (0.80, 0.56), (0.50, 0.84), (2, 132, 199, 255))
    c.arc(0.56, 0.40, 0.10, 180, 355, 0.055, W)
    c.rect(0.36, 0.44, 0.64, 0.66, W, 0.045)
    c.circle(0.50, 0.54, 0.038, (12, 34, 62, 255))


def draw_ic_home(c):
    c.rounded_tile((20, 118, 196), (11, 45, 92), (125, 211, 252))
    c.triangle((0.50, 0.20), (0.82, 0.47), (0.18, 0.47), W)
    c.rect(0.27, 0.45, 0.73, 0.77, W, 0.04)
    c.rect(0.43, 0.56, 0.57, 0.77, (14, 116, 190, 255), 0.03)
    c.circle(0.35, 0.84, 0.025, (186, 230, 253, 230))
    c.circle(0.50, 0.84, 0.032, W)
    c.circle(0.65, 0.84, 0.025, (186, 230, 253, 230))


def draw_ic_browser(c):
    c.rounded_tile((30, 108, 235), (17, 46, 138), (96, 165, 250))
    c.circle(0.48, 0.49, 0.27, W, 0.05)
    c.line(0.21, 0.49, 0.75, 0.49, 0.042, W_SOFT)
    c.line(0.48, 0.22, 0.48, 0.76, 0.042, W_SOFT)
    c.arc(0.35, 0.49, 0.24, 305, 55, 0.038, W_SOFT)
    c.arc(0.61, 0.49, 0.24, 125, 235, 0.038, W_SOFT)
    c.circle(0.73, 0.72, 0.14, (16, 185, 129, 255))
    c.line(0.67, 0.72, 0.72, 0.77, 0.045, W)
    c.line(0.72, 0.77, 0.80, 0.67, 0.045, W)


def draw_ic_ereader(c):
    c.rounded_tile((234, 138, 28), (154, 68, 12), (253, 224, 71))
    c.rect(0.19, 0.25, 0.49, 0.75, W, 0.04)
    c.rect(0.51, 0.25, 0.81, 0.75, (254, 243, 199, 255), 0.04)
    for y in (0.36, 0.46, 0.56, 0.65):
        c.line(0.25, y, 0.43, y, 0.034, (180, 83, 9, 210))
        c.line(0.57, y, 0.75, y, 0.034, (180, 83, 9, 210))
    c.rect(0.64, 0.22, 0.72, 0.46, (220, 38, 38, 255), 0.01)


def draw_ic_audio(c):
    c.rounded_tile((139, 61, 235), (67, 22, 142), (196, 181, 253))
    c.arc(0.50, 0.50, 0.27, 180, 360, 0.065, W)
    c.rect(0.19, 0.48, 0.30, 0.72, W, 0.05)
    c.rect(0.70, 0.48, 0.81, 0.72, W, 0.05)
    c.rect(0.37, 0.55, 0.43, 0.73, (56, 189, 248, 255), 0.02)
    c.rect(0.47, 0.44, 0.53, 0.73, (244, 114, 182, 255), 0.02)
    c.rect(0.57, 0.51, 0.63, 0.73, (52, 211, 153, 255), 0.02)


def draw_ic_clock(c):
    c.rounded_tile((13, 148, 136), (11, 68, 74), (94, 234, 212))
    c.circle(0.50, 0.53, 0.27, W)
    c.circle(0.50, 0.53, 0.27, (20, 184, 166, 255), 0.035)
    c.line(0.24, 0.26, 0.35, 0.19, 0.055, W)
    c.line(0.76, 0.26, 0.65, 0.19, 0.055, W)
    c.line(0.50, 0.53, 0.50, 0.35, 0.048, (15, 23, 42, 255))
    c.line(0.50, 0.53, 0.64, 0.59, 0.042, (15, 23, 42, 255))
    c.circle(0.50, 0.53, 0.035, (239, 68, 68, 255))


def draw_ic_wallpaper(c):
    c.rounded_tile((236, 72, 153), (79, 70, 229), (249, 168, 212))
    c.circle(0.67, 0.34, 0.095, (253, 224, 71, 255))
    c.triangle((0.14, 0.78), (0.43, 0.42), (0.70, 0.78), (255, 255, 255, 235))
    c.triangle((0.44, 0.78), (0.68, 0.50), (0.88, 0.78), (224, 231, 255, 220))


def draw_ic_appmgr(c):
    c.rounded_tile((79, 70, 229), (30, 27, 94), (165, 180, 252))
    for gx, gy in ((0.24, 0.24), (0.54, 0.24), (0.24, 0.54)):
        c.rect(gx, gy, gx + 0.22, gy + 0.22, W, 0.05)
    c.rect(0.54, 0.54, 0.76, 0.76, (52, 211, 153, 255), 0.05)
    c.line(0.59, 0.65, 0.64, 0.70, 0.042, W)
    c.line(0.64, 0.70, 0.72, 0.60, 0.042, W)


def draw_ic_terminal(c):
    c.rounded_tile((24, 31, 44), (9, 12, 18), (52, 211, 153))
    c.line(0.24, 0.35, 0.43, 0.48, 0.058, (52, 211, 153, 255))
    c.line(0.43, 0.48, 0.24, 0.61, 0.058, (52, 211, 153, 255))
    c.rect(0.48, 0.58, 0.75, 0.65, W, 0.02)


def draw_ic_wired_net(c):
    c.rounded_tile((6, 130, 196), (8, 47, 73), (34, 211, 238))
    # USB trident + Ethernet link plug
    c.line(0.50, 0.20, 0.50, 0.76, 0.052, W)
    c.triangle((0.50, 0.15), (0.42, 0.27), (0.58, 0.27), W)
    c.line(0.50, 0.58, 0.32, 0.44, 0.044, W)
    c.line(0.32, 0.44, 0.32, 0.35, 0.044, W)
    c.circle(0.32, 0.34, 0.048, (52, 211, 153, 255))
    c.line(0.50, 0.50, 0.68, 0.38, 0.044, W)
    c.line(0.68, 0.38, 0.68, 0.29, 0.044, W)
    c.rect(0.63, 0.24, 0.73, 0.34, (250, 204, 21, 255), 0.015)
    c.circle(0.50, 0.77, 0.068, W)


def draw_ic_calendar(c):
    c.rounded_tile((248, 250, 252), (226, 232, 240), (203, 213, 225))
    # Crisp red calendar header bar + binder rings + bold '22' bars
    c.rect(0.06, 0.06, 0.94, 0.35, (239, 68, 68, 255), 0.14)
    c.rect(0.06, 0.24, 0.94, 0.36, (220, 38, 38, 255), 0.0)
    c.rect(0.30, 0.13, 0.37, 0.27, W, 0.03)
    c.rect(0.63, 0.13, 0.70, 0.27, W, 0.03)
    # Clean bold "22" digits on crisp white calendar sheet
    for ox in (0.24, 0.54):
        c.rect(ox, 0.44, ox + 0.21, 0.50, (15, 23, 42, 255), 0.02)
        c.rect(ox + 0.14, 0.44, ox + 0.21, 0.62, (15, 23, 42, 255), 0.02)
        c.rect(ox, 0.57, ox + 0.21, 0.63, (15, 23, 42, 255), 0.02)
        c.rect(ox, 0.57, ox + 0.07, 0.76, (15, 23, 42, 255), 0.02)
        c.rect(ox, 0.71, ox + 0.21, 0.78, (15, 23, 42, 255), 0.02)


def draw_ic_calculator(c):
    c.rounded_tile((30, 41, 59), (15, 23, 42), (100, 116, 139))
    c.rect(0.20, 0.17, 0.80, 0.34, (56, 189, 248, 235), 0.05)
    for row in range(3):
        for col in range(2):
            bx = 0.20 + col * 0.20
            by = 0.41 + row * 0.145
            c.rect(bx, by, bx + 0.15, by + 0.105, (203, 213, 225, 235), 0.03)
    # Bright orange '=' operator column
    c.rect(0.62, 0.41, 0.80, 0.515, (249, 115, 22, 255), 0.03)
    c.rect(0.62, 0.555, 0.80, 0.805, (249, 115, 22, 255), 0.04)
    c.rect(0.66, 0.64, 0.76, 0.675, W, 0.01)
    c.rect(0.66, 0.70, 0.76, 0.735, W, 0.01)


def draw_ic_notes(c):
    c.rounded_tile((250, 204, 21), (217, 119, 6), (254, 240, 138))
    c.rect(0.18, 0.18, 0.82, 0.82, (254, 252, 232, 255), 0.07)
    c.rect(0.18, 0.18, 0.82, 0.31, (245, 158, 11, 255), 0.05)
    for y in (0.42, 0.54, 0.66):
        c.line(0.26, y, 0.64, y, 0.035, (120, 53, 15, 185))
    c.line(0.56, 0.72, 0.80, 0.44, 0.085, (15, 23, 42, 255))
    c.triangle((0.50, 0.78), (0.53, 0.68), (0.61, 0.75), (239, 68, 68, 255))


def draw_ic_files(c):
    c.rounded_tile((14, 165, 233), (3, 105, 161), (125, 211, 252))
    c.rect(0.16, 0.24, 0.47, 0.38, (253, 224, 71, 255), 0.05)
    c.rect(0.16, 0.32, 0.84, 0.79, (254, 240, 138, 255), 0.07)
    c.rect(0.16, 0.40, 0.84, 0.79, W, 0.06)
    c.rect(0.42, 0.53, 0.58, 0.60, (2, 132, 199, 220), 0.03)


def draw_ic_photos(c):
    c.rounded_tile((255, 255, 255), (226, 232, 240), (199, 210, 254))
    petals = [
        (0.50, 0.30, (239, 68, 68, 220)),
        (0.68, 0.39, (249, 115, 22, 220)),
        (0.68, 0.60, (234, 179, 8, 220)),
        (0.50, 0.70, (16, 185, 129, 220)),
        (0.32, 0.60, (14, 165, 233, 220)),
        (0.32, 0.39, (168, 85, 247, 220)),
    ]
    for px, py, col in petals:
        c.circle(px, py, 0.145, col)
    c.circle(0.50, 0.50, 0.075, W)


def draw_ic_camera(c):
    c.rounded_tile((51, 65, 85), (15, 23, 42), (148, 163, 184))
    c.rect(0.36, 0.20, 0.64, 0.31, (203, 213, 225, 255), 0.04)
    c.rect(0.16, 0.28, 0.84, 0.78, (30, 41, 59, 255), 0.09)
    c.circle(0.50, 0.54, 0.21, W)
    c.circle(0.50, 0.54, 0.165, (14, 116, 190, 255))
    c.circle(0.50, 0.54, 0.095, (7, 25, 58, 255))
    c.circle(0.45, 0.49, 0.035, (125, 211, 252, 230))
    c.circle(0.74, 0.36, 0.032, (239, 68, 68, 255))


def draw_ic_apk_installer(c):
    c.rounded_tile((16, 185, 129), (4, 120, 87), (110, 231, 183))
    c.rect(0.21, 0.22, 0.79, 0.78, W, 0.08)
    c.rect(0.45, 0.30, 0.55, 0.56, (5, 150, 105, 255), 0.02)
    c.triangle((0.35, 0.52), (0.65, 0.52), (0.50, 0.69), (5, 150, 105, 255))
    c.rect(0.34, 0.71, 0.66, 0.75, (5, 150, 105, 255), 0.02)


def draw_ic_vlc(c):
    c.rounded_tile((255, 136, 0), (204, 82, 0), (254, 215, 170))
    c.rect(0.19, 0.73, 0.81, 0.83, W, 0.035)
    c.triangle((0.50, 0.16), (0.74, 0.75), (0.26, 0.75), (255, 115, 0, 255))
    c.line(0.38, 0.43, 0.62, 0.43, 0.075, W)
    c.line(0.32, 0.60, 0.68, 0.60, 0.082, W)


def draw_ic_aurora(c):
    c.rounded_tile((16, 185, 129), (59, 130, 246), (167, 243, 208))
    c.arc(0.50, 0.34, 0.11, 180, 360, 0.05, W)
    c.rect(0.24, 0.34, 0.76, 0.80, W, 0.08)
    c.triangle((0.50, 0.44), (0.63, 0.70), (0.37, 0.70), (16, 185, 129, 255))


def draw_ic_fdroid(c):
    c.rounded_tile((25, 118, 210), (13, 71, 161), (144, 202, 249))
    c.line(0.33, 0.19, 0.40, 0.30, 0.045, W)
    c.line(0.67, 0.19, 0.60, 0.30, 0.045, W)
    c.rect(0.25, 0.28, 0.75, 0.50, (139, 195, 74, 255), 0.07)
    c.circle(0.39, 0.39, 0.045, W)
    c.circle(0.61, 0.39, 0.045, W)
    c.rect(0.25, 0.53, 0.75, 0.81, (66, 165, 245, 255), 0.07)
    c.circle(0.50, 0.67, 0.075, W, 0.038)


def draw_ic_sys_settings(c):
    c.rounded_tile((71, 85, 105), (30, 41, 59), (148, 163, 184))
    for i in range(8):
        ang = math.radians(i * 45.0)
        c.line(0.50 + 0.18 * math.cos(ang), 0.50 + 0.18 * math.sin(ang),
               0.50 + 0.31 * math.cos(ang), 0.50 + 0.31 * math.sin(ang), 0.095, W)
    c.circle(0.50, 0.50, 0.22, W)
    c.circle(0.50, 0.50, 0.10, (30, 41, 59, 255))


def draw_ic_sys_downloads(c):
    c.rounded_tile((8, 145, 178), (21, 94, 117), (103, 232, 249))
    c.rect(0.44, 0.21, 0.56, 0.53, W, 0.025)
    c.triangle((0.30, 0.49), (0.70, 0.49), (0.50, 0.69), W)
    c.rect(0.24, 0.72, 0.76, 0.80, W, 0.03)


def draw_ic_sys_email(c):
    c.rounded_tile((2, 132, 199), (30, 64, 175), (147, 197, 253))
    c.rect(0.17, 0.28, 0.83, 0.74, W, 0.06)
    c.line(0.20, 0.31, 0.50, 0.55, 0.045, (2, 132, 199, 255))
    c.line(0.80, 0.31, 0.50, 0.55, 0.045, (2, 132, 199, 255))


def draw_ic_sys_contacts(c):
    c.rounded_tile((37, 99, 235), (29, 78, 216), (147, 197, 253))
    c.circle(0.50, 0.37, 0.135, W)
    c.arc(0.50, 0.83, 0.25, 195, 345, 0.19, W)


def draw_ic_sys_recorder(c):
    c.rounded_tile((225, 29, 72), (136, 19, 55), (253, 164, 175))
    c.rect(0.40, 0.20, 0.60, 0.56, W, 0.10)
    c.arc(0.50, 0.48, 0.19, 0, 180, 0.045, W)
    c.line(0.50, 0.67, 0.50, 0.80, 0.048, W)
    c.line(0.36, 0.80, 0.64, 0.80, 0.048, W)


def draw_ic_sys_search(c):
    c.rounded_tile((59, 130, 246), (29, 78, 216), (191, 219, 254))
    c.circle(0.45, 0.45, 0.19, W, 0.065)
    c.line(0.59, 0.59, 0.77, 0.77, 0.075, W)


def draw_ic_sys_music(c):
    c.rounded_tile((236, 72, 153), (190, 18, 60), (251, 207, 232))
    c.line(0.42, 0.66, 0.42, 0.27, 0.055, W)
    c.line(0.70, 0.60, 0.70, 0.22, 0.055, W)
    c.line(0.42, 0.27, 0.70, 0.22, 0.085, W)
    c.circle(0.35, 0.68, 0.085, W)
    c.circle(0.63, 0.62, 0.085, W)


def draw_ic_sys_video(c):
    c.rounded_tile((124, 58, 237), (67, 56, 202), (196, 181, 253))
    c.rect(0.18, 0.25, 0.82, 0.77, W, 0.07)
    c.triangle((0.42, 0.37), (0.42, 0.65), (0.66, 0.51), (109, 40, 217, 255))


def draw_ic_pdf(c):
    c.rounded_tile((220, 38, 38), (153, 27, 27), (252, 165, 165))
    c.rect(0.24, 0.17, 0.76, 0.83, W, 0.06)
    c.triangle((0.58, 0.17), (0.76, 0.17), (0.76, 0.35), (254, 202, 202, 255))
    c.rect(0.32, 0.36, 0.68, 0.43, (220, 38, 38, 255), 0.02)
    c.rect(0.32, 0.50, 0.68, 0.56, (185, 28, 28, 220), 0.02)
    c.rect(0.32, 0.63, 0.56, 0.69, (185, 28, 28, 220), 0.02)


def draw_ic_weather(c):
    c.rounded_tile((14, 165, 233), (2, 132, 199), (186, 230, 253))
    c.circle(0.62, 0.36, 0.16, (253, 224, 71, 255))
    c.circle(0.39, 0.58, 0.16, W)
    c.circle(0.55, 0.54, 0.19, W)
    c.circle(0.69, 0.61, 0.13, W)
    c.rect(0.34, 0.59, 0.72, 0.74, W, 0.05)


def draw_ic_radio(c):
    c.rounded_tile((234, 88, 12), (154, 52, 18), (253, 186, 116))
    c.rect(0.20, 0.40, 0.80, 0.80, W, 0.08)
    c.line(0.30, 0.40, 0.68, 0.20, 0.055, W)
    c.circle(0.37, 0.60, 0.10, (234, 88, 12, 255))
    c.rect(0.54, 0.51, 0.72, 0.56, (154, 52, 18, 255), 0.02)
    c.rect(0.54, 0.63, 0.72, 0.68, (154, 52, 18, 255), 0.02)


def draw_ic_arcade(c):
    c.rounded_tile((147, 51, 234), (88, 28, 135), (216, 180, 254))
    c.rect(0.16, 0.32, 0.84, 0.72, W, 0.14)
    c.rect(0.31, 0.44, 0.37, 0.60, (126, 34, 206, 255), 0.015)
    c.rect(0.26, 0.49, 0.42, 0.55, (126, 34, 206, 255), 0.015)
    c.circle(0.62, 0.56, 0.052, (239, 68, 68, 255))
    c.circle(0.71, 0.47, 0.052, (16, 185, 129, 255))


def draw_ic_shield(c):
    c.rounded_tile((5, 150, 105), (6, 78, 59), (110, 231, 183))
    c.triangle((0.50, 0.82), (0.22, 0.48), (0.78, 0.48), W)
    c.rect(0.24, 0.22, 0.76, 0.52, W, 0.07)
    c.line(0.38, 0.49, 0.47, 0.59, 0.06, (5, 150, 105, 255))
    c.line(0.47, 0.59, 0.64, 0.38, 0.06, (5, 150, 105, 255))


def draw_ic_boost(c):
    c.rounded_tile((8, 145, 178), (15, 23, 42), (103, 232, 249))
    c.arc(0.50, 0.56, 0.28, 195, 345, 0.065, W)
    c.line(0.50, 0.56, 0.67, 0.36, 0.065, (250, 204, 21, 255))
    c.circle(0.50, 0.56, 0.065, W)


def draw_ic_display(c):
    c.rounded_tile((37, 99, 235), (30, 41, 59), (147, 197, 253))
    c.rect(0.17, 0.23, 0.65, 0.58, (191, 219, 254, 235), 0.05)
    c.rect(0.35, 0.40, 0.83, 0.75, W, 0.05)
    c.rect(0.42, 0.47, 0.76, 0.68, (37, 99, 235, 255), 0.025)


def draw_ic_split(c):
    c.rounded_tile((20, 184, 166), (15, 118, 110), (153, 246, 228))
    c.rect(0.17, 0.24, 0.47, 0.76, W, 0.05)
    c.rect(0.53, 0.24, 0.83, 0.76, (204, 251, 241, 235), 0.05)
    c.line(0.50, 0.18, 0.50, 0.82, 0.035, (253, 224, 71, 255))


def draw_ic_dictionary(c):
    c.rounded_tile((30, 64, 175), (23, 37, 84), (147, 197, 253))
    c.rect(0.24, 0.18, 0.76, 0.82, W, 0.06)
    c.rect(0.24, 0.18, 0.34, 0.82, (96, 165, 250, 255), 0.03)
    c.rect(0.58, 0.18, 0.67, 0.40, (244, 63, 94, 255), 0.01)
    c.line(0.42, 0.58, 0.51, 0.34, 0.05, (30, 64, 175, 255))
    c.line(0.51, 0.34, 0.60, 0.58, 0.05, (30, 64, 175, 255))
    c.line(0.45, 0.50, 0.57, 0.50, 0.045, (30, 64, 175, 255))


ALL_ICONS = [
    ("ic_launcher.png", draw_ic_launcher),
    ("ic_launcher_home.png", draw_ic_home),
    ("ic_browser.png", draw_ic_browser),
    ("ic_ereader.png", draw_ic_ereader),
    ("ic_audio.png", draw_ic_audio),
    ("ic_clock.png", draw_ic_clock),
    ("ic_wallpaper.png", draw_ic_wallpaper),
    ("ic_appmgr.png", draw_ic_appmgr),
    ("ic_terminal.png", draw_ic_terminal),
    ("ic_wired_net.png", draw_ic_wired_net),
    ("ic_calendar.png", draw_ic_calendar),
    ("ic_calculator.png", draw_ic_calculator),
    ("ic_notes.png", draw_ic_notes),
    ("ic_files.png", draw_ic_files),
    ("ic_photos.png", draw_ic_photos),
    ("ic_camera.png", draw_ic_camera),
    ("ic_apk_installer.png", draw_ic_apk_installer),
    ("ic_vlc.png", draw_ic_vlc),
    ("ic_aurora.png", draw_ic_aurora),
    ("ic_fdroid.png", draw_ic_fdroid),
    ("ic_sys_settings.png", draw_ic_sys_settings),
    ("ic_sys_downloads.png", draw_ic_sys_downloads),
    ("ic_sys_email.png", draw_ic_sys_email),
    ("ic_sys_contacts.png", draw_ic_sys_contacts),
    ("ic_sys_recorder.png", draw_ic_sys_recorder),
    ("ic_sys_search.png", draw_ic_sys_search),
    ("ic_sys_music.png", draw_ic_sys_music),
    ("ic_sys_video.png", draw_ic_sys_video),
    ("ic_pdf.png", draw_ic_pdf),
    ("ic_weather.png", draw_ic_weather),
    ("ic_radio.png", draw_ic_radio),
    ("ic_recorder.png", draw_ic_sys_recorder),
    ("ic_arcade.png", draw_ic_arcade),
    ("ic_shield.png", draw_ic_shield),
    ("ic_boost.png", draw_ic_boost),
    ("ic_display.png", draw_ic_display),
    ("ic_split.png", draw_ic_split),
    ("ic_splitview.png", draw_ic_split),
    ("ic_dictionary.png", draw_ic_dictionary),
    ("ic_wiki.png", draw_ic_dictionary),
]


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
    "0": ["01110", "10011", "10101", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}


def draw_banner():
    w, h = 960, 240
    px = bytearray(w * h * 4)
    for y in range(h):
        ty = y / (h - 1.0)
        for x in range(w):
            tx = x / (w - 1.0)
            i = (y * w + x) * 4
            px[i] = int(9 + 18 * tx + 8 * ty)
            px[i + 1] = int(16 + 34 * (1.0 - ty) + 22 * tx)
            px[i + 2] = int(30 + 68 * tx)
            px[i + 3] = 255

    em = Canvas(168)
    draw_ic_launcher(em)
    em_px = em.downsample()
    ox, oy = 36, 36
    for y in range(168):
        for x in range(168):
            si = (y * 168 + x) * 4
            sa = em_px[si + 3] / 255.0
            if sa <= 0:
                continue
            di = ((oy + y) * w + (ox + x)) * 4
            for ch in range(3):
                px[di + ch] = int(em_px[si + ch] * sa + px[di + ch] * (1.0 - sa))

    def stamp_text(text, start_x, start_y, scale, color):
        cx = start_x
        for ch in text.upper():
            glyph = FONT_5X7.get(ch, FONT_5X7[" "])
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == "1":
                        for sy in range(scale):
                            for sx in range(scale):
                                txp, typ = cx + gx * scale + sx, start_y + gy * scale + sy
                                if 0 <= txp < w and 0 <= typ < h:
                                    di = (typ * w + txp) * 4
                                    px[di], px[di + 1], px[di + 2], px[di + 3] = color
            cx += 6 * scale

    stamp_text("KOBO ARC 7 FREEDOM", 232, 54, 6, (248, 250, 252, 255))
    stamp_text("VK BOOKS + PDF SCAN + WEATHER + RADIO + SHIELD V2.2", 234, 118, 3, (56, 189, 248, 255))
    stamp_text("ZRAM BOOST + 2ND SCREEN + SPLIT VIEW + ARCADE + VLC", 234, 156, 2, (167, 243, 208, 255))

    write_rgba_png(os.path.join(ROOT, "releases", "logo-banner.png"), w, h, px)


def main():
    densities = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144)]
    for folder, size in densities:
        for fname, fn in ALL_ICONS:
            c = Canvas(size)
            fn(c)
            rgba = c.downsample()
            write_rgba_png(os.path.join(ROOT, f"app/src/main/res/drawable-{folder}/{fname}"), size, size, rgba)
            write_rgba_png(os.path.join(ROOT, f"launcher-android4/res/drawable-{folder}/{fname}"), size, size, rgba)
            if fname == "ic_apk_installer.png":
                write_rgba_png(os.path.join(ROOT, f"upgrader/src/main/res/drawable-{folder}/ic_launcher.png"), size, size, rgba)
            if fname == "ic_aurora.png":
                write_rgba_png(os.path.join(ROOT, f"aurora-store/res/drawable-{folder}/ic_launcher.png"), size, size, rgba)

    hdr = Canvas(96)
    draw_ic_launcher(hdr)
    write_rgba_png(os.path.join(ROOT, "app/src/main/res/drawable-xhdpi/arc7_logo_header.png"), 96, 96, hdr.downsample())

    draw_banner()
    print(f"Generated {len(ALL_ICONS)} custom squircle icons across 4 densities for App, Launcher, Aurora & Upgrader + releases/logo-banner.png")


if __name__ == "__main__":
    main()
