#!/usr/bin/env node
/*
 * Executes the *real* injected e-reader paging runtime
 * (`Arc7EbookReaderActivity.READER_RUNTIME_JS`) inside a headless DOM stub and proves that
 *
 *   1. `arc7NextPage()` advances exactly one screen and reports `arc7ctl:pos/<ratio>/<page>/<pages>`
 *   2. at the end of the chapter it emits `arc7ctl:atend` (Java then opens the next chapter)
 *   3. `arc7PrevPage()` at the top emits `arc7ctl:atstart` (Java then opens the previous chapter)
 *   4. `arc7GotoRatio(r)` restores an exact reading position (A+/A-, theme switch, reopen book)
 *   5. the tap zones map left 30% -> `prev`, middle -> `chrome`, right 30% -> `next`,
 *      and taps on links are left alone
 *
 * Usage: node scripts/test-reader-paging.js [path-to-Arc7EbookReaderActivity.java]
 */
"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const javaPath = process.argv[2] || path.join(
    __dirname, "..", "app", "src", "main", "java", "ca", "arc7freedom", "app",
    "Arc7EbookReaderActivity.java");

const javaSrc = fs.readFileSync(javaPath, "utf8");
const startIdx = javaSrc.indexOf("READER_RUNTIME_JS =");
if (startIdx < 0) {
    console.error("FAIL: READER_RUNTIME_JS not found in " + javaPath);
    process.exit(1);
}
// The constant is a concatenation of Java string literals terminated by `";` on its own line.
const tail = javaSrc.slice(startIdx);
const endMatch = tail.match(/;\s*\n/);
const literalBlock = tail.slice(tail.indexOf("=") + 1, endMatch ? endMatch.index : tail.length);

const parts = [];
const litRe = /"((?:[^"\\]|\\.)*)"/g;
let m;
while ((m = litRe.exec(literalBlock)) !== null) {
    parts.push(m[1].replace(/\\(u[0-9a-fA-F]{4}|.)/g, (all, esc) => {
        if (esc[0] === "u") return String.fromCharCode(parseInt(esc.slice(1), 16));
        switch (esc) {
            case "n": return "\n";
            case "t": return "\t";
            case "r": return "\r";
            case "b": return "\b";
            case "f": return "\f";
            case "0": return "\0";
            default: return esc;
        }
    }));
}
const js = parts.join("");
if (js.length < 200) {
    console.error("FAIL: extracted runtime is suspiciously short (" + js.length + " bytes)");
    process.exit(1);
}

/* ------------------------------------------------------------------ DOM stub */
function makePage(docHeight, viewportHeight) {
    const nav = [];
    const listeners = { click: [], scroll: [] };
    const state = { y: 0, docHeight: docHeight, vh: viewportHeight, tiles: 0, w: 600 };

    const documentStub = {
        documentElement: {
            clientHeight: viewportHeight,
            clientWidth: state.w,
            scrollHeight: docHeight,
            scrollTop: 0,
            appendChild() { state.tiles++; },
        },
        body: { scrollHeight: docHeight, scrollTop: 0 },
        addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
        createElement() {
            return { style: {}, setAttribute() {}, appendChild() {} };
        },
    };

    const windowStub = {
        innerHeight: viewportHeight,
        innerWidth: state.w,
        pageYOffset: 0,
        document: documentStub,
        scrollTo(x, y) {
            const max = Math.max(0, state.docHeight - state.vh);
            state.y = Math.max(0, Math.min(max, Math.round(y)));
            windowStub.pageYOffset = state.y;
            documentStub.documentElement.scrollTop = state.y;
            documentStub.body.scrollTop = state.y;
        },
        addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
        location: {
            set href(value) { nav.push(value); },
            get href() { return "file:///book/chapter.xhtml"; },
        },
    };

    const sandbox = {
        window: windowStub,
        document: documentStub,
        location: windowStub.location,
        setTimeout(fn) { if (typeof fn === "function") fn(); return 1; },
        clearTimeout() {},
        Math: Math,
        console: console,
    };
    vm.createContext(sandbox);
    vm.runInContext(js, sandbox, { filename: "arc7-reader-runtime.js" });

    return {
        window: windowStub,
        state,
        nav,
        listeners,
        page: () => state.y,
        maxScroll: () => Math.max(0, state.docHeight - state.vh),
        tap(x, nodeName) {
            const target = { nodeName: nodeName || "DIV", parentNode: null };
            listeners.click.forEach((fn) => fn({ clientX: x, target: target }));
        },
        swipe(fromX, toX, fromY, toY) {
            const start = { clientX: fromX, clientY: fromY || 400 };
            const end = { clientX: toX, clientY: toY || 400 };
            (listeners.touchstart || []).forEach((fn) => fn({ touches: [start] }));
            (listeners.touchend || []).forEach((fn) => fn({ changedTouches: [end] }));
        },
        scroll() {
            listeners.scroll.forEach((fn) => fn({}));
        },
        lastSignal() { return nav.length ? nav[nav.length - 1] : ""; },
        signals() { return nav.slice(); },
    };
}

/* ------------------------------------------------------------------ assertions */
let failures = 0;
function check(name, cond, detail) {
    if (cond) {
        console.log("  [PASS] " + name);
    } else {
        failures++;
        console.log("  [FAIL] " + name + (detail ? "  -> " + detail : ""));
    }
}

console.log("Testing injected e-reader paging runtime (" + js.length + " bytes of JS)");

/* 1. next page = one screen, with a pos/<ratio>/<page>/<pages> report */
const p1 = makePage(6000, 800);
p1.window.arc7NextPage();
check("arc7NextPage() advances one screen", p1.page() === 720, "y=" + p1.page());
check("arc7NextPage() reports pos shape",
    /^arc7ctl:pos\/\d+\/\d+\/\d+$/.test(p1.lastSignal()), p1.lastSignal());

/* 2. walking off the bottom emits `atend` */
const p2 = makePage(2000, 800);
for (let i = 0; i < 20 && !/atend/.test(p2.lastSignal()); i++) p2.window.arc7NextPage();
check("end of chapter emits arc7ctl:atend", /^arc7ctl:atend$/.test(p2.lastSignal()), p2.lastSignal());
check("last page stops at the bottom", p2.page() === p2.maxScroll(), "y=" + p2.page());

/* 3. going back at the top emits `atstart` */
const p3 = makePage(3000, 700);
p3.window.arc7PrevPage();
check("top of chapter emits arc7ctl:atstart", /^arc7ctl:atstart$/.test(p3.lastSignal()), p3.lastSignal());

/* 3b. one page down then back up returns to the top */
const p3b = makePage(3000, 700);
p3b.window.arc7NextPage();
const after = p3b.page();
p3b.window.arc7PrevPage();
check("prev page undoes next page", after > 0 && p3b.page() === 0, "y=" + p3b.page());

/* 4. restore an exact ratio (font size / theme change, reopening a book) */
const p4 = makePage(4000, 800);
p4.window.arc7GotoRatio(0.5);
check("arc7GotoRatio(0.5) restores the middle", p4.page() === Math.round(0.5 * p4.maxScroll()),
    "y=" + p4.page() + " expected " + Math.round(0.5 * p4.maxScroll()));
p4.window.arc7GotoRatio(1);
check("arc7GotoRatio(1) jumps to the last page", p4.page() === p4.maxScroll(), "y=" + p4.page());

/* 5. tap zones */
const p5 = makePage(5000, 900);
p5.nav.length = 0;
p5.tap(30);
check("tap on the left 30% turns back", /^arc7ctl:prev$/.test(p5.lastSignal()), p5.lastSignal());
p5.nav.length = 0;
p5.tap(570);
check("tap on the right 30% turns forward", /^arc7ctl:next$/.test(p5.lastSignal()), p5.lastSignal());
p5.nav.length = 0;
p5.tap(300);
check("tap in the middle toggles the toolbar", /^arc7ctl:chrome$/.test(p5.lastSignal()), p5.lastSignal());
p5.nav.length = 0;
p5.tap(570, "A");
check("taps on a link are left to the link", p5.nav.length === 0, p5.nav.join(","));

/* 5b. horizontal swipes turn pages too */
const p5b = makePage(5000, 900);
p5b.nav.length = 0;
p5b.swipe(560, 120);
check("swipe left (finger moves left) turns forward", /^arc7ctl:next$/.test(p5b.lastSignal()), p5b.lastSignal());
p5b.nav.length = 0;
p5b.swipe(80, 540);
check("swipe right turns back", /^arc7ctl:prev$/.test(p5b.lastSignal()), p5b.lastSignal());
p5b.nav.length = 0;
p5b.swipe(300, 290, 300, 520);
check("vertical swipe does not turn the page", p5b.nav.length === 0, p5b.nav.join(","));
/* the click that old WebKit can still fire right after a swipe must not double-turn */
p5b.nav.length = 0;
p5b.swipe(560, 120);
p5b.tap(570);
check("the click following a swipe is ignored", p5b.nav.length === 1, p5b.nav.join(","));

/* 6. scroll position is reported back for the progress label */
const p6 = makePage(2600, 800);
p6.nav.length = 0;
p6.window.scrollTo(0, 900);
p6.window.arc7Refresh();
check("arc7Refresh() reports the scroll position", /^arc7ctl:pos\/\d+\/\d+\/\d+$/.test(p6.lastSignal()),
    p6.lastSignal());

if (failures > 0) {
    console.error("\n" + failures + " e-reader paging check(s) FAILED");
    process.exit(1);
}
console.log("\nALL E-READER PAGING CHECKS PASSED");
