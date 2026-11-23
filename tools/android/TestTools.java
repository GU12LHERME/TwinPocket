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

        List<String> fail = ToolsCore.failureLines("ok\n[x] FATAL signal 11 (SIGSEGV)\nfine\njava.lang.IllegalStateException: y\n", 10);
        check(fail.size() == 2, "two failure lines (" + fail.size() + ")");
        check(ToolsCore.stageWords("crash signal 11").equals("Travou") && ToolsCore.stageWords(null).startsWith("Nenhuma"), "stage words");

        if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
