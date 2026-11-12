// NEP-DIENST voor testen in een gewone browser. Wordt alleen geladen als window.Native ontbreekt
// (dus nooit in de echte app). Bootst de toestand van ObdService na.
(function () {
  const t0 = Date.now(), hist = [], data = { ritten: "[]", health: "[]", mode06: "[]", events: "[]", tank: '{"factor":1,"sinds_tank_raw_l":0,"tankbeurten":[]}' };
  let km = 0, last = Date.now();
  function values() {
    const t = (Date.now() - t0) / 1000, drive = (Math.sin(t / 20) + 1) / 2, spd = Math.round(drive * 90);
    const now = Date.now(); km += spd * (now - last) / 3600000; last = now;
    const bc = Math.round((100 + drive * 110) * 10) / 10, ba = Math.round((99 + drive * 110) * 10) / 10;
    const rail = Math.round(280 + drive * 900), maf = Math.round((7.75 + drive * 40) * 100) / 100;
    return { rpm: Math.round(760 + drive * 1800), speed: spd, load: Math.round(17 + drive * 60), coolant: Math.round(63 + Math.min(t / 6, 30)),
      oil: Math.round(59 + Math.min(t / 6, 30)), iat: 23, ambient: 20, voltage: 13.81, boost_cmd: bc, boost_act: ba, boost_dev: Math.round((bc - ba) * 10) / 10,
      rail_cmd: rail, rail_act: rail - 2, rail_dev: 0.2, maf, lambda: 2 - drive * 0.7, fuel_lph: Math.round(maf / (14.5 * (2 - drive * 0.7)) / 0.835 * 360) / 100,
      l100: spd >= 5 ? Math.round(maf / (14.5 * (2 - drive * 0.7)) / 0.835 * 3.6 / spd * 1000) / 10 : null, mil: false, dtc_count: 0, runtime: Math.round(t) };
  }
  setInterval(() => { const v = values(); hist.push({ t: Date.now(), ...v }); if (hist.length > 900) hist.shift(); }, 1000);
  const reply = (id, ok, obj) => setTimeout(() => window.onAction && window.onAction(id, ok, JSON.stringify(obj)), 300);
  window.Native = {
    hasPermission: () => true, requestPermission() {}, serviceRunning: () => true, startService() {}, stopService() {},
    getState() {
      const v = values(), age = {}; for (const k in v) age[k] = 0.4;
      return JSON.stringify({ values: v, age, alarms: { coolant: "ok", oil: "ok", iat: "ok", rpm: "ok", boost_dev: "ok", rail_dev: "ok", voltage: "ok", load: "ok", mil: "ok" },
        conn: { status: "verbonden", error: "", info: { elm: "ELM327 v1.5 (DEMO)", proto: "DEMO", vin: "DEMO" }, multi: true, sessies: 1, onderbrekingen: 0 },
        tripActive: true, trip: { start: "demo", _start: t0, afstand_km: Math.round(km * 10) / 10, rijtijd_s: (Date.now() - t0) / 1000, stationair_s: 0,
          brandstof_l: km * 0.11, l100: null, max: { coolant: v.coolant, oil: v.oil, iat: 23, rpm: 2560, boost_act: 209 }, alarmen: { oranje: 0, rood: 0 } },
        health_features: {}, health_progress: { koeling: 0.2, turbo: 0.5 }, dtcAlert: [], logfile: "rit_DEMO.csv", tank: JSON.parse(data.tank),
        cfg: { did_onderzoek: true, alarms: { coolant: { oranje: 106, rood: 112 } } } });
    },
    getHistory: () => JSON.stringify(hist),
    getData: n => data[n] ?? "null",
    migrate: () => false,
    action(id, name, arg) {
      if (name === "readDtcs") return reply(id, true, { opgeslagen: [], pending: [], permanent: [], permanent_nb: true, mil: false, readiness: { "NOx/SCR": "niet klaar", Turbodruk: "klaar" }, freeze: {} });
      if (name === "mode06") return reply(id, true, [{ mid: "85", naam: "Turbodruk B1", tid: "B1", waarde: -87, min: -32768, max: 483, ok: true, uitgevoerd: true }]);
      if (name === "clearDtcs") return reply(id, false, { fout: "Motor draait. Zet de motor uit (contact aan) en probeer opnieuw." });
      if (name === "addTank") { const t = JSON.parse(data.tank); t.tankbeurten.push({ tijd: "nu", liters: +arg }); data.tank = JSON.stringify(t); return reply(id, true, { tijd: "nu", liters: +arg }); }
      return reply(id, true, {});
    },
    bondedDevices: () => JSON.stringify([{ name: "VEEPEAK (DEMO)", address: "66:1E:87:05:07:FA" }]),
    batteryOk: () => false, askBattery() {},
    listFiles: () => JSON.stringify([{ name: "rit_DEMO.csv", size: 4096, modified: Date.now(), lopend: true },
      { name: "rit_20261109_090228.csv", size: 89416, modified: Date.now() - 864e5, exported: true },
      { name: "did_onderzoek_20261110_120000.csv", size: 300000, modified: Date.now() - 3600e3, exported: !!window.__exp }]),
    exportPending: () => { window.__exp = true; return "1 geëxporteerd"; },
    deleteFile: () => true, exportFile: n => "Downloads/SprinterOBD/" + n + " (demo)", saveFile: n => "Downloads/SprinterOBD/" + n + " (demo)",
  };
})();
