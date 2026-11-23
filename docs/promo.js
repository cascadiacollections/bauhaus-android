"use strict";

/*
 * Black Friday / Cyber Monday window for the marketing site.
 *
 * Edit PROMO to move the campaign. docs/ is served as-is by GitHub Pages
 * (no site build), so this client-side check is the gate. index.html repeats
 * PROMO.label, PROMO.id, and the preview query; test/site/promo.test.js
 * fails if those drift.
 *
 * start is inclusive. endExclusive is the first instant the promo is off.
 * Both are wall-clock times in timeZone, so 2026-12-02T00:00:00 means
 * "through 2026-12-01 23:59" America/Los_Angeles.
 *
 * Preview outside the window: ?promo=bf2026
 * Dismissal is stored in localStorage under storageKey. No cookies.
 */

var PROMO_ID = "bf2026";

var PROMO = {
  id: PROMO_ID,
  timeZone: "America/Los_Angeles",
  start: "2026-11-27T00:00:00",
  endExclusive: "2026-12-02T00:00:00",
  label: "November 27 – December 1, 2026",
  previewParam: "promo",
  previewValue: PROMO_ID,
  storageKey: "bauhaus-promo-dismissed-" + PROMO_ID,
};

var LOCAL_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$/;

function zonedTimeToUtc(isoLocal, timeZone) {
  var match = LOCAL_TIME.exec(isoLocal);
  if (!match) {
    throw new Error("Expected YYYY-MM-DDTHH:mm:ss, got " + isoLocal);
  }
  var year = Number(match[1]);
  var month = Number(match[2]);
  var day = Number(match[3]);
  var hour = Number(match[4]);
  var minute = Number(match[5]);
  var second = Number(match[6]);
  var guess = Date.UTC(year, month - 1, day, hour, minute, second);
  // Second pass picks up a DST offset that differs from the UTC guess.
  var utc = guess - zoneOffsetMs(guess, timeZone);
  return guess - zoneOffsetMs(utc, timeZone);
}

function zoneOffsetMs(utcMs, timeZone) {
  var fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
  var bag = {};
  var parts = fmt.formatToParts(new Date(utcMs));
  var i;
  for (i = 0; i < parts.length; i++) {
    if (parts[i].type !== "literal") bag[parts[i].type] = parts[i].value;
  }
  var hour = Number(bag.hour);
  var year = Number(bag.year);
  var month = Number(bag.month);
  var day = Number(bag.day);
  // A few engines report midnight as 24:00 on the day that just ended.
  if (hour === 24) {
    hour = 0;
    var rolled = new Date(Date.UTC(year, month - 1, day + 1));
    year = rolled.getUTCFullYear();
    month = rolled.getUTCMonth() + 1;
    day = rolled.getUTCDate();
  }
  var asUtc = Date.UTC(year, month - 1, day, hour, Number(bag.minute), Number(bag.second));
  return asUtc - utcMs;
}

function isPreview(search) {
  var query = search || "";
  if (query.charAt(0) === "?") query = query.slice(1);
  return new URLSearchParams(query).get(PROMO.previewParam) === PROMO.previewValue;
}

function decide(options) {
  var now = options.now instanceof Date ? options.now : new Date(options.now);
  var time = now.getTime();
  if (Number.isNaN(time)) {
    return { preview: false, inWindow: false, showBanner: false, showSection: false };
  }
  var preview = isPreview(options.search);
  var start = zonedTimeToUtc(PROMO.start, PROMO.timeZone);
  var end = zonedTimeToUtc(PROMO.endExclusive, PROMO.timeZone);
  var inWindow = time >= start && time < end;
  var showSection = preview || inWindow;
  // The preview query always shows the banner, including after a dismissal,
  // so ?promo=bf2026 stays a reliable way to review it.
  var showBanner = showSection && (preview || !options.dismissed);
  return { preview: preview, inWindow: inWindow, showBanner: showBanner, showSection: showSection };
}

function storageOf(view) {
  try {
    return view.localStorage;
  } catch (err) {
    return null;
  }
}

function readDismissed(storage) {
  if (!storage) return false;
  try {
    return storage.getItem(PROMO.storageKey) === "1";
  } catch (err) {
    return false;
  }
}

function writeDismissed(storage) {
  if (!storage) return;
  try {
    storage.setItem(PROMO.storageKey, "1");
  } catch (err) {
    // Private mode still dismisses for this view.
  }
}

function mount(doc, view, now) {
  var decision = decide({
    now: now || new Date(),
    search: (view.location && view.location.search) || "",
    dismissed: readDismissed(storageOf(view)),
  });
  var root = doc.documentElement;
  // show-banner is intentionally not the element class. A "promo-banner" class
  // on <html> would match the hide rule and blank the page.
  if (decision.showSection) root.classList.add("promo-on");
  if (decision.showBanner) root.classList.add("show-banner");

  var bind = function () {
    var banner = doc.querySelector(".promo-banner");
    if (!banner) return;
    var button = banner.querySelector("[data-promo-dismiss]");
    if (!button) return;
    var dismiss = function () {
      writeDismissed(storageOf(view));
      root.classList.remove("show-banner");
      var brand = doc.querySelector(".brand");
      if (brand && typeof brand.focus === "function") {
        brand.focus({ preventScroll: true });
      }
    };
    button.addEventListener("click", dismiss);
    banner.addEventListener("keydown", function (event) {
      if (event.key !== "Escape") return;
      event.preventDefault();
      dismiss();
    });
  };

  if (doc.readyState === "loading") doc.addEventListener("DOMContentLoaded", bind);
  else bind();
}

var api = {
  PROMO: PROMO,
  zonedTimeToUtc: zonedTimeToUtc,
  decide: decide,
  readDismissed: readDismissed,
  writeDismissed: writeDismissed,
  mount: mount,
};

if (typeof module === "object" && module.exports) module.exports = api;

if (typeof document !== "undefined") mount(document, window);
