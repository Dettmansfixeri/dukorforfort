// Slår upp hastighetsgränsen för en plats i data/hastigheter.bin – samma logik som appen.
//
// Kör:  node verktyg/testa-uppslag.mjs 59.3245 18.0075 [kurs i grader]
//       node verktyg/testa-uppslag.mjs            (kör inbyggda testplatser)

import { readFileSync } from "node:fs";

const buf = readFileSync(new URL("../data/hastigheter.bin", import.meta.url));
if (buf.toString("ascii", 0, 4) !== "DKF1") throw new Error("Fel filformat");
const H = {
  cell: buf.readUInt32LE(4), minE: buf.readInt32LE(8), minN: buf.readInt32LE(12),
  cols: buf.readUInt32LE(16), rows: buf.readUInt32LE(20),
  rulesOff: buf.readUInt32LE(28), rulesLen: buf.readUInt32LE(32),
  cellOff: buf.readUInt32LE(36), refsOff: buf.readUInt32LE(40), segOff: buf.readUInt32LE(44),
};
const rules = JSON.parse(buf.toString("utf8", H.rulesOff, H.rulesOff + H.rulesLen));

// WGS84 -> SWEREF 99 TM (Lantmäteriets formler, samma som Sweref99.kt)
function sweref(lat, lon) {
  const a = 6378137, f = 1 / 298.257222101, lon0 = 15 * Math.PI / 180, k0 = 0.9996, FE = 500000;
  const e2 = f * (2 - f), n = f / (2 - f), ah = a / (1 + n) * (1 + n * n / 4 + n ** 4 / 64);
  const A = e2, B = (5 * e2 ** 2 - e2 ** 3) / 6, C = (104 * e2 ** 3 - 45 * e2 ** 4) / 120, D = 1237 * e2 ** 4 / 1260;
  const b1 = n / 2 - 2 * n * n / 3 + 5 * n ** 3 / 16 + 41 * n ** 4 / 180;
  const b2 = 13 * n * n / 48 - 3 * n ** 3 / 5 + 557 * n ** 4 / 1440;
  const b3 = 61 * n ** 3 / 240 - 103 * n ** 4 / 140, b4 = 49561 * n ** 4 / 161280;
  const phi = lat * Math.PI / 180, lam = lon * Math.PI / 180;
  const s = Math.sin(phi);
  const phiS = phi - s * Math.cos(phi) * (A + B * s * s + C * s ** 4 + D * s ** 6);
  const dl = lam - lon0;
  const xi = Math.atan(Math.tan(phiS) / Math.cos(dl));
  const eta = Math.atanh(Math.cos(phiS) * Math.sin(dl));
  const N = k0 * ah * (xi + b1 * Math.sin(2 * xi) * Math.cosh(2 * eta) + b2 * Math.sin(4 * xi) * Math.cosh(4 * eta)
    + b3 * Math.sin(6 * xi) * Math.cosh(6 * eta) + b4 * Math.sin(8 * xi) * Math.cosh(8 * eta));
  const E = k0 * ah * (eta + b1 * Math.cos(2 * xi) * Math.sinh(2 * eta) + b2 * Math.cos(4 * xi) * Math.sinh(4 * eta)
    + b3 * Math.cos(6 * xi) * Math.sinh(6 * eta) + b4 * Math.cos(8 * xi) * Math.sinh(8 * eta)) + FE;
  return [E, N];
}

function readSeg(off) {
  let o = H.segOff + off;
  const v = buf.readUInt8(o++), fl = buf.readUInt8(o++);
  let rule = -1;
  if (fl & 8) { rule = buf.readUInt16LE(o); o += 2; }
  const n = buf.readUInt16LE(o); o += 2;
  let x = buf.readInt32LE(o), y = buf.readInt32LE(o + 4); o += 8;
  const pts = [[x, y]];
  for (let i = 1; i < n; i++) { x += buf.readInt16LE(o); y += buf.readInt16LE(o + 2); o += 4; pts.push([x, y]); }
  return { v, dir: fl & 3, gren: !!(fl & 4), rule, pts };
}

function lookup(lat, lon, course) {
  const [e, n] = sweref(lat, lon);
  const c = Math.floor((e - H.minE) / H.cell), r = Math.floor((n - H.minN) / H.cell);
  if (c < 0 || r < 0 || c >= H.cols || r >= H.rows) return null;
  const cell = r * H.cols + c;
  const from = buf.readUInt32LE(H.cellOff + cell * 4), to = buf.readUInt32LE(H.cellOff + (cell + 1) * 4);
  const cands = [];
  for (let i = from; i < to; i++) {
    const s = readSeg(buf.readUInt32LE(H.refsOff + i * 4));
    let best = Infinity, bearing = 0;
    for (let k = 1; k < s.pts.length; k++) {
      const [ax, ay] = s.pts[k - 1], [bx, by] = s.pts[k];
      const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
      const t = l2 ? Math.max(0, Math.min(1, ((e - ax) * dx + (n - ay) * dy) / l2)) : 0;
      const d = Math.hypot(e - ax - t * dx, n - ay - t * dy);
      if (d < best) { best = d; bearing = (Math.atan2(dx, dy) * 180 / Math.PI + 360) % 360; }
    }
    cands.push({ ...s, dist: best, bearing });
  }
  cands.sort((a, b) => a.dist - b.dist);
  return { e, n, cands: cands.slice(0, 4) };
}

const places = process.argv.length > 3
  ? [[`${process.argv[2]}, ${process.argv[3]}`, +process.argv[2], +process.argv[3]]]
  : [
    ["Essingeleden, Stockholm", 59.3236, 18.0010],
    ["E4 norr om Uppsala", 59.9600, 17.6000],
    ["Drottninggatan (gågata), Stockholm", 59.3330, 18.0627],
    ["Kungsgatan, Göteborg", 57.7046, 11.9636],
    ["E6 vid Kungsbacka", 57.4900, 12.0530],
  ];
for (const [name, lat, lon] of places) {
  const r = lookup(lat, lon);
  console.log(`\n${name}  (E ${r?.e.toFixed(0)}, N ${r?.n.toFixed(0)})`);
  if (!r || !r.cands.length) { console.log("  ingen väg i närheten"); continue; }
  for (const c of r.cands) {
    console.log(`  ${String(c.v).padStart(3)} km/h  ${c.dist.toFixed(1).padStart(6)} m  kurs ${c.bearing.toFixed(0).padStart(3)}°` +
      `${c.dir ? "  riktning " + c.dir : ""}${c.gren ? "  gren" : ""}${c.rule >= 0 ? "  regel " + JSON.stringify(rules[c.rule]) : ""}`);
  }
}
