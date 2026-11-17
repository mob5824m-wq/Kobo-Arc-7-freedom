#!/usr/bin/env python3
"""
Generates one **standalone, separately installable** Android app per built-in default app
(Browser, Clock, Calculator, Notes, Files, PDF Reader, Weather, …) and keeps the
`Arc7 Freedom` suite in sync so no launcher icon ever appears twice.

Until `v2.5.3` every built-in app only existed as an `<activity>` inside the `Arc7 Freedom`
suite (`ca.arc7freedom.app`), so the tablet only had ONE entry in *Settings → Apps*.  This
script emits `split-apps/<key>/AndroidManifest.xml` for every app, giving each one:

  * its own package id (`ca.arc7freedom.<key>`),
  * its own launcher icon + label (`Browser`, `Clock`, `Calculator`, …),
  * its own task stack and data directory,
  * its own row in *Settings → Apps* (installable / updatable / uninstallable on its own).

The Java classes stay shared (`ca.arc7freedom.app.*`) — exactly like the `YouTube` /
`YouTube Music` apps — but shared source does not mean a shared Android UID or network
process. Each manifest declares only its own entry point and explicitly owned support
components. Cross-app navigation launches the owning APK, never a bundled suite copy.

The suite manifest gets a generated `<activity-alias>` block (between the `SPLIT-APPS`
markers) that keeps the old launcher entries alive **only while the standalone app is not
installed** — these are now forwarding/installation-help shortcuts, not copies of the apps.
`SplitAppIconSync` flips them off once the real app is installed, preventing duplicate icons.

Usage: python3 scripts/gen-split-apps.py [--check]
"""
from __future__ import annotations

import copy
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SUITE_MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
JAVA_DIR = os.path.join(ROOT, "app", "src", "main", "java", "ca", "arc7freedom", "app")
OUT_DIR = os.path.join(ROOT, "split-apps")

ANDROID_NS = "http://schemas.android.com/apk/res/android"
NS = "{%s}" % ANDROID_NS

# key -> (published file name, one-line description for the Wi-Fi portal)
APP_META = {
    "browser": ("Browser.apk", "Web browser with TLS 1.3 + 118 bundled 2026 root CAs"),
    "ebookreader": ("Books.apk", "E-book reader (EPUB / CBZ) with 4-store search"),
    "audioplayer": ("Music.apk", "Offline music player with Now Playing notification"),
    "clock": ("Clock.apk", "Alarms on any days you pick, timer, stopwatch, standby clock"),
    "wallpaperpicker": ("Wallpapers.apk", "Wallpaper picker with 40+ built-in skins"),
    "appmanager": ("App-Manager.apk", "Install / uninstall / back up every app"),
    "terminal": ("Terminal.apk", "Root shell terminal with python3 + apt"),
    "calendar": ("Calendar.apk", "Month view calendar with events + reminders"),
    "photos": ("Photos.apk", "Gallery with pinch-zoom and slideshow"),
    "camera": ("Camera.apk", "Front camera photo + video capture"),
    "calculator": ("Calculator.apk", "Scientific calculator with history"),
    "notes": ("Notes.apk", "Notes / notepad with autosave"),
    "filemanager": ("Files.apk", "Two-pane file manager (copy / move / rename / zip)"),
    "wiredinternet": ("USB-Internet.apk", "Share your PC internet to the tablet over USB"),
    "bluetoothinternet": ("Bluetooth-Internet.apk", "Share Wi-Fi over Bluetooth PAN, or receive tethered internet"),
    "packageinstaller": ("Package-Installer.apk", "APK installer with signature checks"),
    "pdfviewer": ("PDF-Reader.apk", "PDF reader with page jump + night mode"),
    "weather": ("Weather.apk", "48-hour + 14-day weather, saved cities, air quality, alerts, radar & widget"),
    "radiorss": ("Radio-Podcasts.apk", "Internet radio + podcast/RSS player"),
    "recorder": ("Voice-Recorder.apk", "Voice recorder with playback + share"),
    "arcade": ("Games.apk", "Built-in arcade games"),
    "shield": ("Ad-Blocker.apk", "System-wide ad / tracker blocker"),
    "boost": ("System-Boost.apk", "RAM + cache cleaner and storage expander"),
    "secondscreen": ("Second-Screen.apk", "Use the tablet as a PC second screen"),
    "splitview": ("Split-View.apk", "Two apps side by side"),
    "dictionarywiki": ("Wikipedia.apk", "Wikipedia + dictionary reader"),
    "streamdeck": ("Stream-Deck.apk", "PC Stream Deck remote (OBS control)"),
    "vpn": ("Wi-Fi-VPN.apk", "Wi-Fi fix, official Tailscale Google sign-in, VPN manager"),
}

# Network engines and permissions belong to the APK making the request, never the suite.
NETWORK_APPS = {
    "browser", "ebookreader", "audioplayer", "clock", "calendar", "appmanager", "terminal",
    "filemanager", "wiredinternet", "packageinstaller", "weather", "radiorss", "shield",
    "secondscreen", "splitview", "dictionarywiki", "streamdeck", "vpn",
}

# Explicit permission allowlists; e.g. Calculator/Notes cannot open Internet sockets or a mic.
APP_PERMISSIONS = {key: {"READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"} for key in APP_META}
for key in NETWORK_APPS:
    APP_PERMISSIONS[key].update(("INTERNET", "ACCESS_NETWORK_STATE"))
for key in ("browser", "audioplayer", "radiorss", "secondscreen", "streamdeck", "filemanager",
            "wiredinternet", "vpn", "bluetoothinternet"):
    APP_PERMISSIONS[key].add("ACCESS_WIFI_STATE")
for key in ("audioplayer", "radiorss", "recorder", "clock", "calendar", "secondscreen",
            "streamdeck", "wiredinternet", "vpn", "ebookreader"):
    APP_PERMISSIONS[key].add("WAKE_LOCK")
for key in ("wiredinternet", "vpn", "bluetoothinternet"):
    APP_PERMISSIONS[key].add("CHANGE_NETWORK_STATE")
for key in ("wiredinternet", "vpn"):
    APP_PERMISSIONS[key].add("CHANGE_WIFI_STATE")
for key in ("filemanager", "streamdeck", "secondscreen"):
    APP_PERMISSIONS[key].add("CHANGE_WIFI_MULTICAST_STATE")
for key in ("clock", "calendar", "arcade"):
    APP_PERMISSIONS[key].add("VIBRATE")
for key in ("clock", "calendar"):
    APP_PERMISSIONS[key].add("RECEIVE_BOOT_COMPLETED")
for key in ("wallpaperpicker", "photos"):
    APP_PERMISSIONS[key].update(("SET_WALLPAPER", "SET_WALLPAPER_HINTS"))
APP_PERMISSIONS["camera"].update(("CAMERA", "RECORD_AUDIO", "WAKE_LOCK"))
APP_PERMISSIONS["recorder"].add("RECORD_AUDIO")
APP_PERMISSIONS["clock"].add("SET_TIME_ZONE")
APP_PERMISSIONS["weather"].update(("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK"))
APP_PERMISSIONS["bluetoothinternet"].update(("BLUETOOTH", "BLUETOOTH_ADMIN", "ACCESS_NETWORK_STATE"))

# Only private support components of this app. Cross-app activities are launched via
# SplitAppRouter, not copied into the caller's manifest/process/data directory.
APP_EXTRAS = {
    "vpn": (("service", "Arc7VpnService"),),
    "wiredinternet": (("service", "Arc7ChromebookUsbService"),),
    "audioplayer": (("service", "Arc7AudioService"), ("receiver", "Arc7AudioWidgetProvider")),
    "radiorss": (("service", "Arc7AudioService"),),
    "recorder": (("service", "Arc7AudioService"),),
    "clock": (("activity", "Arc7AlarmAlertActivity"), ("activity", "Arc7DeskClockActivity"),
              ("receiver", "Arc7AlarmReceiver"), ("receiver", "Arc7ScheduleBootReceiver"),
              ("receiver", "Arc7ClockWidgetProvider"), ("service", "Arc7AudioService")),
    "calendar": (("activity", "Arc7AlarmAlertActivity"), ("receiver", "Arc7AlarmReceiver"),
                 ("receiver", "Arc7ScheduleBootReceiver"), ("receiver", "Arc7CalendarWidgetProvider")),
    "weather": (("receiver", "Arc7WeatherWidgetProvider"), ("receiver", "Arc7WeatherBootReceiver"),
                ("service", "Arc7WeatherService")),
}
for key in NETWORK_APPS:
    APP_EXTRAS[key] = APP_EXTRAS.get(key, ()) + (("service", "Arc7TlsGatewayService"),)

SUITE_HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Standalone `{label}` app (`{pkg}`) — generated by `scripts/gen-split-apps.py`.

  A REAL, separately installable Android application: its own package id, its own launcher
  icon/label, its own task stack, its own data directory and its own row in
  Settings -> Apps, so it can be installed, updated and uninstalled on its own.

  Java classes are shared with the `Arc7 Freedom` suite (`ca.arc7freedom.app.*`), exactly
  like the `YouTube` / `YouTube Music` apps. Each APK runs under its OWN Android UID,
  with its OWN network engine/ports and only its own permissions and support components.
  Do not edit by hand — re-run `python3 scripts/gen-split-apps.py`.
-->
"""


def attr(node, name: str, default: str = "") -> str:
    return node.get(NS + name, default)


def xml_escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;"))


def activity_key(class_name: str) -> str:
    base = class_name.rsplit(".", 1)[-1]
    base = re.sub(r"^Arc7", "", base)
    base = re.sub(r"Activity$", "", base)
    return base.lower()


def activity_label(class_name: str) -> str:
    """`Arc7WiredInternetActivity` -> `WiredInternet` (used for the suite alias name)."""
    base = class_name.rsplit(".", 1)[-1]
    return re.sub(r"Activity$", "", base)


def indent(lines, spaces: int):
    pad = " " * spaces
    return [pad + ln if ln.strip() else ln for ln in lines]


def render_filters(node) -> list[str]:
    out: list[str] = []
    for flt in node.findall("intent-filter"):
        out.append("<intent-filter>")
        for child in list(flt):
            attrs = " ".join(
                f'android:{k.replace(NS, "")}="{xml_escape(v)}"' for k, v in child.attrib.items()
            )
            out.append(f"    <{child.tag} {attrs} />")
        out.append("</intent-filter>")
    return out


def render_component(tag: str, src, *, name: str, override: dict | None = None,
                     keep_filters: bool) -> list[str]:
    attrs: list[tuple[str, str]] = []
    for key, value in src.attrib.items():
        key = key.replace(NS, "")
        if key == "name":
            value = name
        if override and key in override:
            value = override[key]
        attrs.append((key, value))
    if override:
        for key, value in override.items():
            if key not in dict(attrs):
                attrs.append((key, value))
    lines = [f"<{tag}"]
    lines += indent([f'android:{k}="{xml_escape(v)}"' for k, v in attrs], 4)
    body = render_filters(src) if keep_filters else []
    for child in list(src):
        if child.tag in ("intent-filter",):
            continue
        if child.tag == "meta-data":
            attributes = " ".join(f'android:{k.replace(NS, "")}="{xml_escape(v)}"'
                                  for k, v in child.attrib.items())
            body.append(f"<meta-data {attributes} />")
    if not body:
        lines[-1] += " />"
        return lines
    lines[-1] += ">"
    lines += indent(body, 4)
    lines.append(f"</{tag}>")
    return lines


def build_app(key: str, meta, version_code: str, version_name: str, target_activity,
              by_class: dict, services, receivers) -> str:
    pkg = f"ca.arc7freedom.{key}"
    label = attr(target_activity, "label") or key.title()
    icon = attr(target_activity, "icon") or "@drawable/ic_launcher"

    lines: list[str] = [SUITE_HEADER.format(label=xml_escape(label), pkg=pkg).rstrip()]
    lines.append(
        f'<manifest xmlns:android="{ANDROID_NS}"\n'
        f'    package="{pkg}"\n'
        f'    android:versionCode="{version_code}"\n'
        f'    android:versionName="{version_name}"\n'
        f'    android:installLocation="internalOnly">'
    )
    lines.append("")
    lines.append('    <uses-sdk android:minSdkVersion="15" android:targetSdkVersion="17" />')
    lines.append("")
    lines.append('    <uses-feature android:name="android.hardware.camera" android:required="false" />')
    lines.append('    <uses-feature android:name="android.hardware.microphone" android:required="false" />')
    lines.append("")
    if key == "bluetoothinternet":
        lines.append('    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />')
    if key == "weather":
        for feature in ("location", "location.gps", "location.network"):
            lines.append(f'    <uses-feature android:name="android.hardware.{feature}" android:required="false" />')
    for permission in sorted(APP_PERMISSIONS[key]):
        lines.append(f'    <uses-permission android:name="android.permission.{permission}" />')
    lines.append("")
    lines.append("    <application")
    lines.append('        android:name="ca.arc7freedom.app.Arc7Application"')
    lines.append(f'        android:taskAffinity="{pkg}.task.main"')
    lines.append(f'        android:icon="{icon}"')
    lines.append(f'        android:label="{xml_escape(label)}"')
    lines.append('        android:hardwareAccelerated="true"')
    lines.append('        android:largeHeap="true"')
    lines.append('        android:allowBackup="true"')
    lines.append('        android:theme="@android:style/Theme.Holo">')
    lines.append("")

    lines += indent(render_component(
        "activity", target_activity,
        name="ca.arc7freedom.app." + attr(target_activity, "name").lstrip("."),
        override={"taskAffinity": f"{pkg}.task.main", "exported": "true"},
        keep_filters=True,
    ), 8)
    lines.append("")

    for tag, comp_name in APP_EXTRAS.get(key, ()):
        src = None
        if tag == "service":
            candidates = services
        elif tag == "receiver":
            candidates = receivers
        elif tag == "activity":
            candidates = list(by_class.values())
        else:
            raise AssertionError(f"{key}: unsupported extra component type {tag!r}")
        for cand in candidates:
            if attr(cand, "name").lstrip(".") == comp_name:
                src = cand
                break
        assert src is not None, f"{key}: {tag} {comp_name} missing from the suite manifest"
        extra_override = {"exported": "false"}
        if tag == "activity":
            extra_override["taskAffinity"] = f"{pkg}.task.{comp_name.lower()}"
            if comp_name == "Arc7DeskClockActivity":
                extra_override["exported"] = "true"  # suite dock/widget shortcut -> Clock APK
        lines += indent(render_component(
            tag, src,
            name="ca.arc7freedom.app." + comp_name,
            override=extra_override,
            keep_filters=False if tag == "activity" else True,
        ), 8)
        lines.append("")

    while lines and not lines[-1].strip():
        lines.pop()
    lines.append("")
    lines.append("    </application>")
    lines.append("</manifest>")
    return "\n".join(lines) + "\n"


def patch_suite_manifest(alias_entries: list[tuple[str, str, str, str, str]]) -> None:
    """
    Moves every split app's launcher/VIEW intent filters from its `<activity>` to a generated
    `<activity-alias>` (so `SplitAppIconSync` can switch it off once the standalone app is
    installed) and rewrites the generated alias block between the SPLIT-APPS markers.
    """
    with open(SUITE_MANIFEST, "r", encoding="utf-8") as fp:
        text = fp.read()
    original = text

    block_lines = [
        "<!-- SPLIT-APPS:START (generated by scripts/gen-split-apps.py — do not edit) -->",
        "<!--",
        "  Each default app now ships as its own installable APK (`releases/apps/<Name>.apk`,",
        "  packages `ca.arc7freedom.<key>`).  These aliases keep the old suite launcher entry",
        "  AND its deep links as installation-help shortcuts while the app is not installed;",
        "  `SplitAppIconSync` disables them as soon as the real app is present, so the launcher",
        "  and the `Open with` chooser never show the same app twice. Aliases NEVER run suite copies.",
        "-->",
    ]

    for act_name, key, label, icon, filters in alias_entries:
        alias = activity_label(act_name)
        block_lines += [
            f'<activity-alias android:name=".launch.{alias}"',
            '    android:targetActivity=".SplitAppLaunchActivity"',
            '    android:enabled="true"',
            '    android:exported="true"',
            f'    android:icon="{icon}"',
            f'    android:label="{xml_escape(label)}">',
        ]
        block_lines += indent(filters, 4)
        block_lines.append("</activity-alias>")
    block_lines.append("<!-- SPLIT-APPS:END -->")

    if not alias_entries:
        raise SystemExit("Refusing to write an empty SPLIT-APPS block — no launcher activities found?")

    def _render(pad: str) -> str:
        return "\n".join(indent(block_lines, len(pad)) if pad else block_lines)

    marker_re = re.compile(r"([ \t]*)(<!-- SPLIT-APPS:START.*?<!-- SPLIT-APPS:END -->)", re.DOTALL)
    match = marker_re.search(text)
    if match:
        text = text[:match.start()] + _render(match.group(1)) + text[match.end():]
    else:
        idx = text.rindex("</application>")
        text = text[:idx] + "\n" + _render("        ") + "\n\n" + text[idx:]

    # Strip the now-duplicated intent filters off the suite activities themselves.
    for act_name, _key, _label, _icon, _filters in alias_entries:
        pat = re.compile(
            r'(<activity\b[^>]*android:name="' + re.escape(act_name) + r'"[^>]*>)(.*?)(</activity>)',
            re.DOTALL,
        )
        m = pat.search(text)
        assert m, f"suite manifest: activity {act_name} not found"
        body = re.sub(r"\n?\s*<intent-filter>.*?</intent-filter>", "", m.group(2), flags=re.DOTALL)
        text = text[:m.start()] + m.group(1) + body + m.group(3) + text[m.end():]

    if text != original:
        with open(SUITE_MANIFEST, "w", encoding="utf-8") as fp:
            fp.write(text)


def write_java_keys(entries: list[tuple[str, str, str, str]]) -> None:
    """Generates `SplitApps.java` so `SplitAppIconSync` knows every alias <-> package pair."""
    path = os.path.join(JAVA_DIR, "SplitApps.java")
    rows = "\n".join(
        f'            {{ ".launch.{activity_label(act)}", "ca.arc7freedom.{key}", "{label}", "ca.arc7freedom.app.{act.lstrip(".")}" }},'
        for act, key, label, _icon in entries
    )
    body = f'''package ca.arc7freedom.app;

/**
 * GENERATED by `scripts/gen-split-apps.py` — do not edit by hand.
 *
 * One row per built-in app that now also ships as its own installable APK
 * (`releases/apps/<Name>.apk`, package `ca.arc7freedom.<key>`):
 * {{ activity alias in the suite, standalone package, human readable label, activity class }}.
 */
final class SplitApps {{

    private SplitApps() {{}}

    static final String[][] ALL = {{
{rows}
    }};
}}
'''
    with open(path, "w", encoding="utf-8") as fp:
        fp.write(body)

    launcher_path = os.path.join(ROOT, "launcher-android4/src/main/java/ca/arc7freedom/launcher/BuiltinApps.java")
    launcher = """package ca.arc7freedom.launcher;

import android.content.ComponentName;
import android.content.Intent;

/** GENERATED by scripts/gen-split-apps.py. Never route built-in shortcuts through the suite. */
final class BuiltinApps {
    private BuiltinApps() {}
    private static final String[][] ALL = {
""" + rows + """
    };

    /** Resolve management actions as well as launches, including old pinned suite aliases. */
    static String managementPackage(String pkg, String cls) {
        if (pkg == null) return null;
        if (cls != null && cls.startsWith(".")) cls = pkg + cls;
        if ("ca.arc7freedom.app".equals(pkg)
                && "ca.arc7freedom.app.Arc7DeskClockActivity".equals(cls)) return "ca.arc7freedom.clock";
        for (String[] row : ALL) {
            if (("ca.arc7freedom.app".equals(pkg) || row[1].equals(pkg))
                    && (row[3].equals(cls) || ("ca.arc7freedom.app" + row[0]).equals(cls))) return row[1];
        }
        return pkg;
    }

    static Intent route(Intent original) {
        if (original == null || original.getComponent() == null) return original;
        String pkg = original.getComponent().getPackageName();
        String cls = original.getComponent().getClassName();
        if (cls.startsWith(".")) cls = pkg + cls;
        if ("ca.arc7freedom.app".equals(pkg)
                && "ca.arc7freedom.app.Arc7DeskClockActivity".equals(cls)) {
            return new Intent(original).setComponent(new ComponentName("ca.arc7freedom.clock", cls))
                    .setPackage("ca.arc7freedom.clock");
        }
        for (String[] row : ALL) {
            if (("ca.arc7freedom.app".equals(pkg) || row[1].equals(pkg))
                    && (row[3].equals(cls) || ("ca.arc7freedom.app" + row[0]).equals(cls))) {
                return new Intent(original).setComponent(new ComponentName(row[1], row[3])).setPackage(row[1]);
            }
        }
        return original;
    }
}
"""
    with open(launcher_path, "w", encoding="utf-8") as fp:
        fp.write(launcher)


def main() -> None:
    tree = ET.parse(SUITE_MANIFEST)
    manifest = tree.getroot()
    version_code = manifest.get(NS + "versionCode", "254")
    version_name = manifest.get(NS + "versionName", "2.5.4")
    android = manifest.find("application")
    assert android is not None

    all_activities = android.findall("activity")
    by_class = {attr(a, "name").lstrip("."): a for a in all_activities}
    services = android.findall("service")
    receivers = android.findall("receiver")

    # Sources of truth for "which apps to split":
    #   * activities that still carry a LAUNCHER filter (first run), and
    #   * `<activity-alias>` targets already generated by a previous run (idempotency —
    #     after the first run the activities themselves no longer have any filters).
    alias_by_target = {}
    for alias_el in android.findall("activity-alias"):
        target = attr(alias_el, "targetActivity")
        if target == ".SplitAppLaunchActivity":
            target = "." + attr(alias_el, "name").rsplit(".", 1)[-1] + "Activity"
        if target and not target.startswith(".launch"):
            alias_by_target[target] = alias_el

    launchers = []
    for act in all_activities:
        name = attr(act, "name")
        cats = [attr(c, "name") for f in act.findall("intent-filter")
                for c in f.findall("category")]
        if "android.intent.category.LAUNCHER" in cats or name in alias_by_target:
            launchers.append(act)

    def merged_source(act):
        """Activity attributes + whichever intent-filters currently carry its launcher/VIEW links."""
        src = ET.Element("activity", dict(act.attrib))
        filt_src = alias_by_target.get(attr(act, "name"), act)
        for flt in filt_src.findall("intent-filter"):
            src.append(copy.deepcopy(flt))
        for meta in act.findall("meta-data"):
            src.append(copy.deepcopy(meta))
        return src

    generated, alias_entries, java_entries = [], [], []
    for act in launchers:
        act_name = attr(act, "name")
        if act_name.lstrip(".") in ("MainActivity", "YouTubeActivity", "YouTubeMusicActivity"):
            continue
        key = activity_key(act_name)
        if key not in APP_META:
            raise SystemExit(f"Add {key!r} ({attr(act, 'label')}) to APP_META in scripts/gen-split-apps.py")
        src = merged_source(act)
        label = attr(src, "label") or key.title()
        icon = attr(src, "icon") or "@drawable/ic_launcher"

        text = build_app(key, APP_META[key], version_code, version_name,
                         src, by_class, services, receivers)
        out_dir = os.path.join(OUT_DIR, key)
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "AndroidManifest.xml"), "w", encoding="utf-8") as fp:
            fp.write(text)

        alias_entries.append((act_name, key, label, icon, render_filters(src)))
        java_entries.append((act_name, key, label, icon))
        generated.append((key, APP_META[key][0], label, APP_META[key][1]))

    for act in all_activities:
        act_name = attr(act, "name")
        if act_name not in (".YouTubeActivity", ".YouTubeMusicActivity"):
            continue
        src = merged_source(act)
        key = "youtube" if act_name == ".YouTubeActivity" else "ytmusic"
        label, icon = attr(src, "label"), attr(src, "icon")
        alias_entries.append((act_name, key, label, icon, render_filters(src)))
        java_entries.append((act_name, key, label, icon))

    patch_suite_manifest(alias_entries)
    write_java_keys(java_entries)

    index_path = os.path.join(OUT_DIR, "apps.tsv")
    with open(index_path, "w", encoding="utf-8") as fp:
        for key, fname, label, desc in sorted(generated, key=lambda row: row[1].lower()):
            fp.write(f"{key}\t{fname}\t{label}\t{desc}\n")

    print(f"Generated {len(generated)} standalone app manifests in split-apps/ (+ apps.tsv)")
    for key, fname, label, _desc in sorted(generated, key=lambda row: row[1].lower()):
        print(f"  {fname:26s} ca.arc7freedom.{key:18s} {label}")

    if "--check" in sys.argv:
        missing = [row[1] for row in generated
                   if not os.path.isfile(os.path.join(ROOT, "releases", "apps", row[1]))]
        if missing:
            raise SystemExit(f"Missing built split APKs in releases/apps/: {missing}")


if __name__ == "__main__":
    main()
