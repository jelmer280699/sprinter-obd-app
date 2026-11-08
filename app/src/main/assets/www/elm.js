// ELM327-laag (alleen lezen) bovenop de Native Bluetooth-brug van de app.
// De native kant blokkeert al alles behalve AT/lees-modes; dit is de tweede laag.
(function () {
  const READ_ONLY = /^(AT(?!PP)[A-Z0-9@ .]*|(01|02|03|07|09|0A)[0-9A-F]*|06[0-9A-F]{2}|22[0-9A-F]{4})$/;
  let buf = "", waiter = null, chain = Promise.resolve();
  const listeners = [];

  window.onSerialData = function (chunk) {
    buf += chunk;
    if (waiter && buf.includes(">")) { const w = waiter; waiter = null; w.resolve(buf); buf = ""; }
  };
  window.onSerialState = function (state, msg) {
    if ((state === "verbroken" || state === "fout") && waiter) { const w = waiter; waiter = null; w.reject(new Error(msg || state)); }
    listeners.forEach(f => { try { f(state, msg); } catch (e) {} });
  };

  const dbg = t => { if (window.ELM && ELM.debug) try { ELM.debug(t); } catch (e) {} };
  function rawSend(cmd, ms) {
    return new Promise((resolve, reject) => {
      buf = "";
      waiter = { resolve, reject };
      dbg("TX " + cmd);
      const t = setTimeout(() => { if (waiter) { waiter = null; dbg("TIMEOUT " + cmd + " (ontvangen: " + JSON.stringify(buf) + ")"); reject(new Error("Timeout op " + cmd)); } }, ms);
      const wrap = f => v => { clearTimeout(t); if (typeof v === "string") dbg("RX " + v.replace(/[\r\n]+/g, " | ").replace(/>/g, "").trim()); f(v); };
      waiter.resolve = wrap(resolve); waiter.reject = wrap(reject);
      if (!Native.write(cmd)) { clearTimeout(t); waiter = null; reject(new Error("Niet verzonden (geblokkeerd of niet verbonden): " + cmd)); }
    });
  }

  // Commando's strikt na elkaar (de ELM kan maar één ding tegelijk)
  function send(cmd, ms = 5000) {
    const c = cmd.replace(/\s/g, "").toUpperCase();
    if (!READ_ONLY.test(c)) return Promise.reject(new Error("Geblokkeerd (alleen lezen): " + c));
    const p = chain.then(() => rawSend(c, ms));
    chain = p.catch(() => {});
    return p;
  }

  // Antwoordtekst -> bytes (single- en multi-frame), null bij NO DATA
  function parseBytes(text) {
    const lines = text.replace(/>/g, "").split(/[\r\n]+/).map(l => l.trim()).filter(l => l && !l.startsWith("SEARCHING"));
    if (!lines.length) return null;
    const joined = lines.join(" ").toUpperCase();
    if (joined.includes("NO DATA")) return null;
    for (const e of ["UNABLE TO CONNECT", "CAN ERROR", "BUS INIT", "BUS ERROR", "STOPPED", "BUFFER FULL", "DATA ERROR"])
      if (joined.includes(e)) throw new Error(joined);
    if (joined.trim() === "?") throw new Error("ELM begrijpt commando niet");
    const multi = lines.some(l => /^[0-9A-F]:/.test(l));
    let total = null, out = [];
    for (let l of lines) {
      if (multi) {
        const m = l.match(/^([0-9A-F]):\s*(.*)$/);
        if (!m) { if (/^[0-9A-F]{3}$/.test(l)) total = parseInt(l, 16); continue; }
        l = m[2];
      }
      for (const b of l.split(/\s+/)) if (/^[0-9A-F]{2}$/.test(b)) out.push(parseInt(b, 16));
    }
    return total !== null ? out.slice(0, total) : out;
  }

  async function query(cmd, ms) { return parseBytes(await send(cmd, ms)); }

  async function init(opts = {}) {
    // ATZ = volledige reset van de ELM-chip; ATD = alleen standaardinstellingen (geen herstart).
    if (opts.skipATZ) await send("ATD", 3000); else await send("ATZ", 4000);
    for (const c of ["ATE0", "ATL0", "ATS1", "ATH0", "ATAT1"]) await send(c, 2000);
    const elm = (await send("ATI", 2000)).replace(/>/g, "").trim();
    await send("ATSP6", 2000);
    let r = await send("0100", 10000);
    if (!r.includes("41 00")) {
      await send("ATSP0", 2000);
      r = await send("0100", 15000);
      if (!r.includes("41 00")) throw new Error("ECU antwoordt niet (contact aan?)");
    }
    // Getest op deze bus: Mode 01 alleen functioneel (7DF); alleen antwoorden van de motor-ECU (7E8) accepteren.
    await send("ATSH7DF", 2000);
    await send("ATCRA7E8", 2000);
    const proto = (await send("ATDP", 2000)).replace(/>/g, "").trim();
    return { elm, proto };
  }

  window.ELM = { send, query, parseBytes, init, onState: f => listeners.push(f) };
})();
