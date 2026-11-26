"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const promo = require("../../docs/promo.js");

const PROMO = promo.PROMO;
const docs = path.join(__dirname, "../../docs");
const html = fs.readFileSync(path.join(docs, "index.html"), "utf8");
const css = fs.readFileSync(path.join(docs, "style.css"), "utf8");
const source = fs.readFileSync(path.join(docs, "promo.js"), "utf8");

function atLosAngeles(isoLocal) {
  return new Date(promo.zonedTimeToUtc(isoLocal, PROMO.timeZone));
}

function memoryStorage(initial) {
  const data = new Map(initial ? Object.entries(initial) : []);
  return {
    getItem(key) {
      return data.has(key) ? data.get(key) : null;
    },
    setItem(key, value) {
      data.set(key, String(value));
    },
  };
}

function fakeDocument() {
  const listeners = {};
  const button = { listeners: {}, addEventListener(type, fn) { this.listeners[type] = fn; } };
  const banner = {
    hidden: true,
    listeners: {},
    addEventListener(type, fn) { this.listeners[type] = fn; },
    querySelector(selector) {
      return selector === "[data-promo-dismiss]" ? button : null;
    },
  };
  const section = { hidden: true };
  const names = new Set();
  const brand = {
    focused: false,
    focus() { this.focused = true; },
  };
  return {
    readyState: "complete",
    listeners,
    button,
    banner,
    section,
    brand,
    documentElement: {
      classList: {
        add(name) { names.add(name); },
        remove(name) { names.delete(name); },
        contains(name) { return names.has(name); },
      },
    },
    addEventListener(type, fn) { listeners[type] = fn; },
    querySelector(selector) {
      if (selector === ".promo-banner") return banner;
      if (selector === ".promo-section") return section;
      if (selector === ".brand") return brand;
      return null;
    },
  };
}

function sliceBetween(sourceText, startMarker, endMarker) {
  const start = sourceText.indexOf(startMarker);
  const end = sourceText.indexOf(endMarker, start + startMarker.length);
  assert.ok(start !== -1 && end !== -1, startMarker + " … " + endMarker);
  return sourceText.slice(start, end);
}

test("the window is Pacific time, inclusive through 2026-12-01 23:59", () => {
  assert.equal(
    promo.zonedTimeToUtc(PROMO.start, PROMO.timeZone),
    Date.parse("2026-11-27T08:00:00.000Z")
  );
  assert.equal(
    promo.zonedTimeToUtc(PROMO.endExclusive, PROMO.timeZone),
    Date.parse("2026-12-02T08:00:00.000Z")
  );
  // Guards the offset helper against a PST/PDT mix-up if the dates move.
  assert.equal(
    promo.zonedTimeToUtc("2026-07-01T00:00:00", "America/Los_Angeles"),
    Date.parse("2026-07-01T07:00:00.000Z")
  );
});

test("hidden before the window and shown from the first instant", () => {
  const before = promo.decide({
    now: new Date(promo.zonedTimeToUtc(PROMO.start, PROMO.timeZone) - 1),
    search: "",
    dismissed: false,
  });
  assert.equal(before.showBanner, false);
  assert.equal(before.showSection, false);

  const start = promo.decide({
    now: atLosAngeles(PROMO.start),
    search: "",
    dismissed: false,
  });
  assert.equal(start.inWindow, true);
  assert.equal(start.showBanner, true);
  assert.equal(start.showSection, true);
});

test("stays up through the last millisecond of 2026-12-01 and then stops", () => {
  const end = promo.zonedTimeToUtc(PROMO.endExclusive, PROMO.timeZone);
  const last = promo.decide({ now: new Date(end - 1), search: "", dismissed: false });
  const next = promo.decide({ now: new Date(end), search: "", dismissed: false });
  assert.equal(last.showBanner, true);
  assert.equal(last.showSection, true);
  assert.equal(next.showBanner, false);
  assert.equal(next.showSection, false);
  assert.equal(atLosAngeles("2026-12-01T23:59:59").getTime() < end, true);
});

test("Cyber Monday and the days around it are inside the window", () => {
  for (const iso of ["2026-11-27T00:00:00", "2026-11-30T12:00:00", "2026-12-01T23:59:00"]) {
    const decision = promo.decide({ now: atLosAngeles(iso), search: "", dismissed: false });
    assert.equal(decision.inWindow, true, iso);
  }
  const outside = promo.decide({
    now: atLosAngeles("2026-11-26T23:59:59"),
    search: "",
    dismissed: false,
  });
  assert.equal(outside.inWindow, false);
});

test("preview shows the promo outside the window and ignores other values", () => {
  const preview = promo.decide({
    now: atLosAngeles("2026-01-15T12:00:00"),
    search: "?promo=bf2026",
    dismissed: false,
  });
  assert.equal(preview.preview, true);
  assert.equal(preview.inWindow, false);
  assert.equal(preview.showBanner, true);
  assert.equal(preview.showSection, true);

  const named = promo.decide({
    now: atLosAngeles("2026-01-15T12:00:00"),
    search: "utm=1&promo=bf2026",
    dismissed: false,
  });
  assert.equal(named.preview, true);

  for (const search of ["?promo=other", "?promo=bf2026x", "?Promo=bf2026", "?promo=", ""]) {
    const decision = promo.decide({
      now: atLosAngeles("2026-01-15T12:00:00"),
      search,
      dismissed: false,
    });
    assert.equal(decision.showBanner, false, search);
  }
});

test("dismissal hides the banner and keeps the section, unless previewing", () => {
  const dismissed = promo.decide({
    now: atLosAngeles("2026-11-28T15:00:00"),
    search: "",
    dismissed: true,
  });
  assert.equal(dismissed.showBanner, false);
  assert.equal(dismissed.showSection, true);

  const preview = promo.decide({
    now: atLosAngeles("2026-01-15T12:00:00"),
    search: "?promo=bf2026",
    dismissed: true,
  });
  assert.equal(preview.showBanner, true);
  assert.equal(preview.showSection, true);
});

test("an invalid clock shows nothing", () => {
  const decision = promo.decide({ now: new Date(NaN), search: "?promo=bf2026", dismissed: false });
  // Preview is still evaluated, but a NaN clock bails out before that.
  assert.equal(decision.showBanner, false);
  assert.equal(decision.showSection, false);
});

test("dismissal is a localStorage flag and storage failures stay quiet", () => {
  const storage = memoryStorage();
  assert.equal(promo.readDismissed(storage), false);
  promo.writeDismissed(storage);
  assert.equal(storage.getItem(PROMO.storageKey), "1");
  assert.equal(promo.readDismissed(storage), true);
  assert.equal(promo.readDismissed(memoryStorage({ [PROMO.storageKey]: "0" })), false);

  const broken = {
    getItem() { throw new Error("blocked"); },
    setItem() { throw new Error("blocked"); },
  };
  assert.equal(promo.readDismissed(broken), false);
  assert.equal(promo.readDismissed(null), false);
  assert.doesNotThrow(() => promo.writeDismissed(broken));
  assert.doesNotThrow(() => promo.writeDismissed(null));
});

test("mount outside the window hides the banner and the section", () => {
  const doc = fakeDocument();
  doc.banner.hidden = false;
  doc.section.hidden = false;
  promo.mount(doc, { localStorage: memoryStorage(), location: { search: "" } }, atLosAngeles("2026-11-01T12:00:00"));
  assert.equal(doc.banner.hidden, true);
  assert.equal(doc.section.hidden, true);
});

test("mount inside the window shows both, and dismiss keeps the section", () => {
  const doc = fakeDocument();
  const storage = memoryStorage();
  promo.mount(
    doc,
    { localStorage: storage, location: { search: "" } },
    atLosAngeles("2026-11-30T09:00:00")
  );
  assert.equal(doc.banner.hidden, false);
  assert.equal(doc.section.hidden, false);

  doc.button.listeners.click();
  assert.equal(storage.getItem(PROMO.storageKey), "1");
  assert.equal(doc.banner.hidden, true);
  assert.equal(doc.section.hidden, false);
  assert.equal(doc.brand.focused, true);
});

test("escape dismisses the banner from the keyboard", () => {
  const doc = fakeDocument();
  promo.mount(
    doc,
    { localStorage: memoryStorage(), location: { search: "?promo=bf2026" } },
    atLosAngeles("2026-03-01T12:00:00")
  );
  let prevented = false;
  doc.banner.listeners.keydown({ key: "Tab", preventDefault() { prevented = true; } });
  assert.equal(prevented, false);
  assert.equal(doc.banner.hidden, false);
  assert.equal(doc.section.hidden, false);

  doc.banner.listeners.keydown({ key: "Escape", preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(doc.banner.hidden, true);
  assert.equal(doc.section.hidden, false);
});

test("a stored dismissal hides the banner on a later visit in the window", () => {
  const doc = fakeDocument();
  const storage = memoryStorage({ [PROMO.storageKey]: "1" });
  promo.mount(doc, { localStorage: storage, location: { search: "" } }, atLosAngeles("2026-11-28T12:00:00"));
  assert.equal(doc.section.hidden, false);
  assert.equal(doc.banner.hidden, true);
});

test("preview shows the banner again after dismissal", () => {
  const doc = fakeDocument();
  const storage = memoryStorage({ [PROMO.storageKey]: "1" });
  promo.mount(
    doc,
    { localStorage: storage, location: { search: "?promo=bf2026" } },
    atLosAngeles("2026-06-01T12:00:00")
  );
  assert.equal(doc.banner.hidden, false);
  assert.equal(doc.section.hidden, false);
});

test("dismiss is bound after DOMContentLoaded while the document is loading", () => {
  const doc = fakeDocument();
  doc.readyState = "loading";
  promo.mount(
    doc,
    { localStorage: memoryStorage(), location: { search: "?promo=bf2026" } },
    atLosAngeles("2026-06-01T12:00:00")
  );
  assert.equal(doc.button.listeners.click, undefined);
  assert.equal(doc.banner.hidden, true);
  assert.equal(doc.section.hidden, true);
  doc.listeners.DOMContentLoaded();
  assert.equal(typeof doc.button.listeners.click, "function");
  assert.equal(doc.banner.hidden, false);
  assert.equal(doc.section.hidden, false);
});

test("mount survives localStorage being blocked", () => {
  const doc = fakeDocument();
  const view = {
    get localStorage() { throw new Error("blocked"); },
    location: { search: "?promo=bf2026" },
  };
  assert.doesNotThrow(() => promo.mount(doc, view, atLosAngeles("2026-06-01T12:00:00")));
  assert.equal(doc.banner.hidden, false);
  assert.doesNotThrow(() => doc.button.listeners.click());
  assert.equal(doc.banner.hidden, true);
  assert.equal(doc.section.hidden, false);
});

test("rejects a local time that is not second precision", () => {
  assert.throws(() => promo.zonedTimeToUtc("2026-11-27", PROMO.timeZone), /YYYY-MM-DDTHH:mm:ss/);
});

test("the page hides the promo unless script opts in, with no third-party code", () => {
  const gate = ".promo-banner[hidden],.promo-section[hidden],.promo-banner[hidden] *,.promo-section[hidden] *{display:none !important}";
  assert.match(html, /class="promo-banner"[^>]*\shidden[\s>]/);
  assert.match(html, /class="promo-section"[^>]*\shidden[\s>]/);
  assert.ok(html.includes(gate));
  const section = sliceBetween(html, 'class="promo-section"', "</section>");
  assert.ok(section.indexOf("hidden") < section.indexOf('id="promo-heading"'));
  assert.equal(source.includes('classList.add("promo-banner")'), false);
  assert.equal(source.includes('classList.add("promo-on")'), false);
  assert.equal(source.includes('classList.add("show-banner")'), false);
  assert.match(source, /section\.hidden = !decision\.showSection/);
  assert.match(source, /banner\.hidden = !decision\.showBanner/);
  const styleAt = html.indexOf("<style>");
  const scriptAt = html.indexOf('<script src="promo.js">');
  const bodyAt = html.indexOf("<body>");
  assert.ok(styleAt !== -1 && scriptAt !== -1 && styleAt < scriptAt && scriptAt < bodyAt);

  const external = [...html.matchAll(/<script\b[^>]*>/g)]
    .map((match) => match[0])
    .filter((tag) => tag.includes("src="));
  assert.deepEqual(external, ['<script src="promo.js">']);

  assert.equal(html.includes("?" + PROMO.previewParam + "=" + PROMO.previewValue), true);
  assert.equal(html.includes('data-promo="' + PROMO.id + '"'), true);
  assert.doesNotMatch(source, /document\.cookie/);
  assert.doesNotMatch(source, /https?:\/\//);
  assert.match(source, /localStorage/);
  assert.doesNotMatch(html, /document\.cookie/);
  assert.doesNotMatch(html, /google-analytics|gtag\(|facebook|doubleclick/i);
});

test("banner and section reuse the hero download link and the configured label", () => {
  const primary = html.match(/class="button primary" href="(https:\/\/github\.com\/[^"]+)"/);
  assert.ok(primary);
  const href = primary[1];
  const banner = sliceBetween(html, 'class="promo-banner"', '<div class="wrap">');
  const section = sliceBetween(html, 'class="promo-section"', 'id="download"');
  assert.equal(banner.includes(href), true);
  assert.equal(section.includes(href), true);
  assert.equal(banner.includes(PROMO.label), true);
  assert.equal(section.includes(PROMO.label), true);
  assert.equal(banner.includes("play.google"), false);
  assert.equal(section.includes("play.google"), false);
  assert.match(banner, /role="region"/);
  assert.match(banner, /aria-label="Black Friday and Cyber Monday"/);
  assert.match(banner, /type="button"/);
  assert.match(banner, /data-promo-dismiss/);
  assert.match(banner, /aria-label="Dismiss promotion"/);
  assert.match(section, /aria-labelledby="promo-heading"/);
  assert.match(section, /id="promo-heading"/);
});

test("promo styles keep a visible focus treatment and do not animate", () => {
  const promoCss = css.slice(css.indexOf("Seasonal promo"));
  assert.ok(promoCss.length > 40);
  assert.match(css, /@media\(prefers-reduced-motion:reduce\)/);
  assert.match(promoCss, /\.promo-banner\[hidden\],\.promo-section\[hidden\],\.promo-banner\[hidden\] \*,\.promo-section\[hidden\] \*\{display:none !important\}/);
  assert.match(promoCss, /\.promo-banner a:focus-visible,\.promo-banner button:focus-visible\{outline:3px solid #25271d/);
  assert.match(promoCss, /@media print\{[\s\S]*body \.promo-banner\{display:none !important\}/);
  assert.doesNotMatch(promoCss, /animation\s*:/);
  assert.equal(promoCss.includes("play.google"), false);
});
