// Kern van de app: verbinding, poller, alarmen, rit, verbruik, gezondheid, DID-onderzoek, logging.
// Port van dashboard.py. ALLEEN LEZEN (zie elm.js en de native brug).
(function () {
  // ---------------- opslag (localStorage in de app) ----------------
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} },
  };
  const VEEPEAK = "66:1E:87:05:07:FA";

  const DEFAULT_CFG = {
    did_onderzoek: true,
    alarms: {
      coolant: { oranje: 106, rood: 112 }, coolant_koud: { na_s: 1200, onder: 70 },
      oil: { oranje: 120, rood: 130 }, iat: { oranje: 60, rood: 75 },
      boost_dev: { oranje: 20, rood: 40, duur_s: 3 }, rail_dev: { oranje: 10, rood: 20, duur_s: 2 },
      voltage: { laag_oranje: 12.8, laag_rood: 12.4, hoog_oranje: 14.9, hoog_rood: 15.2 },
      load: { oranje: 85, duur_s: 60 }, rpm: { oranje: 4000, rood: 4500 },
    },
  };
  const CFG = Object.assign({}, DEFAULT_CFG, store.get("cfg", {}));
  CFG.alarms = Object.assign({}, DEFAULT_CFG.alarms, (store.get("cfg", {}) || {}).alarms || {});

  const LOG_KEYS = ["rpm", "speed", "load", "coolant", "oil", "iat", "ambient", "voltage", "boost_cmd", "boost_act", "boost_dev",
    "rail_cmd", "rail_act", "rail_dev", "maf", "lambda", "fuel_lph", "l100", "throttle", "pedal", "baro", "runtime"];
  const RESEARCH_REF = ["rpm", "speed", "load", "coolant", "oil", "iat", "ambient", "voltage", "boost_act", "boost_cmd",
    "rail_act", "rail_cmd", "maf", "lambda", "throttle", "pedal", "baro", "runtime"];
  const LEVEL = { ok: 0, oranje: 1, rood: 2 };
  const DIESEL_AFR = 14.5, DIESEL_KG_L = 0.835;
  const pad = n => String(n).padStart(2, "0");
  const ts = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const tsSec = (d = new Date()) => ts(d) + ":" + pad(d.getSeconds());
  const fileStamp = (d = new Date()) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  // ---------------- toestand ----------------
  const S = {
    values: {}, updated: {}, alarms: {}, history: [], dtcAlert: [], healthEval: {},
    conn: { status: "niet verbonden", info: {}, error: "", multi: null, sessies: 0, onderbrekingen: 0 },
    trip: null, health: null, logfile: "", tank: store.get("tank", { factor: 1, sinds_tank_raw_l: 0, tankbeurten: [] }),
  };

  function newTrip() {
    S.trip = { start: Date.now(), afstand_km: 0, rijtijd_s: 0, stationair_s: 0, brandstof_l: 0, max: {},
      alarmen: { oranje: 0, rood: 0 }, _last: null, _prev: {}, _mode06: false };
    S.health = new HEALTH.TripHealth();
  }
  // Rit hervatten als de app minder dan 30 min geleden nog liep (bijv. door Android afgesloten)
  const live = store.get("trip_live", null);
  if (live && live.trip && Date.now() - (live.trip._last || 0) < 30 * 60000) {
    S.trip = live.trip; S.trip._last = null; S.health = new HEALTH.TripHealth(live.health);
  } else newTrip();

  function tripSummary(t) {
    return { start: ts(new Date(t.start)), _start: t.start, afstand_km: Math.round(t.afstand_km * 10) / 10,
      rijtijd_s: Math.round(t.rijtijd_s), stationair_s: Math.round(t.stationair_s), brandstof_l: Math.round(t.brandstof_l * 100) / 100,
      l100: t.afstand_km > 1 ? Math.round(t.brandstof_l / t.afstand_km * 1000) / 10 : null, max: t.max, alarmen: t.alarmen };
  }
  function saveTrip() {
    const t = S.trip;
    if (t.afstand_km < 0.1 && t.stationair_s < 60) return;
    const ritten = store.get("ritten", []).filter(r => r._start !== t.start);
    ritten.push(tripSummary(t));
    store.set("ritten", ritten.slice(-500));
    const f = S.health.features();
    if (Object.keys(f).length) {
      const h = store.get("health", []).filter(x => x._start !== t.start);
      h.push({ _start: t.start, start: ts(new Date(t.start)), features: f });
      store.set("health", h.slice(-500));
    }
  }
  function refreshHealth() {
    const hist = store.get("health", []).filter(h => h._start !== S.trip.start);
    S.healthEval = { kengetallen: HEALTH.evaluate(hist, S.health.features()), voortgang: S.health.progress(),
      mode06: HEALTH.mode06Margins(store.get("mode06", [])), ritten_basis: hist.length };
  }
  function logEvent(soort, tekst) {
    const ev = store.get("events", []); ev.push({ tijd: ts(), soort, tekst }); store.set("events", ev.slice(-300));
  }
  function storeMode06(tests) {
    const h = store.get("mode06", []); h.push({ tijd: ts(), tests }); store.set("mode06", h.slice(-200));
  }
  function addTankbeurt(liters) {
    const T = S.tank, raw = T.sinds_tank_raw_l || 0;
    const e = { tijd: ts(), liters, schatting_ruw_l: Math.round(raw * 100) / 100 };
    T.tankbeurten = T.tankbeurten || [];
    if (T.tankbeurten.length && raw > 5) e.factor = Math.round(liters / raw * 1000) / 1000;
    T.tankbeurten.push(e);
    const fs = T.tankbeurten.slice(-5).filter(b => b.factor);
    if (fs.length) T.factor = Math.round(fs.reduce((s, b) => s + b.liters * b.factor, 0) / fs.reduce((s, b) => s + b.liters, 0) * 1000) / 1000;
    T.sinds_tank_raw_l = 0;
    store.set("tank", T);
    return e;
  }

  // ---------------- alarmen ----------------
  const since = {};
  function dur(key, cond, need) {
    const now = Date.now();
    if (!cond) { delete since[key]; return false; }
    if (!(key in since)) since[key] = now;
    return now - since[key] >= need * 1000;
  }
  function evaluateAlarms(v) {
    const c = CFG.alarms, a = {};
    const thr = (k, val) => { if (val == null) return; const t = c[k]; a[k] = val >= t.rood ? "rood" : val >= t.oranje ? "oranje" : "ok"; };
    thr("coolant", v.coolant); thr("oil", v.oil); thr("iat", v.iat); thr("rpm", v.rpm);
    const k = c.coolant_koud;
    if (v.coolant != null && (v.runtime || 0) > k.na_s && (v.speed || 0) > 0 && v.coolant < k.onder) {
      a.coolant = "oranje"; a.coolant_reden = "Te koud na lang rijden: thermostaat?";
    }
    for (const key of ["boost_dev", "rail_dev"]) {
      const val = v[key], t = c[key];
      if (val == null) continue;
      a[key] = dur(key + "_r", val >= t.rood, t.duur_s) ? "rood" : dur(key + "_o", val >= t.oranje, t.duur_s) ? "oranje" : "ok";
    }
    const u = v.voltage, t = c.voltage;
    if (u != null && (v.rpm || 0) > 500)
      a.voltage = (u <= t.laag_rood || u >= t.hoog_rood) ? "rood" : (u <= t.laag_oranje || u >= t.hoog_oranje) ? "oranje" : "ok";
    else if (u != null) a.voltage = "ok";
    if (v.load != null) a.load = dur("load", v.load >= c.load.oranje, c.load.duur_s) ? "oranje" : "ok";
    if ("mil" in v) a.mil = v.mil ? "rood" : "ok";
    return a;
  }

  // ---------------- logbestanden (app-opslag, export via Instellingen) ----------------
  let csvBuf = "", resBuf = "", resFile = "", lastFlush = 0;
  function flushLogs(force) {
    if (!force && Date.now() - lastFlush < 10000) return;
    lastFlush = Date.now();
    try {
      if (csvBuf && S.logfile) { Native.appendFile(S.logfile, csvBuf); csvBuf = ""; }
      if (resBuf && resFile) { Native.appendFile(resFile, resBuf); resBuf = ""; }
    } catch (e) {}
  }
  const num = x => x == null ? "" : String(x).replace(".", ",");

  // ---------------- update ----------------
  let lastLog = 0, lastSave = 0;
  function update(nv) {
    const now = Date.now(), v = S.values;
    for (const k in nv) { v[k] = nv[k]; S.updated[k] = now; }
    if (v.boost_cmd != null && v.boost_act != null) v.boost_dev = Math.round((v.boost_cmd - v.boost_act) * 10) / 10;
    if ((v.rail_cmd || 0) > 200 && v.rail_act != null) v.rail_dev = Math.round(Math.abs(v.rail_cmd - v.rail_act) / v.rail_cmd * 1000) / 10;
    // Verbruiksschatting [schatting]: MAF / (14,5 x lambda); lambda afgekapt op 2,0 -> bovengrens bij lage last.
    if (v.maf != null && v.lambda && v.load != null) {
      const raw = v.load < 1 ? 0 : v.maf / (DIESEL_AFR * Math.min(v.lambda, 2)) / DIESEL_KG_L * 3.6;
      v.fuel_raw_lph = raw;
      v.fuel_lph = Math.round(raw * (S.tank.factor || 1) * 100) / 100;
      const spd = v.speed || 0;
      v.l100 = spd >= 5 ? Math.round(v.fuel_lph / spd * 1000) / 10 : null;
    }
    S.alarms = evaluateAlarms(v);
    // rit
    const t = S.trip;
    if (t._last != null) {
      const dt = Math.min((now - t._last) / 1000, 5), spd = v.speed || 0;
      t.afstand_km += spd * dt / 3600;
      if (spd > 0) t.rijtijd_s += dt; else if ((v.rpm || 0) > 300) t.stationair_s += dt;
      if (v.fuel_raw_lph != null) {
        const rawL = v.fuel_raw_lph * dt / 3600;
        t.brandstof_l += rawL * (S.tank.factor || 1);
        S.tank.sinds_tank_raw_l = (S.tank.sinds_tank_raw_l || 0) + rawL;
      }
    }
    t._last = now;
    for (const k of ["coolant", "oil", "iat", "speed", "rpm", "boost_act", "load"])
      if (v[k] != null) t.max[k] = Math.max(t.max[k] ?? v[k], v[k]);
    for (const k in S.alarms) {
      const lvl = S.alarms[k];
      if ((lvl === "oranje" || lvl === "rood") && t._prev[k] !== lvl) t.alarmen[lvl]++;
      t._prev[k] = lvl;
    }
    // 1 Hz: log, gezondheid, grafiekgeschiedenis
    if (now - lastLog >= 1000) {
      lastLog = now;
      if (!S.logfile) {
        S.logfile = "rit_" + fileStamp() + ".csv";
        csvBuf += "tijd;" + LOG_KEYS.join(";") + ";alarm\n";
      }
      const worst = Object.keys(S.alarms).filter(k => !k.endsWith("reden")).map(k => S.alarms[k])
        .reduce((a, b) => (LEVEL[b] || 0) > (LEVEL[a] || 0) ? b : a, "ok");
      csvBuf += tsSec() + ";" + LOG_KEYS.map(k => num(v[k])).join(";") + ";" + worst + "\n";
      S.health.sample(v, now);
      const h = { t: now }; for (const k of LOG_KEYS) h[k] = v[k] ?? null;
      S.history.push(h); if (S.history.length > 900) S.history.shift();
      flushLogs(false);
    }
    if (now - lastSave > 30000) {
      lastSave = now;
      saveTrip(); store.set("tank", S.tank); refreshHealth();
      store.set("trip_live", { trip: S.trip, health: S.health });
    }
  }

  // ---------------- poller ----------------
  const FAST = ["0C", "0D", "04"], MEDIUM = ["70", "6D", "66", "24"], SLOW = ["05", "5C", "46", "42"],
    SLOW_SINGLE = ["68", "49", "11", "33"], VERY_SLOW = ["01", "1F"];
  let multi = null;

  async function q(pids) {
    if (pids.length > 1 && multi !== false) {
      const data = await ELM.query("01" + pids.join(""));
      const got = data ? PIDS.splitResponse(data) : {};
      if (multi === null && Object.keys(got).length) {
        multi = pids.every(p => parseInt(p, 16) in got);
        S.conn.multi = multi;
      }
      if (multi) return got;
    }
    const out = {};
    for (const p of pids) { const d = await ELM.query("01" + p); if (d) Object.assign(out, PIDS.splitResponse(d)); }
    return out;
  }

  let researchI = 0;
  async function researchStep() {
    const list = window.DID_KANDIDATEN || [];
    if (!CFG.did_onderzoek || !list.length) return;
    const h = list[researchI++ % list.length], did = parseInt(h, 16);
    const d = await ELM.readDid(did);
    if (!d) return;
    if (!resFile) { resFile = "did_onderzoek_" + fileStamp() + ".csv"; resBuf += "t;did;ruw;" + RESEARCH_REF.join(";") + "\n"; }
    const raw = d.reduce((a, b) => a * 256 + b, 0);
    resBuf += (Date.now() / 1000).toFixed(2) + ";" + h + ";" + raw + ";" + RESEARCH_REF.map(k => S.values[k] ?? "").join(";") + "\n";
  }

  async function autoDtcCheck() {
    const found = [];
    for (const [mode, resp, soort] of [["07", 0x47, "pending"], ["03", 0x43, "opgeslagen"]]) {
      try { const d = await ELM.query(mode); if (d) DTC.decodeDtcs(d, resp).forEach(c => found.push([c, soort])); } catch (e) {}
    }
    const known = new Set(S.dtcAlert.map(a => a.code + "|" + a.soort));
    for (const [code, soort] of found) {
      if (!known.has(code + "|" + soort)) {
        const ex = DTC.explain(code);
        S.dtcAlert.push({ code, soort, omschrijving: ex.omschrijving, tip: ex.tip, tijd: ts() });
        logEvent("foutcode", `${soort} ${code}: ${ex.omschrijving}`);
      }
    }
    const cur = new Set(found.map(([c, s]) => c + "|" + s));
    S.dtcAlert = S.dtcAlert.filter(a => cur.has(a.code + "|" + a.soort));
  }

  let polling = false;
  async function pollLoop() {
    polling = true;
    let cycle = 0, nodata = 0, tSlow = 0, tVslow = 0, tDtc = Date.now() - 540000;
    while (polling) {
      const got = await q(FAST);
      nodata = Object.keys(got).length ? 0 : nodata + 1;
      if (nodata >= 5) throw new Error("ECU antwoordt niet meer (contact uit?)");
      if (cycle % 2 === 0) Object.assign(got, await q(MEDIUM));
      const now = Date.now();
      if (now - tSlow > 5000) { Object.assign(got, await q(SLOW), await q(SLOW_SINGLE)); tSlow = now; }
      if (now - tVslow > 10000) { Object.assign(got, await q(VERY_SLOW)); tVslow = now; }
      update(PIDS.decode(got));
      if (now - tDtc > 600000) { tDtc = now; await autoDtcCheck(); }
      if (!S.trip._mode06 && (S.values.runtime || 0) >= 1200 && (S.values.rpm || 0) > 400) {
        S.trip._mode06 = true;
        try { storeMode06(await HEALTH.readMode06()); } catch (e) {}
      }
      await researchStep();
      cycle++;
    }
  }

  // ---------------- verbinding met automatisch herverbinden ----------------
  let want = false, waitState = null, firstInit = true;
  ELM.onState((s, m) => {
    if (waitState && (s === "verbonden" || s === "fout")) { const w = waitState; waitState = null; w(s === "verbonden" ? null : m); }
    if (s === "verbroken" || s === "fout") { polling = false; ELM.reset(); }
  });

  async function session() {
    const addr = store.get("dev", VEEPEAK);
    S.conn.status = "verbinden"; S.conn.error = "";
    const err = await new Promise(res => { waitState = res; Native.setPause(2000); Native.connectWith(addr, "secure"); });
    if (err) throw new Error(err);
    const info = await ELM.init({ skipATZ: !firstInit });
    if (firstInit) {
      try { const vin = await ELM.query("0902", 5000); if (vin) info.vin = String.fromCharCode(...vin.slice(3).filter(b => b > 32 && b < 127)); } catch (e) {}
    }
    firstInit = false;
    S.conn.info = info; S.conn.status = "verbonden"; S.conn.sessies++;
    await pollLoop();
  }

  async function run() {
    if (want) return;
    want = true;
    while (want) {
      try { await session(); }
      catch (e) {
        if (S.conn.status === "verbonden") S.conn.onderbrekingen++;
        S.conn.status = "niet verbonden"; S.conn.error = e.message;
      }
      polling = false; flushLogs(true);
      if (!want) break;
      try { Native.disconnect(); } catch (e) {}
      await sleep(3000);
    }
    S.conn.status = "niet verbonden";
  }
  function stop() { want = false; polling = false; try { Native.disconnect(); } catch (e) {} flushLogs(true); }

  // ---------------- acties vanuit de interface ----------------
  async function readDtcs() {
    const res = {};
    for (const [mode, resp, name] of [["03", 0x43, "opgeslagen"], ["07", 0x47, "pending"], ["0A", 0x4A, "permanent"]]) {
      try {
        const d = await ELM.query(mode);
        res[name] = d ? DTC.decodeDtcs(d, resp).map(DTC.explain) : [];
        if (name === "permanent" && d === null) res.permanent_nb = true;
      } catch (e) { res[name] = []; res[name + "_fout"] = e.message; }
    }
    const st = PIDS.decode(PIDS.splitResponse((await ELM.query("0101")) || []));
    res.mil = st.mil; res.readiness = st.readiness || {}; res.freeze = {};
    if (res.opgeslagen.length) {
      try {
        const d = await ELM.query("020200");
        if (d && d.length >= 5) {
          res.freeze_code = DTC.decodeDtcs([0x43, 1, d[3], d[4]], 0x43);
          const ff = {};
          for (const p of ["04", "05", "0C", "0D", "11", "1F", "33", "42", "46", "5C"]) {
            const dd = await ELM.query("02" + p + "00");
            if (dd) Object.assign(ff, PIDS.splitResponse(dd, 0x42, true));
          }
          const dec = PIDS.decode(ff);
          for (const k in dec) if (PIDS.PIDS[k]) res.freeze[PIDS.PIDS[k][1]] = [dec[k], PIDS.PIDS[k][2]];
        }
      } catch (e) { res.freeze_fout = e.message; }
    }
    return res;
  }
  async function clearDtcs(confirm) {
    if ((S.values.rpm || 0) > 0) throw new Error("Motor draait. Zet de motor uit (contact aan) en probeer opnieuw.");
    return ELM.clearDtcs(confirm);
  }
  async function mode06() { const t = await HEALTH.readMode06(); storeMode06(t); return t; }
  function tripReset() { saveTrip(); newTrip(); store.set("trip_live", null); }
  function setConfig(cfg) { Object.assign(CFG, cfg); store.set("cfg", { did_onderzoek: CFG.did_onderzoek, alarms: CFG.alarms }); }

  // Afsluiten/naar achtergrond: alles wegschrijven
  document.addEventListener("visibilitychange", () => { if (document.hidden) { flushLogs(true); saveTrip(); store.set("tank", S.tank); } });

  window.APP = { S, CFG, DEFAULT_CFG, store, run, stop, readDtcs, clearDtcs, mode06, tripReset, addTankbeurt, setConfig,
    refreshHealth, tripSummary, logEvent, flushLogs };
})();
