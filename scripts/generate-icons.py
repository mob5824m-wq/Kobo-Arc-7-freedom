#!/usr/bin/env python3
"""
Generates crisp anti-aliased multi-density Android launcher icons (mdpi 48px, hdpi 72px,
xhdpi 96px, xxhdpi 144px) for both:
  1. KoboArc7Freedom (`app/src/main/res/drawable-*/ic_launcher.png`)
  2. VanillaAndroid4Home (`launcher-android4/res/drawable-*/ic_launcher_home.png`)
Pure Python + stdlib zlib/struct PNG encoder (zero external image library dependency!).
"""
import math
import os
import struct
import zlib


def write_png(path: str, size: int, pixel_fn) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # 2x2 supersampling anti-aliasing for razor-sharp edges
    rows = []
    sub = (-0.25, 0.25)
    for y in range(size):
        row = bytearray([0])  # filter type 0
        for x in range(size):
            r_acc = g_acc = b_acc = a_acc = 0.0
            for sy in sub:
                for sx in sub:
                    nx = (x + 0.5 + sx) / size
                    ny = (y + 0.5 + sy) / size
                    r, g, b, a = pixel_fn(nx, ny)
                    r_acc += r
                    g_acc += g
                    b_acc += b
                    a_acc += a
            row.extend((
                max(0, min(255, int(round(r_acc * 0.25)))),
                max(0, min(255, int(round(g_acc * 0.25)))),
                max(0, min(255, int(round(b_acc * 0.25)))),
                max(0, min(255, int(round(a_acc * 0.25)))),
            ))
        rows.append(bytes(row))

    raw = b"".join(rows)
    compressed = zlib.compress(raw, 9)

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", compressed))
        f.write(chunk(b"IEND", b""))


def sd_round_rect(x: float, y: float, cx: float, cy: float, hw: float, hh: float, rad: float) -> float:
    dx = abs(x - cx) - hw + rad
    dy = abs(y - cy) - hh + rad
    outside = math.hypot(max(dx, 0.0), max(dy, 0.0))
    inside = min(max(dx, dy), 0.0)
    return outside + inside - rad


def sd_segment(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    denom = vx * vx + vy * vy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / denom))
    dx = px - (ax + t * vx)
    dy = py - (ay + t * vy)
    return math.hypot(dx, dy)


def freedom_icon_pixel(x: float, y: float):
    # Rounded dark-slate hardware badge with subtle cyan rim + unlocked padlock + # arrow
    d_bg = sd_round_rect(x, y, 0.5, 0.5, 0.43, 0.43, 0.16)
    if d_bg > 0.0:
        return (0, 0, 0, 0)

    # Subtle vertical slate gradient (#16202C -> #0E151E)
    t = y
    r = 24.0 * (1.0 - t) + 13.0 * t
    g = 35.0 * (1.0 - t) + 21.0 * t
    b = 49.0 * (1.0 - t) + 31.0 * t

    # Subtle inner border highlight in Holo Blue (#33B5E5)
    if d_bg > -0.022:
        r, g, b = 45.0, 158.0, 204.0

    # Open shackle (raised & unlocked right side!)
    # Arc center (0.50, 0.40), radius 0.135
    ang = math.atan2(y - 0.37, x - 0.50)
    dist_ring = abs(math.hypot(x - 0.50, y - 0.37) - 0.135)
    in_arc = (y <= 0.38) and (dist_ring <= 0.036)
    in_left_leg = (abs(x - 0.365) <= 0.036) and (0.37 <= y <= 0.50)
    # Right shackle ends higher (0.37..0.41) -> visibly UNLOCKED!
    in_right_tip = (abs(x - 0.635) <= 0.036) and (0.37 <= y <= 0.405)

    if in_arc or in_left_leg or in_right_tip:
        r, g, b = 51.0, 181.0, 229.0  # Classic Jelly Bean Holo Blue #33B5E5

    # Lock body (#F1F5F9 clean silver-white rounded rectangle)
    d_body = sd_round_rect(x, y, 0.50, 0.61, 0.20, 0.15, 0.045)
    if d_body <= 0.0:
        r, g, b = 238.0, 244.0, 250.0
        # Keyhole / root prompt '>' glyph inside lock body in deep slate
        if sd_segment(x, y, 0.45, 0.565, 0.52, 0.61) <= 0.022 or sd_segment(x, y, 0.52, 0.61, 0.45, 0.655) <= 0.022:
            r, g, b = 18.0, 26.0, 36.0
        elif sd_segment(x, y, 0.54, 0.655, 0.585, 0.655) <= 0.020:
            r, g, b = 51.0, 181.0, 229.0

    return (r, g, b, 255.0)


def android4_home_icon_pixel(x: float, y: float):
    # Authentic Holo Blue (#33B5E5 -> #0099CC) Android 4 Home icon
    d_bg = sd_round_rect(x, y, 0.5, 0.5, 0.43, 0.43, 0.16)
    if d_bg > 0.0:
        return (0, 0, 0, 0)

    r = 51.0 * (1.0 - y) + 0.0 * y
    g = 181.0 * (1.0 - y) + 140.0 * y
    b = 229.0 * (1.0 - y) + 196.0 * y

    # Crisp white AOSP house silhouette
    roof = sd_segment(x, y, 0.24, 0.50, 0.50, 0.27) <= 0.038 or sd_segment(x, y, 0.50, 0.27, 0.76, 0.50) <= 0.038
    walls = (0.33 <= x <= 0.67) and (0.47 <= y <= 0.73)
    door = (0.445 <= x <= 0.555) and (0.57 <= y <= 0.74)
    if (roof or walls) and not door:
        r, g, b = 255.0, 255.0, 255.0

    return (r, g, b, 255.0)


def main() -> None:
    densities = {
        "drawable-mdpi": 48,
        "drawable-hdpi": 72,
        "drawable-xhdpi": 96,
        "drawable-xxhdpi": 144,
    }
    for folder, px in densities.items():
        write_png(f"app/src/main/res/{folder}/ic_launcher.png", px, freedom_icon_pixel)
        write_png(f"launcher-android4/res/{folder}/ic_launcher_home.png", px, android4_home_icon_pixel)
    print("Generated multi-density PNG icons successfully.")


if __name__ == "__main__":
    main()
