// Listar alla datapaket på Trafikverkets Lastkajen (bara namn, id och filnamn – inget laddas ner).
// Används för att se vilken vägdata (NVDB) som finns utöver ISA hastighet.
//
// Kör:  node verktyg/lista-lastkajen.mjs > lastkajen.txt
// Inloggning från LASTKAJEN_USER och LASTKAJEN_PASSWORD (repots secrets).

const BASE = "https://lastkajen.trafikverket.se/api";
const user = (process.env.LASTKAJEN_USER ?? "").trim();
const pass = process.env.LASTKAJEN_PASSWORD ?? "";
if (!user || !pass) { console.error("LASTKAJEN_USER/LASTKAJEN_PASSWORD saknas"); process.exit(1); }

async function login() {
  for (const a of [
    { headers: { "Content-Type": "application/json" }, body: JSON.stringify({ UserName: user, Password: pass }) },
    { headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ UserName: user, Password: pass }).toString() },
  ]) {
    const res = await fetch(`${BASE}/Identity/Login`, { method: "POST", ...a });
    if (res.ok) { try { const t = JSON.parse(await res.text()).access_token; if (t) return t; } catch { /* */ } }
  }
  throw new Error("Inloggningen misslyckades");
}
const token = await login();
const get = async (path) => {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  return { ok: res.ok, status: res.status, body: res.ok ? await res.json().catch(() => null) : null };
};

// Paketlistan: API-dokumentationen är knapphändig, så vi provar de troliga anropen.
const kandidater = ["/DataPackage/GetPublishedDataPackages", "/DataPackage/GetDataPackages", "/DataPackage", "/DataPackage/GetAll"];
let paket = null;
for (const k of kandidater) {
  const r = await get(k);
  console.log(`# ${k}: HTTP ${r.status}`);
  if (r.ok && r.body) { paket = Array.isArray(r.body) ? r.body : (r.body.items ?? r.body.dataPackages ?? r.body.result ?? null); if (paket) break; }
}
if (!paket) { console.log("Hittade inget anrop som listar paketen."); process.exit(0); }

console.log(`\n${paket.length} datapaket:\n`);
const namn = (p) => p.name ?? p.title ?? p.Name ?? JSON.stringify(p).slice(0, 80);
for (const p of paket.sort((a, b) => String(namn(a)).localeCompare(String(namn(b)), "sv"))) {
  console.log(`[${p.id ?? p.Id}] ${namn(p)}${p.description ? " – " + String(p.description).replace(/\s+/g, " ").slice(0, 160) : ""}`);
}
// Filerna i paket som rör NVDB/vägdata (bara namnen).
console.log("\n--- Filer i NVDB-paketen ---");
for (const p of paket.filter((x) => /nvdb|väg|vag|gata|trafik/i.test(namn(x))).slice(0, 25)) {
  const r = await get(`/DataPackage/GetDataPackageFiles?id=${p.id ?? p.Id}`);
  const filer = (r.body ?? []).filter((f) => !f.isFolder).map((f) => f.name);
  console.log(`\n[${p.id ?? p.Id}] ${namn(p)} (${filer.length} filer)`);
  for (const f of filer.slice(0, 40)) console.log("   " + f);
}
