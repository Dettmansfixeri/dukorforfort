// Gör om Trafikverkets ISA-fil (GeoPackage) till appens kompakta datafil.
//
// Kör:  node verktyg/konvertera-isa.mjs <sökväg till ISA.gpkg>
//
// Resultat i mappen data/:
//   hastigheter.ndjson.gz  en rad per vägsträcka (se format nedan), gzip-komprimerad
//   version.json           version, storlek och kontrollsumma – det appen kollar
//   sammanfattning.md      text till GitHub-releasen
//
// Format per rad:
//   {"v":70,"p":[x0,y0,dx1,dy1,...],"r":1,"g":1,"a":[...]}
//     v  högsta tillåtna hastighet (km/h)
//     p  linje i SWEREF 99 TM, hela meter, första punkten absolut och resten som skillnader
//     r  riktning: saknas = båda håll, 1 = med digitaliseringsriktningen, 2 = mot
//     g  1 om sträckan är en gren (av-/påfart). Saknas annars.
//     a  avvikande hastighet vissa tider (saknas om ingen):
//        {"v":30,"d":"VUF","k":[[700,1700]],"s":"0101","e":"1231","w":["mån","fre"]}
//          d  dagslag: V = vardag, VUF = vardag utom dag före sön- och helgdag,
//             VF = vardag före sön- och helgdag, S = sön- och helgdag (saknas = alla dagar)
//          k  klockslag som [start, slut] i formen TTMM (slut < start betyder över midnatt)
//          s/e  datumintervall MMDD (saknas = hela året)
//          w  veckodagsintervall [startdag, slutdag] (saknas = alla)

import { DatabaseSync } from "node:sqlite";
import {
  mkdirSync, createWriteStream, statSync, createReadStream,
  readFileSync, writeFileSync, appendFileSync,
} from "node:fs";
import { createGzip } from "node:zlib";
import { pipeline } from "node:stream/promises";
import { createHash } from "node:crypto";

const gpkgPath = process.argv[2];
if (!gpkgPath) { console.error("Ange sökvägen till ISA.gpkg"); process.exit(1); }
const sourceName = process.argv[3] ?? "";

const TOLERANS_M = 2; // förenkling av vägarnas form (Douglas–Peucker)

// ---------- GeoPackage-geometri -> lista med [x, y] ----------
function parseGpkgLine(blob) {
  if (!blob || blob[0] !== 0x47 || blob[1] !== 0x50) return null; // "GP"
  const flags = blob[3];
  const envCode = (flags >> 1) & 7;
  const envBytes = [0, 32, 48, 48, 64][envCode] ?? 0;
  let o = 8 + envBytes;
  const dv = new DataView(blob.buffer, blob.byteOffset, blob.byteLength);
  const le = blob[o] === 1; o += 1;
  let type = dv.getUint32(o, le); o += 4;
  let dims = 2;
  if (type > 1000) { // ISO-WKB med Z/M
    if (type >= 3000) dims = 4; else if (type >= 1000) dims = 3;
    type %= 1000;
  }
  if (type !== 2) return null; // bara LineString
  const n = dv.getUint32(o, le); o += 4;
  const pts = new Array(n);
  for (let i = 0; i < n; i++) {
    pts[i] = [dv.getFloat64(o, le), dv.getFloat64(o + 8, le)];
    o += 8 * dims;
  }
  return pts;
}

// ---------- Douglas–Peucker ----------
function simplify(pts, tol) {
  if (pts.length <= 2) return pts;
  const keep = new Uint8Array(pts.length);
  keep[0] = keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  const tol2 = tol * tol;
  while (stack.length) {
    const [a, b] = stack.pop();
    const [ax, ay] = pts[a], [bx, by] = pts[b];
    const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
    let maxD = -1, idx = -1;
    for (let i = a + 1; i < b; i++) {
      const [px, py] = pts[i];
      let d2;
      if (len2 === 0) d2 = (px - ax) ** 2 + (py - ay) ** 2;
      else {
        const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        d2 = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2;
      }
      if (d2 > maxD) { maxD = d2; idx = i; }
    }
    if (maxD > tol2) { keep[idx] = 1; stack.push([a, idx], [idx, b]); }
  }
  return pts.filter((_, i) => keep[i]);
}

function encode(pts) {
  const out = [];
  let px = 0, py = 0, first = true;
  for (const [fx, fy] of pts) {
    const x = Math.round(fx), y = Math.round(fy);
    if (first) { out.push(x, y); first = false; }
    else if (x !== px || y !== py) out.push(x - px, y - py);
    else continue;
    px = x; py = y;
  }
  return out.length >= 4 ? out : null;
}

// ---------- Avvikande hastighet ----------
const DAGSLAG = {
  "vardag": "V",
  "vardag utom dag före sön- och helgdag": "VUF",
  "vardag före sön- och helgdag": "VF",
  "sön- och helgdag": "S",
};
const unknownDagslag = new Set();
const isSet = (v) => v !== null && v !== undefined && v !== -1 && v !== "-1";
// Datum utan år (återkommer varje år) lagras som 1899-MM-DD; 1899-12-29 betyder "inget datum".
const mmdd = (d) => (typeof d === "string" && d.length >= 10 && d !== "1899-12-29" ? d.slice(5, 7) + d.slice(8, 10) : null);
const hhmm = (h, m) => h * 100 + (isSet(m) ? m : 0);

function interval(r, n) { // n = 1 eller 2 (Tidsintervall 11 / 12)
  const k = [];
  for (const j of [1, 2]) {
    const st = r[`STTIM1${n}${j}`], sl = r[`SLTIM1${n}${j}`];
    if (isSet(st) && isSet(sl)) k.push([hhmm(st, r[`STMIN1${n}${j}`]), hhmm(sl, r[`SLMIN1${n}${j}`])]);
  }
  const o = {};
  const ds = r[`DAGSL1${n}`];
  if (isSet(ds)) {
    o.d = DAGSLAG[ds];
    if (!o.d) { unknownDagslag.add(ds); o.d = ds; }
  }
  if (k.length) o.k = k;
  const s = mmdd(r[`STDAT1${n}`]), e = mmdd(r[`SLDAT1${n}`]);
  if (s && e) { o.s = s; o.e = e; }
  const wd1 = r[`STDAG1${n}`], wd2 = r[`SLDAG1${n}`];
  if (isSet(wd1)) o.w = [wd1, isSet(wd2) ? wd2 : wd1];
  return Object.keys(o).length ? o : null;
}

let skippedVehicle = 0;
function deviations(r) {
  if (!isSet(r.HAVHAST1) || r.HAVHAST1 <= 0) return null;
  // Avvikelser som bara gäller vissa fordonstyper eller vikter hoppar vi över.
  // HAVGIE1 ("gäller inte/endast") utan angiven fordonstyp eller vikt säger inget
  // om vilka fordon som avses – sådana (oftast skolzoner) behandlas som gällande alla.
  const fordon = [r.FORDTYP11, r.FORDTYP12, r.FORDTYP13].some(isSet);
  if (fordon || (isSet(r.TOTALVIKT1) && r.TOTALVIKT1 > 0)) {
    skippedVehicle++;
    return null;
  }
  const out = [];
  for (const n of [1, 2]) {
    const iv = interval(r, n);
    if (iv) out.push({ v: r.HAVHAST1, ...iv });
  }
  return out.length ? out : [{ v: r.HAVHAST1 }];
}

// ---------- Läs, slå ihop, skriv ----------
const db = new DatabaseSync(gpkgPath, { readOnly: true });
const rows = db.prepare(`select * from ISA order by RLID, STARTAVST`).iterate();

mkdirSync(new URL("../data/", import.meta.url), { recursive: true });
const ndjsonPath = new URL("../data/hastigheter.ndjson", import.meta.url);
const out = createWriteStream(ndjsonPath);

let totalIn = 0, written = 0, noGeom = 0;
const perLimit = {};
let cur = null; // { key, rlid, end, pts, rec }

function flush() {
  if (!cur) return;
  const p = encode(simplify(cur.pts, TOLERANS_M));
  if (p) {
    const rec = { v: cur.v, p };
    if (cur.r) rec.r = cur.r;
    if (cur.g) rec.g = 1;
    if (cur.a) rec.a = cur.a;
    if (!out.write(JSON.stringify(rec) + "\n")) { /* buffras */ }
    written++;
    perLimit[cur.v] = (perLimit[cur.v] ?? 0) + 1;
  }
  cur = null;
}

const t0 = Date.now();
for (const r of rows) {
  totalIn++;
  if (totalIn % 500000 === 0) console.log(`  läst ${totalIn} rader (${((Date.now() - t0) / 1000).toFixed(0)} s)`);
  const pts = parseGpkgLine(r.geom);
  if (!pts || pts.length < 2 || !(r.HTHAST > 0)) { noGeom++; continue; }
  const rDir = r.RIKTNING === "Med" ? 1 : r.RIKTNING === "Mot" ? 2 : 0;
  const a = deviations(r);
  const key = `${r.HTHAST}|${rDir}|${r.GREN === 1 ? 1 : 0}|${a ? JSON.stringify(a) : ""}`;

  // Slå ihop med föregående om det är samma länk, direkt fortsättning och samma regler.
  if (cur && cur.key === key && cur.rlid === r.RLID && Math.abs(cur.end - r.STARTAVST) < 1e-9) {
    const last = cur.pts[cur.pts.length - 1];
    const [fx, fy] = pts[0];
    if (Math.hypot(last[0] - fx, last[1] - fy) < 1) {
      for (let i = 1; i < pts.length; i++) cur.pts.push(pts[i]);
      cur.end = r.SLUTAVST;
      continue;
    }
  }
  flush();
  cur = { key, rlid: r.RLID, end: r.SLUTAVST, pts, v: r.HTHAST, r: rDir, g: r.GREN === 1, a };
}
flush();
await new Promise((res) => out.end(res));
db.close();

const gzPath = new URL("../data/hastigheter.ndjson.gz", import.meta.url);
await pipeline(createReadStream(ndjsonPath), createGzip({ level: 9 }), createWriteStream(gzPath));

// ---------- version.json och sammanfattning ----------
const now = new Date();
const pad = (n) => String(n).padStart(2, "0");
const version = `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
  `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`;
const gzBytes = readFileSync(gzPath);
writeFileSync(new URL("../data/version.json", import.meta.url), JSON.stringify({
  version,
  kalla: sourceName || "ISA hastighet (Trafikverket, Lastkajen)",
  fil: "hastigheter.ndjson.gz",
  storlek: gzBytes.length,
  sha256: createHash("sha256").update(gzBytes).digest("hex"),
  strackor: written,
}, null, 2) + "\n");

const mb = (p) => (statSync(p).size / 1024 / 1024).toFixed(1) + " MB";
const rowsOut = [
  ["Källa", sourceName || "ISA.gpkg"],
  ["Rader i ISA-filen", totalIn],
  ["Sträckor efter hopslagning", written],
  ["Utan geometri/hastighet", noGeom],
  ["Fordonsspecifika avvikelser (skippade)", skippedVehicle],
  ["Okomprimerad fil", mb(ndjsonPath)],
  ["Komprimerad fil (.gz)", mb(gzPath)],
  ["Tid", ((Date.now() - t0) / 1000).toFixed(0) + " s"],
];
console.log("\n===== RESULTAT =====");
for (const [k, v] of rowsOut) console.log(`${(k + ":").padEnd(42)}${v}`);
console.log("Fördelning per hastighet:", perLimit);
if (unknownDagslag.size) console.log("Okända dagslag:", [...unknownDagslag]);

const md = [
  `Hastighetsgränser från Trafikverket (ISA hastighet via Lastkajen), konverterade ${now.toISOString().slice(0, 16).replace("T", " ")} UTC.`,
  "", "| | |", "|---|---|",
  ...rowsOut.map(([k, v]) => `| ${k} | ${v} |`),
  "", "Fördelning per hastighet: " + Object.entries(perLimit).map(([k, v]) => `${k}: ${v}`).join(", "),
  "", "Data: Trafikverket, CC0.",
].join("\n");
writeFileSync(new URL("../data/sammanfattning.md", import.meta.url), md + "\n");
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, md + "\n");
