// NEP-DONGLE voor testen in een gewone browser. Wordt alleen geladen als window.Native ontbreekt
// (dus nooit in de echte app). Antwoorden gebaseerd op de echte Fase 1-uitvoer, met wat variatie.
(function () {
  const t0 = Date.now(), files = {};
  const hx = n => n.toString(16).toUpperCase().padStart(2, "0");
  const w16 = n => hx((n >> 8) & 255) + " " + hx(n & 255);
  function answer(c) {
    const t = (Date.now() - t0) / 1000, drive = (Math.sin(t / 20) + 1) / 2;
    const rpm = Math.round((760 + drive * 1800) * 4), cool = Math.round(63 + Math.min(t / 6, 30)) + 40;
    const bc = Math.round((100 + drive * 110) / 0.03125), ba = Math.round((99 + drive * 110 - (t % 120 > 60 && t % 120 < 70 ? 25 : 0)) / 0.03125);
    const rail = Math.round((280 + drive * 900) * 10), maf = Math.round((7.75 + drive * 40) * 32), lam = Math.min(65535, Math.round((2 - drive * 0.7) * 32768));
    const r = {
      "0C": "0C " + w16(rpm), "0D": "0D " + hx(Math.round(drive * 90)), "04": "04 " + hx(Math.round((17 + drive * 60) * 2.55)),
      "05": "05 " + hx(cool), "5C": "5C " + hx(cool - 4), "46": "46 3C", "42": "42 35 F4",
      "70": "70 07 " + w16(bc) + " " + w16(ba) + " 00 00 00 00 01", "6D": "6D 03 " + w16(rail) + " " + w16(rail - 3) + " 00 00 00 00 00 00",
      "66": "66 01 " + w16(maf) + " 00 00", "24": "24 " + w16(lam) + " 13 D8", "68": "68 01 3F 00 00 00 00 00",
      "00": "00 98 18 A0 13", "49": "49 0F", "11": "11 DC", "33": "33 64", "01": "01 00 0E EA 22", "1F": "1F " + w16(Math.round(t)),
    };
    if (c.startsWith("AT")) return c === "ATI" ? "ELM327 v1.5 (DEMO)" : c === "ATDP" ? "DEMO" : "OK";
    if (c === "03") return "43 00"; if (c === "07") return "47 00"; if (c === "0A") return "NO DATA"; if (c === "04") return "44";
    if (c === "0902") return "49 02 01 44 45 4D 4F";
    if (c === "0600") return "46 00 60 00 00 00";
    if (c === "0602") return "46 02 01 0A 01 2C 00 64 03 E8";
    if (c === "0603") return "46 03 05 0A 00 32 00 00 00 28";
    if (c.startsWith("22")) return c === "222000" ? "62 20 00 " + w16(1000 + Math.round(drive * 500)) : "7F 22 31";
    if (c.startsWith("01")) {
      const parts = []; for (let i = 2; i < c.length; i += 2) if (r[c.slice(i, i + 2)]) parts.push(r[c.slice(i, i + 2)]);
      return parts.length ? "41 " + parts.join(" ") : "NO DATA";
    }
    return "NO DATA";
  }
  const reply = c => setTimeout(() => window.onSerialData && window.onSerialData(answer(c) + "\r\r>"), 40);
  const state = (a, b) => setTimeout(() => window.onSerialState && window.onSerialState(a, b), 50);
  window.Native = {
    hasPermission: () => true, requestPermission() {},
    bondedDevices: () => JSON.stringify([{ name: "VEEPEAK (DEMO)", address: "66:1E:87:05:07:FA" }]),
    setPause() {}, connect(a) { state("verbonden", a); }, connectWith(a) { state("verbonden", a); },
    disconnect() { state("verbroken", "Losgekoppeld"); },
    write(c) { reply(c); return true; }, clearDtc(x) { if (x !== "WISSEN") return false; reply("04"); return true; },
    appendFile(n, t) { files[n] = (files[n] || "") + t; return true; },
    listFiles: () => JSON.stringify(Object.keys(files).map(n => ({ name: n, size: files[n].length, modified: Date.now() }))),
    deleteFile(n) { delete files[n]; return true; }, exportFile: n => "Downloads/SprinterOBD/" + n + " (demo)",
    saveFile: n => "Downloads/SprinterOBD/" + n + " (demo)",
  };
  window.__MOCK_FILES = files;
})();
