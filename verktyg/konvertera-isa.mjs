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
//          f  fordonskoder som avvikelsen avser (saknas = alla fordon), se lista nedan
//          fi 1 = gäller INTE fordonen i f, 2 = gäller ENDAST dem (saknas = endast)
//          tv gäller fordon över denna totalvikt i ton

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

// Fordonskoder enligt Trafikverkets dataproduktspecifikation för Hastighetsgräns:
//  10 bil, 20 buss, 30 cykel, 40 fordon, 49 personbil klass II, 50 fordon i linjetrafik,
//  70 fordon för rörelsehindrade m. tillstånd, 80 hästfordon, 90 lastbil, 100 lätt lastbil,
//  110 lätt terrängvagn, 120 moped, 130 moped kl I, 140 moped kl II, 150 motorcykel,
//  160 motordrivet fordon med släp (annat än påhängsvagn/enaxlig), 170 motordrivna fordon,
//  180 motorredskap, 190/200 motorredskap kl I/II, 210 personbil, 230 terrängmotorfordon,
//  240 terrängmotorfordon och terrängsläp, 250 terrängskoter, 260 terrängvagn, 270 traktor,
//  280 tung lastbil, 290 tung terrängvagn, 300 utryckningsfordon, 9999 okänt.
// "Gäller inte/endast fordon": 1 = gäller inte, 2 = gäller endast.
// Allt sparas så att appen kan räkna ut gränsen för det fordon man valt.
let withVehicle = 0;
function deviations(r) {
  if (!isSet(r.HAVHAST1) || r.HAVHAST1 <= 0) return null;
  const f = [r.FORDTYP11, r.FORDTYP12, r.FORDTYP13].filter(isSet);
  const vehicle = {};
  if (f.length) vehicle.f = f;
  if (f.length && isSet(r.HAVGIE1)) vehicle.fi = r.HAVGIE1; // 1 = inte, 2 = endast
  if (isSet(r.TOTALVIKT1) && r.TOTALVIKT1 > 0) vehicle.tv = r.TOTALVIKT1; // ton
  if (Object.keys(vehicle).length) withVehicle++;
  const out = [];
  for (const n of [1, 2]) {
    const iv = interval(r, n);
    if (iv) out.push({ v: r.HAVHAST1, ...vehicle, ...iv });
  }
  return out.length ? out : [{ v: r.HAVHAST1, ...vehicle }];
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

// ---------- Binärformat för appen (hastigheter.bin) ----------
// Alla tal little-endian. Koordinater i SWEREF 99 TM, hela meter.
//
//  Header (48 byte):
//    0  "DKF1"
//    4  u32 cellstorlek (m)          8  i32 minE        12  i32 minN
//   16  u32 kolumner                20  u32 rader       24  u32 antal sträckor
//   28  u32 regler: offset          32  u32 regler: längd (UTF-8 JSON)
//   36  u32 cellindex: offset       40  u32 referenser: offset
//   44  u32 sträckor: offset
//  Cellindex: (kolumner*rader + 1) st u32 – start i referenslistan för cell i
//             (cell = rad*kolumner + kolumn, rad räknas från minN).
//  Referenser: u32 per post – offset (relativt sträckområdet) till en sträcka.
//             En sträcka finns med i varje cell som dess utbredning + 50 m överlappar.
//  Sträcka:  u8 hastighet, u8 flaggor (bit0-1 riktning 0/1/2, bit2 gren, bit3 har regel),
//            [u16 regel-id om bit3], u16 antal punkter n, i32 x0, i32 y0,
//            (n-1) × (i16 dx, i16 dy)
//  Regler:   JSON-array; regel-id pekar på en lista avvikelser (formatet "a" ovan).
const CELL_M = 2000;
const MARGIN_M = 50;
const segBufs = [];      // Buffer per sträcka
const segBox = [];       // [minX, minY, maxX, maxY]
const ruleIds = new Map();
const rulesList = [];

function splitBigDeltas(pts) { // pts i hela meter; inga steg större än i16
  const out = [pts[0]];
  for (let i = 1; i < pts.length; i++) {
    const [ax, ay] = out[out.length - 1], [bx, by] = pts[i];
    const steps = Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay)) / 30000);
    for (let s = 1; s < steps; s++) {
      out.push([Math.round(ax + ((bx - ax) * s) / steps), Math.round(ay + ((by - ay) * s) / steps)]);
    }
    out.push([bx, by]);
  }
  return out;
}

function addBinary(c, simplified) {
  let pts = [];
  for (const [fx, fy] of simplified) {
    const x = Math.round(fx), y = Math.round(fy);
    const last = pts[pts.length - 1];
    if (!last || last[0] !== x || last[1] !== y) pts.push([x, y]);
  }
  if (pts.length < 2) return;
  pts = splitBigDeltas(pts);
  let ruleId = -1;
  if (c.a) {
    const k = JSON.stringify(c.a);
    ruleId = ruleIds.get(k);
    if (ruleId === undefined) { ruleId = rulesList.length; ruleIds.set(k, ruleId); rulesList.push(c.a); }
  }
  const hasRule = ruleId >= 0;
  const buf = Buffer.alloc(2 + (hasRule ? 2 : 0) + 2 + 8 + (pts.length - 1) * 4);
  let o = 0;
  buf.writeUInt8(c.v, o++);
  buf.writeUInt8((c.r & 3) | (c.g ? 4 : 0) | (hasRule ? 8 : 0), o++);
  if (hasRule) { buf.writeUInt16LE(ruleId, o); o += 2; }
  buf.writeUInt16LE(pts.length, o); o += 2;
  buf.writeInt32LE(pts[0][0], o); o += 4;
  buf.writeInt32LE(pts[0][1], o); o += 4;
  let minX = pts[0][0], minY = pts[0][1], maxX = minX, maxY = minY;
  for (let i = 1; i < pts.length; i++) {
    buf.writeInt16LE(pts[i][0] - pts[i - 1][0], o); o += 2;
    buf.writeInt16LE(pts[i][1] - pts[i - 1][1], o); o += 2;
    minX = Math.min(minX, pts[i][0]); maxX = Math.max(maxX, pts[i][0]);
    minY = Math.min(minY, pts[i][1]); maxY = Math.max(maxY, pts[i][1]);
  }
  segBufs.push(buf);
  segBox.push([minX, minY, maxX, maxY]);
}

function flush() {
  if (!cur) return;
  const simplified = simplify(cur.pts, TOLERANS_M);
  const p = encode(simplified);
  if (p) {
    const rec = { v: cur.v, p };
    if (cur.r) rec.r = cur.r;
    if (cur.g) rec.g = 1;
    if (cur.a) rec.a = cur.a;
    if (!out.write(JSON.stringify(rec) + "\n")) { /* buffras */ }
    addBinary(cur, simplified);
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

const ndjsonGzPath = new URL("../data/hastigheter.ndjson.gz", import.meta.url);
await pipeline(createReadStream(ndjsonPath), createGzip({ level: 9 }), createWriteStream(ndjsonGzPath));

// ---------- Bygg hastigheter.bin ----------
let gMinX = Infinity, gMinY = Infinity, gMaxX = -Infinity, gMaxY = -Infinity;
for (const [a, b, c, d] of segBox) {
  gMinX = Math.min(gMinX, a); gMinY = Math.min(gMinY, b);
  gMaxX = Math.max(gMaxX, c); gMaxY = Math.max(gMaxY, d);
}
const minE = Math.floor((gMinX - MARGIN_M) / CELL_M) * CELL_M;
const minN = Math.floor((gMinY - MARGIN_M) / CELL_M) * CELL_M;
const cols = Math.ceil((gMaxX + MARGIN_M - minE) / CELL_M) + 1;
const rowsN = Math.ceil((gMaxY + MARGIN_M - minN) / CELL_M) + 1;
const nCells = cols * rowsN;

// Offset för varje sträcka i sträckområdet
const segOffset = new Uint32Array(segBufs.length);
let segBytes = 0;
for (let i = 0; i < segBufs.length; i++) { segOffset[i] = segBytes; segBytes += segBufs[i].length; }

// Räkna referenser per cell, sedan fyll i
const cellOf = (x, y) => [Math.floor((x - minE) / CELL_M), Math.floor((y - minN) / CELL_M)];
const counts = new Uint32Array(nCells + 1);
const forEachCell = (i, fn) => {
  const [a, b, c, d] = segBox[i];
  const [c0, r0] = cellOf(a - MARGIN_M, b - MARGIN_M);
  const [c1, r1] = cellOf(c + MARGIN_M, d + MARGIN_M);
  for (let r = r0; r <= r1; r++) for (let cc = c0; cc <= c1; cc++) fn(r * cols + cc);
};
for (let i = 0; i < segBufs.length; i++) forEachCell(i, (cell) => counts[cell + 1]++);
for (let i = 1; i <= nCells; i++) counts[i] += counts[i - 1];
const totalRefs = counts[nCells];
const refs = new Uint32Array(totalRefs);
const fill = counts.slice(0, nCells);
for (let i = 0; i < segBufs.length; i++) forEachCell(i, (cell) => { refs[fill[cell]++] = segOffset[i]; });

const rulesBuf = Buffer.from(JSON.stringify(rulesList), "utf8");
const HEADER = 48;
const rulesOff = HEADER;
const cellOff = rulesOff + rulesBuf.length;
const refsOff = cellOff + (nCells + 1) * 4;
const segOff = refsOff + totalRefs * 4;
const header = Buffer.alloc(HEADER);
header.write("DKF1", 0, "ascii");
header.writeUInt32LE(CELL_M, 4);
header.writeInt32LE(minE, 8);
header.writeInt32LE(minN, 12);
header.writeUInt32LE(cols, 16);
header.writeUInt32LE(rowsN, 20);
header.writeUInt32LE(segBufs.length, 24);
header.writeUInt32LE(rulesOff, 28);
header.writeUInt32LE(rulesBuf.length, 32);
header.writeUInt32LE(cellOff, 36);
header.writeUInt32LE(refsOff, 40);
header.writeUInt32LE(segOff, 44);

const binPath = new URL("../data/hastigheter.bin", import.meta.url);
const bin = createWriteStream(binPath);
const write = (b) => new Promise((res) => (bin.write(b) ? res() : bin.once("drain", res)));
await write(header);
await write(rulesBuf);
await write(Buffer.from(counts.buffer, counts.byteOffset, counts.byteLength));
await write(Buffer.from(refs.buffer, refs.byteOffset, refs.byteLength));
for (let i = 0; i < segBufs.length; i += 10000) await write(Buffer.concat(segBufs.slice(i, i + 10000)));
await new Promise((res) => bin.end(res));

const gzPath = new URL("../data/hastigheter.bin.gz", import.meta.url);
await pipeline(createReadStream(binPath), createGzip({ level: 9 }), createWriteStream(gzPath));

// ---------- version.json och sammanfattning ----------
const now = new Date();
const pad = (n) => String(n).padStart(2, "0");
const version = `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
  `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`;
const gzBytes = readFileSync(gzPath);
writeFileSync(new URL("../data/version.json", import.meta.url), JSON.stringify({
  version,
  kalla: sourceName || "ISA hastighet (Trafikverket, Lastkajen)",
  format: 1,
  fil: "hastigheter.bin.gz",
  storlek: gzBytes.length,
  sha256: createHash("sha256").update(gzBytes).digest("hex"),
  okomprimerad: statSync(binPath).size,
  strackor: written,
}, null, 2) + "\n");

const mb = (p) => (statSync(p).size / 1024 / 1024).toFixed(1) + " MB";
const rowsOut = [
  ["Källa", sourceName || "ISA.gpkg"],
  ["Rader i ISA-filen", totalIn],
  ["Sträckor efter hopslagning", written],
  ["Utan geometri/hastighet", noGeom],
  ["Avvikelser med fordonstyp/vikt", withVehicle],
  ["Unika regler", rulesList.length],
  ["Appens fil (hastigheter.bin)", mb(binPath)],
  ["Appens fil komprimerad (.bin.gz)", mb(gzPath)],
  ["Läsbar fil (.ndjson.gz)", mb(ndjsonGzPath)],
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
