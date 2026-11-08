// Mode 06 + trendbewaking. Port van obd/mode06.py en obd/health.py.
(function () {
  // ---------------- Mode 06 ----------------
  const MID_NL = {
    0x01: "Lambdasonde B1S1", 0x02: "Lambdasonde B1S2", 0x03: "Lambdasonde B1S3",
    0x21: "Katalysator B1", 0x31: "EGR", 0x41: "Lambdasonde-verwarming B1S1",
    0x42: "Lambdasonde-verwarming B1S2", 0x81: "Brandstofsysteem B1", 0x85: "Turbodruk B1",
    0x90: "NOx-adsorber B1", 0x98: "NOx-/SCR-katalysator B1", 0xB0: "Roetfilter (DPF) B1",
  };
  const hex = n => n.toString(16).toUpperCase().padStart(2, "0");
  const s16 = (v, signed) => (signed && (v & 0x8000)) ? v - 0x10000 : v;

  async function supportedMids() {
    const mids = [];
    for (let base = 0; base <= 0xE0; base += 0x20) {
      const d = await ELM.query("06" + hex(base));
      if (!d || d[0] !== 0x46 || d.length < 6) break;
      const bits = ((d[2] << 24) | (d[3] << 16) | (d[4] << 8) | d[5]) >>> 0;
      for (let i = 0; i < 32; i++) if (bits & (1 << (31 - i)) >>> 0) mids.push(base + 1 + i);
      if (!(bits & 1)) break;
    }
    return mids.filter(m => m % 0x20 !== 0);
  }

  async function readMode06() {
    const res = [];
    for (const mid of await supportedMids()) {
      const d = await ELM.query("06" + hex(mid));
      if (!d || d[0] !== 0x46) continue;
      const body = d.slice(1);
      for (let i = 0; i + 8 < body.length; i += 9) {
        const m = body[i], tid = body[i + 1], uas = body[i + 2], signed = uas >= 0x80;
        const val = s16((body[i + 3] << 8) | body[i + 4], signed);
        const lo = s16((body[i + 5] << 8) | body[i + 6], signed);
        const hi = s16((body[i + 7] << 8) | body[i + 8], signed);
        res.push({ mid: hex(m), naam: MID_NL[m] || `MID ${hex(m)} (fabrikant/onbekend)`, tid: hex(tid), uas: hex(uas),
          waarde: val, min: lo, max: hi, ok: lo <= val && val <= hi, uitgevoerd: !(val === 0 && lo === 0 && hi === 0) });
      }
    }
    return res;
  }

  function mode06Margins(history) {
    const first = {}, last = {};
    for (const rec of history) for (const t of rec.tests) {
      if (t.uitgevoerd === false) continue;
      const limits = [t.min, t.max].filter(x => ![-32768, 32767, 65535].includes(x));
      if (!limits.length) continue;
      const margin = Math.min(...limits.map(x => Math.abs(t.waarde - x)));
      const grens = limits.reduce((a, x) => Math.abs(t.waarde - x) < Math.abs(t.waarde - a) ? x : a);
      const key = t.mid + "-" + t.tid, e = { naam: t.naam, tid: t.tid, marge: margin, waarde: t.waarde, grens, ok: t.ok, tijd: rec.tijd };
      if (!first[key]) first[key] = e;
      last[key] = e;
    }
    const order = { rood: 0, oranje: 1, ok: 2 };
    return Object.keys(last).map(k => {
      const l = last[k], f = first[k];
      let status = l.ok ? "ok" : "rood";
      if (l.ok && f.marge > 0 && l.marge < 0.5 * f.marge) status = "oranje";
      return { ...l, marge_eerst: f.marge, status, metingen_sinds: f.tijd };
    }).sort((a, b) => order[a.status] - order[b.status]);
  }

  // ---------------- Trendbewaking ----------------
  const BASIS_MIN = 5;
  // [naam, eenheid, min_n, richting (+1 hoger slechter, -1 lager slechter, 0 beide), drempel oranje, drempel rood, tip]
  const FEATURES = {
    koeling: ["Koelvloeistof boven buitentemp. bij hoge last", "°C", 60, +1, 5, 10,
      "Radiator/condensor vrij van stof en insecten? Koelvloeistofniveau, ventilatorkoppeling, waterpomp."],
    turbo: ["Turbodruk tekort onder last (gevraagd − werkelijk)", "kPa", 30, +1, 8, 15,
      "Laadluchtslangen en klemmen nalopen (bekend zwak punt), intercooler op lekkage, turboactuator."],
    rail: ["Raildruk-afwijking", "%", 30, +1, 3, 6,
      "Brandstoffilter (vuile diesel!), water in filter, voedingsdruk, hogedrukpomp."],
    intercooler: ["Inlaatlucht boven buitentemp. bij hoge last", "°C", 60, +1, 6, 12,
      "Intercooler aan buitenzijde schoonmaken (stof/insecten), lekkage, olie in laadlucht."],
    laadspanning: ["Laadspanning (motor warm, >1200 rpm)", "V", 60, -1, 0.3, 0.6,
      "Dynamo/riem, accupolen, massaverbinding. Mercedes laadt variabel: kijk naar de trend."],
    rustspanning: ["Accu-rustspanning vóór start", "V", 1, -1, 0.2, 0.4,
      "Accu laden/testen; check sluipverbruik (camper-installatie, dongle in OBD-poort)."],
    opwarmtijd: ["Opwarmtijd koud (≤40 °C) → 80 °C", "min", 1, +1, 4, 8,
      "Thermostaat blijft open hangen? (vooral als het ook bij warm weer lang duurt)"],
    stationair_maf: ["Luchtmassa warm stationair", "g/s", 30, 0, 1.2, 2.5,
      "Informatief: luchtfilter, MAF-sensor, EGR, lekkage na de MAF."],
  };

  class TripHealth {
    constructor(saved) {
      this.sum = {}; this.n = {}; this.single = {}; this.started = false; this.coldStart = null;
      for (const k in FEATURES) { this.sum[k] = 0; this.n[k] = 0; }
      if (saved) Object.assign(this, saved);
    }
    add(k, x) { this.sum[k] += x; this.n[k]++; }
    sample(v, now) {
      const rpm = v.rpm, load = v.load, spd = v.speed || 0, cool = v.coolant, amb = v.ambient, rt = v.runtime || 0;
      if (rpm == null) return;
      if (!this.started) {
        if (rpm === 0 && v.voltage != null && !("rustspanning" in this.single)) this.single.rustspanning = v.voltage;
        if (rpm > 400) { this.started = true; if (cool != null && cool <= 40) this.coldStart = now; }
      }
      if (this.coldStart && cool != null && cool >= 80 && !("opwarmtijd" in this.single))
        this.single.opwarmtijd = Math.round((now - this.coldStart) / 6000) / 10;
      const hot = load != null && load >= 60;
      if (hot && spd >= 40 && cool != null && amb != null && rt > 900) this.add("koeling", cool - amb);
      if (hot && rpm >= 1800 && (v.boost_cmd || 0) >= 140 && v.boost_act != null) this.add("turbo", v.boost_cmd - v.boost_act);
      if ((v.rail_cmd || 0) >= 600 && v.rail_dev != null) this.add("rail", v.rail_dev);
      if (hot && spd >= 60 && v.iat != null && amb != null) this.add("intercooler", v.iat - amb);
      if (rpm >= 1200 && rt >= 600 && v.voltage != null) this.add("laadspanning", v.voltage);
      if (cool != null && cool >= 80 && rpm < 850 && spd === 0 && v.maf != null) this.add("stationair_maf", v.maf);
    }
    features() {
      const out = {};
      for (const k in FEATURES) {
        if (k in this.single) out[k] = this.single[k];
        else if (this.n[k] >= FEATURES[k][2]) out[k] = Math.round(this.sum[k] / this.n[k] * 100) / 100;
      }
      return out;
    }
    progress() {
      const p = {};
      for (const k in FEATURES) p[k] = (k in this.single) ? 1 : Math.min(1, this.n[k] / FEATURES[k][2]);
      return p;
    }
  }

  const mean = a => a.reduce((s, x) => s + x, 0) / a.length;
  const stdev = a => { if (a.length < 2) return 0; const m = mean(a); return Math.sqrt(a.reduce((s, x) => s + (x - m) ** 2, 0) / (a.length - 1)); };
  const r2 = x => Math.round(x * 100) / 100;

  function evaluate(history, current) {
    const res = {};
    for (const k in FEATURES) {
      const [naam, eenheid, , richting, dOr, dRood, tip] = FEATURES[k];
      let vals = history.filter(h => h.features && k in h.features).map(h => h.features[k]);
      let last = current[k], bron = "deze rit";
      if (last == null && vals.length) { last = vals[vals.length - 1]; bron = "vorige rit"; vals = vals.slice(0, -1); }
      const item = { naam, eenheid, laatste: last ?? null, bron, n_basis: vals.length, tip, status: "basislijn", basis: null, spreiding: null, afwijking: null };
      if (vals.length >= BASIS_MIN && last != null) {
        const m = mean(vals), sd = stdev(vals), dev = last - m, bad = richting ? dev * richting : Math.abs(dev);
        const limO = Math.max(3 * sd, dOr), limR = Math.max(4 * sd, dRood);
        Object.assign(item, { basis: r2(m), spreiding: r2(sd), afwijking: r2(dev), status: bad >= limR ? "rood" : bad >= limO ? "oranje" : "ok" });
      } else if (last == null) item.status = "geen data";
      res[k] = item;
    }
    const rv = res.rustspanning;
    if (rv.laatste != null) {
      if (rv.laatste < 12.0) rv.status = "rood";
      else if (rv.laatste < 12.2 && rv.status !== "rood") rv.status = "oranje";
    }
    return res;
  }

  window.HEALTH = { readMode06, mode06Margins, TripHealth, evaluate, FEATURES };
})();
