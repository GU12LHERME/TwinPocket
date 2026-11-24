package com.twinsanityevolved.game;

import java.util.List;

// The Ferramentas tab's log reading (android/game/.../ToolsCore.java) on a plain JVM: the pace / perf lines the game
// writes, the session history's lines, the failure lines.
public final class TestTools {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) failures++; }

    public static void main(String[] a) {
        String log =
            "[2026-11-23 10:00:00] Twinsanity Pocket native library loaded: process x:game\n"
            + "[2026-11-23 10:00:01] [gl.es] GL_RENDERER Adreno (TM) 740\n"
            + "[2026-11-23 10:00:10] [te.gfx] pace: presents 1-600 at 59.9 fps, longest gap 20.1 ms, 3 of 599 gaps over a field; "
            + "a frame 16.69 ms = submit 2.00 + present 3.00 + the rest 11.69; builds 1.00 ms (waited on 0.10)\n"
            + "[2026-11-23 10:00:10] [te.android] perf: presents 1-600: CPU 80% of one core (10.0% of 8 cores), 13.30 ms of CPU a present; peak memory 400 MB\n"
            + "[2026-11-23 10:00:20] [te.gfx] pace: presents 601-1200 at 50.0 fps, longest gap 45.0 ms, 30 of 599 gaps over a field; "
            + "a frame 20.00 ms = submit 2.00 + present 3.00 + the rest 15.00; builds 1.00 ms (waited on 0.10)\n"
            + "[2026-11-23 10:00:20] [te.android] perf: presents 601-1200: CPU 100% of one core (12.5% of 8 cores), 20.00 ms of CPU a present; peak memory 420 MB\n"
            + "[2026-11-23 10:01:40] the native program ended normally: returned 0\n";
        ToolsCore.Summary s = ToolsCore.summarize(log, "finished 12 returned 0 (last stage: x)");
        check(s.count() == 2, "two windows (" + s.count() + ")");
        check(Math.abs(s.avg(ToolsCore.FPS) - 54.95) < 0.01, "average fps " + s.avg(ToolsCore.FPS));
        check(s.min(ToolsCore.FPS) == 50.0, "lowest window fps");
        check(s.max(ToolsCore.GAP_MS) == 45.0, "worst gap");
        check(s.avg(ToolsCore.CPU) == 90.0, "average CPU of one core (the perf line joined to its pace line)");
        check(s.max(ToolsCore.MEMORY) == 420.0, "peak memory");
        check("finished".equals(s.endState), "end state from the stage file");
        check(s.renderer != null && s.renderer.contains("Adreno"), "renderer line");
        check(ToolsCore.seconds(s.start, s.end) == 100, "duration from the time stamps (" + ToolsCore.seconds(s.start, s.end) + ")");
        check(ToolsCore.duration(100).equals("1 min 40 s"), "duration in words");

        ToolsCore.Summary none = ToolsCore.summarize(null, null);
        check(none.count() == 0 && none.avg(ToolsCore.FPS) < 0 && ToolsCore.num(none.avg(ToolsCore.FPS), 1).equals("—"), "no log: no figures");

        String line = ToolsCore.sessionLine(s, "0.26.0", "2", "60", "bilinear", "original", "2026-11-23 10:00:00#1234");
        String hist = ToolsCore.trimmed(null, line, 50);
        List<String[]> rows = ToolsCore.sessions(hist);
        check(rows.size() == 1 && rows.get(0)[1].equals("0.26.0") && rows.get(0)[4].equals("55.0"), "a history line written and read back");
        check(ToolsCore.hasSession(hist, "2026-11-23 10:00:00#1234"), "a session found by its key");
        check(!ToolsCore.hasSession(hist, "other"), "another key not found");
        String many = hist;
        for (int i = 0; i < 60; i++) many = ToolsCore.trimmed(many, line.replace("#1234", "#" + i), 50);
        check(ToolsCore.sessions(many).size() == 50, "the history kept to 50 sessions");
        check(ToolsCore.sessions(many).get(49)[15].endsWith("#59"), "the newest session last");

        // the 1.4.0 report (P0): frame times, the GPU's time, the settings and the telemetry samples
        String p0 =
            "[2026-11-23 11:00:00] Twinsanity Pocket native library loaded: process x:game\n"
            + "[2026-11-23 11:00:10] [te.gfx] pace: presents 1-600 at 60.0 fps, longest gap 25.0 ms, 2 of 599 gaps over a field; "
            + "a frame 16.67 ms = submit 2.00 + present 3.00 + the rest 11.67; builds 1.00 ms (waited on 0.10)\n"
            + "[2026-11-23 11:00:10] [te.android] perf: presents 1-600: CPU 120% of one core (15.0% of 8 cores), 20.00 ms of CPU a present; peak memory 600 MB\n"
            + "[2026-11-23 11:00:10] [te.android] frames: presents 1-600: frame time avg 16.67 ms, min 15.90, max 25.00, p50 16.65, p95 17.45, "
            + "p99 18.05, jitter 0.60 ms; 2 over 1.5x and 0 over 2.5x the median, of 599\n"
            + "[2026-11-23 11:00:10] [te.gfx] gpu frame: avg 6.20 ms, min 5.10, max 9.80 over 140 frames (1 in 4 sampled; dropped 0, disjoint 0)\n"
            + "[2026-11-23 11:00:10] [te.android] config: presents 1-600: preset quality, scale 4x, anisotropy 16x, draw distance long, aspect 16:9, frame rate 60, vsync on\n"
            + "[2026-11-23 11:00:12] " + ToolsCore.powerLine(33.4, ToolsCore.currentMa(-812000), 3.91, ToolsCore.watts(812, 3.91, "battery"), "battery", 78, 0, 0.41, 60.0) + "\n"
            + "[2026-11-23 11:00:20] [te.gfx] pace: presents 601-1200 at 59.0 fps, longest gap 40.0 ms, 9 of 599 gaps over a field; "
            + "a frame 16.95 ms = submit 2.00 + present 3.00 + the rest 11.95; builds 1.00 ms (waited on 0.10)\n"
            + "[2026-11-23 11:00:20] [te.android] perf: presents 601-1200: CPU 140% of one core (17.5% of 8 cores), 20.00 ms of CPU a present; peak memory 620 MB\n"
            + "[2026-11-23 11:00:20] [te.android] frames: presents 601-1200: frame time avg 16.95 ms, min 14.00, max 40.00, p50 16.65, p95 19.05, "
            + "p99 33.35, jitter 1.40 ms; 9 over 1.5x and 1 over 2.5x the median, of 599\n"
            + "[2026-11-23 11:00:20] [te.gfx] gpu frame: avg 7.00 ms, min 5.00, max 12.00 over 150 frames (1 in 4 sampled; dropped 0, disjoint 0)\n"
            + "[2026-11-23 11:00:20] [te.android] config: presents 601-1200: preset quality, scale 4x, anisotropy 16x, draw distance long, aspect 16:9, frame rate 60, vsync on\n"
            + "[2026-11-23 11:00:22] " + ToolsCore.powerLine(35.0, ToolsCore.currentMa(950), 3.85, ToolsCore.watts(950, 3.85, "battery"), "battery", 77, 1, 0.55, 60.0) + "\n"
            + "[2026-11-23 11:00:32] " + ToolsCore.powerLine(36.0, 1200, 3.80, ToolsCore.watts(1200, 3.80, "charger"), "charger", 77, -1, -1, -1) + "\n";
        ToolsCore.Summary q = ToolsCore.summarize(p0, null);
        check(q.count() == 2, "P0: two windows (" + q.count() + ")");
        check(Math.abs(q.avg(ToolsCore.FRAME_MS) - 16.81) < 0.001, "P0: frame time from the frames line " + q.avg(ToolsCore.FRAME_MS));
        check(q.min(ToolsCore.FT_MIN) == 14.0 && q.max(ToolsCore.FT_MAX) == 40.0, "P0: frame time min / max");
        check(q.max(ToolsCore.FT_P99) == 33.35 && Math.abs(q.avg(ToolsCore.FT_P95) - 18.25) < 0.001, "P0: p95 / p99");
        check(Math.abs(q.avg(ToolsCore.JITTER) - 1.0) < 0.001, "P0: jitter");
        check(q.sum(ToolsCore.LATE) == 11 && q.sum(ToolsCore.VERY_LATE) == 1, "P0: late frames summed");
        check(Math.abs(q.avg(ToolsCore.GPU_AVG) - 6.6) < 0.001 && q.min(ToolsCore.GPU_MIN) == 5.0 && q.max(ToolsCore.GPU_MAX) == 12.0, "P0: GPU time");
        check("preset quality, scale 4x, anisotropy 16x, draw distance long, aspect 16:9, frame rate 60, vsync on".equals(q.mainConfig()), "P0: the settings");
        check(q.power.size() == 3, "P0: three telemetry samples");
        check(q.powerMin(ToolsCore.TEMP) == 33.4 && q.powerMax(ToolsCore.TEMP) == 36.0, "P0: battery temperature min / max");
        check(Math.abs(q.powerAvg(ToolsCore.CURRENT) - (812 + 950 + 1200) / 3.0) < 0.01, "P0: current (µA and mA read as mA)");
        check(Math.abs(q.powerAvg(ToolsCore.WATTS) - (812 * 3.91 + 950 * 3.85) / 2000.0) < 0.01, "P0: power on the battery only " + q.powerAvg(ToolsCore.WATTS));
        check(q.charged(), "P0: a sample on the charger noticed");
        check(q.powerMax(ToolsCore.THERMAL) == 1 && q.powerMax(ToolsCore.HEADROOM) == 0.55, "P0: thermal status and headroom (na skipped)");
        check(q.powerMax(ToolsCore.REFRESH) == 60.0, "P0: display refresh");
        check(ToolsCore.currentMa(0) < 0 && ToolsCore.currentMa(Long.MIN_VALUE) < 0, "P0: an unknown current stays unknown");
        ToolsCore.Power unk = ToolsCore.power(" temp_c=na current_ma=na voltage_v=na power_w=na source=na level=na thermal=na headroom=na refresh_hz=na");
        check(unk.tempC < 0 && unk.thermal < 0 && unk.source == null && unk.refreshHz < 0, "P0: na fields read as unknown");
        check(ToolsCore.summarize(p0 + "[x] [te.gfx] gpu frame: unavailable (the driver has no timer query)\n", null).gpuUnavailable, "P0: GPU time unavailable noticed");
        String l0 = ToolsCore.sessionLine(q, "1.4.0", "1", "30", "bilinear", "original", "2026-11-23 11:00:00#99");
        String[] c0 = ToolsCore.sessions(ToolsCore.trimmed(hist, l0, 50)).get(1);
        check(c0.length == ToolsCore.COLUMNS, "P0: a history line has every column (" + c0.length + ")");
        check(c0[10].equals("4") && c0[11].equals("60") && c0[13].equals("long"), "P0: scale / frame rate / distance from the session's own settings");
        check(c0[ToolsCore.COL_PRESET].equals("quality") && c0[ToolsCore.COL_ANISO].equals("16x"), "P0: preset and anisotropy columns");
        check(c0[ToolsCore.COL_GPU].equals("6.60") && c0[ToolsCore.COL_TEMP_MAX].equals("36.0") && c0[ToolsCore.COL_THERMAL].equals("1"), "P0: GPU / temperature / thermal columns");
        check(ToolsCore.col(rows.get(0), ToolsCore.COL_GPU).equals("—"), "P0: an older history line reads — in the new columns");
        String old16 = "2026-11-01 10:00:00\t1.2.0\t60\tfinished\t60.0\t59.0\t16.70\t20.0\t90\t600\t4\t60\tbilinear\tlong\t0\tk1\n";
        List<String[]> mixed = ToolsCore.sessions(ToolsCore.trimmed(old16, l0, 50));
        check(mixed.size() == 2 && ToolsCore.col(mixed.get(0), ToolsCore.COL_TEMP).equals("—") && ToolsCore.hasSession(old16, "k1"),
            "P0: a 1.2.0 history line kept and read beside the new ones");
        check(ToolsCore.hasSession(ToolsCore.trimmed(hist, l0, 50), "2026-11-23 11:00:00#99"), "P0: the key still found");

        // 1.6.0: the hitch lines, the area changes, the display request
        String h6 = "[2026-11-23 12:00:00] [te.android] display: asked for 60 Hz (mode 2); the panel at 120.0 Hz now; modes 1920x1080 60.0 Hz, 1920x1080 120.0 Hz\n"
            + "[twinsanity-pocket] area: 'levels\\earth\\hub\\huba' @ boundary 15361 (was 'levels\\earth\\hub\\beach')\n"
            + "[te.android] hitch: present 15400 boundary 15420: 115.3 ms; game 108.0 ms, drawing 2.0 + swap 1.5 ms, build wait 0.2 ms; shaders 0 compiled + 0 linked (0.0 ms); textures made 0, meshes made 3; loading no\n"
            + "[te.android] hitch: present 9100 boundary 9150: 205.2 ms; game 20.0 ms, drawing 150.0 + swap 2.0 ms, build wait 1.0 ms; shaders 2 compiled + 1 linked (140.0 ms); textures made 0, meshes made 0; loading no\n"
            + "[te.android] hitch: present 9900 boundary 9950: 90.0 ms; game 10.0 ms, drawing 5.0 + swap 2.0 ms, build wait 60.0 ms; shaders 0 compiled + 0 linked (0.0 ms); textures made 0, meshes made 40; loading no\n"
            + "[te.android] hitch: present 30000 boundary 30050: 80.0 ms; game 70.0 ms, drawing 2.0 + swap 1.0 ms, build wait 0.0 ms; shaders 0 compiled + 0 linked (0.0 ms); textures made 0, meshes made 0; loading no\n"
            + "[te.android] hitch: present 700 boundary 700: 900.0 ms; game -1.0 ms, drawing 1.0 + swap 1.0 ms, build wait 0.0 ms; shaders 0 compiled + 0 linked (0.0 ms); textures made 0, meshes made 0; loading yes\n";
        // 1.6.1: the game thread's tick lines split a game-side hitch
        String t61 = "[twinsanity-pocket] tick: boundary 30049 took 72.0 ms (draw submit 0.0, present 1.0, pump 0.2, game 70.8) prof: (own code) 81% (29), texture decode 8% (3)\n"
            + "[twinsanity-pocket] area: 'levels\\earth\\cavern\\cavent' @ boundary 40000 (was '')\n"
            + "[te.android] hitch: present 40010 boundary 40005: 190.0 ms; game 170.0 ms, drawing 2.0 + swap 1.0 ms, build wait 0.0 ms; shaders 0 compiled + 0 linked (0.0 ms); textures made 0, meshes made 0; loading no\n"
            + "[twinsanity-pocket] tick: boundary 40004 took 168.0 ms (draw submit 0.0, present 1.0, pump 0.2, game 166.8) load: textures 40 decoded (900 KB, 50.5 ms), made 40 (waited 10.0 ms); model parts 120 (30.0 ms; waits 2.0 ms) prof: texture decode 45% (38), (own code) 40% (34)\n";
        ToolsCore.Summary hs = ToolsCore.summarize(p0 + h6 + t61, null);
        check(hs.playHitches() == 5, "1.6.0: hitches read, the loading one apart");
        check(hs.worstPlayHitch() == 205.2, "1.6.0: the worst play hitch");
        check(hs.hitches.get(0).cause(hs).equals("jogo: troca de área") && "levels\\earth\\hub\\huba".equals(hs.areaNear(15420)), "1.6.0: a hitch after an area change");
        check(hs.hitches.get(1).cause(hs).equals("compilação de shaders"), "1.6.0: a shader hitch");
        check(hs.hitches.get(2).cause(hs).equals("espera pela geometria"), "1.6.0: a geometry hitch");

        check(hs.hitches.get(4).cause(hs).equals("tela de carregamento"), "1.6.0: the loading picture's frame");
        check(hs.hitches.size() == 6 && hs.hitches.get(3).cause(hs).equals("jogo: código do próprio jogo"), "1.6.1: a tick of the game's own code");
        check(hs.hitches.get(5).cause(hs).equals("jogo: o port preparando texturas / modelos"), "1.6.1: a tick of the port's load work");
        ToolsCore.Tick tk = hs.tickNear(40005);
        check(tk != null && Math.abs(tk.loadMs - 92.5) < 0.01 && "texture decode".equals(tk.top) && tk.topPct == 45, "1.6.1: the tick's load work and top section " + (tk == null ? "" : tk.loadMs + " " + tk.top));
        check(hs.causes().size() == 5 && !hs.causes().containsKey("tela de carregamento"), "1.6.0: causes counted outside the loading");
        check(hs.displayMode != null && hs.displayMode.startsWith("asked for 60 Hz"), "1.6.0: the display request read");
        String[] c6 = ToolsCore.sessions(ToolsCore.trimmed("", ToolsCore.sessionLine(hs, "1.6.0", "3", "60", "bilinear", "long", "k6"), 50)).get(0);
        check(c6.length == ToolsCore.COLUMNS && c6[ToolsCore.COL_HITCHES].equals("5") && c6[ToolsCore.COL_HITCH_MAX].equals("205"), "1.6.0: hitch columns");
        check(ToolsCore.sessions(ToolsCore.trimmed("", ToolsCore.sessionLine(q, "1.5.0", "3", "60", "bilinear", "long", "k5"), 50)).get(0)[ToolsCore.COL_HITCHES].equals("—"),
            "1.6.0: a log without hitch support reads — (unknown), not 0");

        // 1.7.0: the history key from a log's head alone
        check("2026-11-23 10:00:00".equals(ToolsCore.firstStamp("no stamp here\n[2026-11-23 10:00:00] x\n[2026-11-23 10:00:05] y\n")), "1.7.0: first stamp");
        check(ToolsCore.firstStamp("nothing\n") == null && ToolsCore.firstStamp(null) == null, "1.7.0: no stamp");

        List<String> fail = ToolsCore.failureLines("ok\n[x] FATAL signal 11 (SIGSEGV)\nfine\njava.lang.IllegalStateException: y\n", 10);
        check(fail.size() == 2, "two failure lines (" + fail.size() + ")");
        check(ToolsCore.stageWords("crash signal 11").equals("Travou") && ToolsCore.stageWords(null).startsWith("Nenhuma"), "stage words");

        if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
