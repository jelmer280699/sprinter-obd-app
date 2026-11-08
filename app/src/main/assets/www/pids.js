// Mode 01-PID's die deze ECU ondersteunt (Fase 1), SAE J1979-formules. Port van obd/pids.py.
(function () {
  const u16 = (a, b) => a * 256 + b;
  const pct = d => Math.round(d[0] * 1000 / 255) / 10;
  const temp = d => d[0] - 40;
  const r1 = x => Math.round(x * 10) / 10;

  function monitor(d) {
    const [a, b, c, e] = d;
    const names = ["NMHC-katalysator", "NOx/SCR", null, "Turbodruk", null, "Uitlaatgassensor", "Roetfilter (DPF)", "EGR/VVT"];
    const mon = {};
    names.forEach((n, bit) => { if (n && (c & (1 << bit))) mon[n] = (e & (1 << bit)) ? "niet klaar" : "klaar"; });
    [[0, "Verbranding/misfire"], [1, "Brandstofsysteem"], [2, "Componenten"]].forEach(([bit, n]) => {
      if (b & (1 << bit)) mon[n] = (b & (1 << (bit + 4))) ? "niet klaar" : "klaar";
    });
    return { mil: !!(a & 0x80), dtc_count: a & 0x7f, readiness: mon };
  }

  // sleutel: [pid, naam, eenheid, aantal databytes, decoder]
  const PIDS = {
    load: [0x04, "Motorbelasting", "%", 1, pct],
    coolant: [0x05, "Koelvloeistof", "°C", 1, temp],
    rpm: [0x0C, "Toerental", "rpm", 2, d => Math.round(u16(d[0], d[1]) / 4)],
    speed: [0x0D, "Snelheid", "km/u", 1, d => d[0]],
    throttle: [0x11, "Smoorklep", "%", 1, pct],
    runtime: [0x1F, "Looptijd motor", "s", 2, d => u16(d[0], d[1])],
    dist_mil: [0x21, "Afstand met MIL", "km", 2, d => u16(d[0], d[1])],
    lambda: [0x24, "Lambda B1S1 (afgekapt op 2,0)", "λ", 4, d => Math.round(u16(d[0], d[1]) * 2 / 65536 * 1000) / 1000],
    dist_clr: [0x31, "Afstand sinds wissen", "km", 2, d => u16(d[0], d[1])],
    baro: [0x33, "Barometer", "kPa", 1, d => d[0]],
    voltage: [0x42, "Accuspanning (ECU)", "V", 2, d => Math.round(u16(d[0], d[1])) / 1000],
    ambient: [0x46, "Buitentemperatuur", "°C", 1, temp],
    pedal: [0x49, "Gaspedaal", "%", 1, pct],
    oil: [0x5C, "Motorolie", "°C", 1, temp],
    maf: [0x66, "Luchtmassa (MAF)", "g/s", 5, d => (d[0] & 1) ? Math.round(u16(d[1], d[2]) / 32 * 100) / 100 : null],
    iat: [0x68, "Inlaatlucht", "°C", 7, d => (d[0] & 1) ? d[1] - 40 : null],
    rail_cmd: [0x6D, "Raildruk gevraagd", "bar", 11, d => Math.round(u16(d[1], d[2]) / 10)],
    rail_act: [0x6D, "Raildruk werkelijk", "bar", 11, d => Math.round(u16(d[3], d[4]) / 10)],
    boost_cmd: [0x70, "Turbodruk gevraagd (abs)", "kPa", 10, d => (d[0] & 1) ? r1(u16(d[1], d[2]) * 0.03125) : null],
    boost_act: [0x70, "Turbodruk werkelijk (abs)", "kPa", 10, d => (d[0] & 2) ? r1(u16(d[3], d[4]) * 0.03125) : null],
    status: [0x01, "Monitorstatus", "", 4, monitor],
  };

  const PID_LEN = { 0x13: 1, 0x1C: 1, 0x30: 1, 0x4A: 1, 0x51: 1, 0x6C: 5, 0x85: 10, 0x88: 13, 0x92: 2 };
  for (const k in PIDS) PID_LEN[PIDS[k][0]] = PIDS[k][3];

  // '41 0C xx xx 0D xx ...' (ook multi-PID) -> {pid: [bytes]}; frame=true voor Mode 02
  function splitResponse(data, modeResp = 0x41, frame = false) {
    const out = {};
    if (!data || data[0] !== modeResp) return out;
    let i = 1;
    while (i < data.length) {
      const pid = data[i++];
      if (frame) i++;
      const n = PID_LEN[pid];
      if (n === undefined || i + n > data.length) break;
      out[pid] = data.slice(i, i + n);
      i += n;
    }
    return out;
  }

  function decode(pidBytes) {
    const vals = {};
    for (const key in PIDS) {
      const [pid, , , , f] = PIDS[key];
      if (!(pid in pidBytes)) continue;
      let v;
      try { v = f(pidBytes[pid]); } catch (e) { continue; }
      if (v && typeof v === "object") Object.assign(vals, v);
      else if (v !== null && v !== undefined && !Number.isNaN(v)) vals[key] = v;
    }
    return vals;
  }

  window.PIDS = { PIDS, PID_LEN, splitResponse, decode };
})();
