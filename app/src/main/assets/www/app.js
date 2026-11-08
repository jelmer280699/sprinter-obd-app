// Interface-kant: leest de toestand van de meetdienst (ObdService, Kotlin) en stuurt acties door.
// Het meten, loggen en de ritdetectie gebeuren in de dienst, ook met scherm uit.
(function () {
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
  const S = { values: {}, age: {}, alarms: {}, conn: { status: "opstarten", error: "" }, trip: null, tripActive: false,
    tank: { factor: 1, tankbeurten: [] }, dtcAlert: [], logfile: "", healthEval: {}, history: [], cfg: DEFAULT_CFG,
    health_features: {}, health_progress: {} };

  const store = {
    get(name, d) { try { const v = JSON.parse(Native.getData(name)); return v == null ? d : v; } catch (e) { return d; } },
  };

  function poll() {
    try {
      const st = JSON.parse(Native.getState());
      if (st && st.values) Object.assign(S, st);
      else S.conn = { status: Native.serviceRunning() ? "opstarten" : "gestopt", error: "" };
    } catch (e) {}
  }
  setInterval(poll, 400); poll();
  function pollHistory() { try { S.history = JSON.parse(Native.getHistory()); } catch (e) {} }

  // ---- acties met promise (resultaat komt via window.onAction) ----
  let seq = 0; const pending = {};
  window.onAction = function (id, ok, json) {
    const p = pending[id]; if (!p) return; delete pending[id];
    let r; try { r = JSON.parse(json); } catch (e) { r = json; }
    ok ? p.res(r) : p.rej(new Error((r && r.fout) || "mislukt"));
  };
  function action(name, arg = "") {
    return new Promise((res, rej) => { const id = "a" + (++seq); pending[id] = { res, rej }; Native.action(id, name, String(arg)); });
  }

  // ---- gebeurtenissen van de dienst ----
  const listeners = [];
  window.onEvent = function (s, m) { listeners.forEach(f => { try { f(s, m); } catch (e) {} }); };
  window.onServiceReady = function () { poll(); migrate(); };

  // Eenmalig: gegevens uit de vorige app-versie (localStorage) naar de dienst overzetten
  function migrate() {
    try {
      if (localStorage.getItem("gemigreerd")) return;
      for (const k of ["ritten", "health", "mode06", "events", "tank"]) {
        const v = localStorage.getItem(k); if (v) Native.migrate(k, v);
      }
      localStorage.setItem("gemigreerd", "1");
    } catch (e) {}
  }

  function refreshHealth() {
    const start = S.trip ? S.trip._start : -1;
    const hist = store.get("health", []).filter(h => h._start !== start);
    S.healthEval = { kengetallen: HEALTH.evaluate(hist, S.health_features || {}), voortgang: S.health_progress || {},
      mode06: HEALTH.mode06Margins(store.get("mode06", [])), ritten_basis: hist.length };
  }
  setInterval(refreshHealth, 30000); setTimeout(refreshHealth, 1500);

  window.APP = {
    S, DEFAULT_CFG, store, poll, pollHistory, refreshHealth,
    get CFG() { return S.cfg || DEFAULT_CFG; },
    onEvent: f => listeners.push(f),
    run: () => Native.startService(),
    stop: () => Native.stopService(),
    readDtcs: () => action("readDtcs"),
    clearDtcs: c => action("clearDtcs", c).then(r => r.ok),
    mode06: () => action("mode06"),
    tripReset: () => action("tripReset"),
    addTankbeurt: l => action("addTank", l),
    setConfig: cfg => action("setCfg", JSON.stringify(cfg)),
    setDevice: a => action("setDevice", a),
  };
})();
