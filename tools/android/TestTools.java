package com.twinsanityevolved.game;

import java.util.List;

// The Ferramentas tab's log reading (android/game/.../ToolsCore.java) on a plain JVM: the pace / perf lines the game
// writes, the session history's lines, the failure lines.
public final class TestTools {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) failures++; }

    public static void main(String[] a) {
        String log =
            "[2026-11-23 10:00:00] TwinsanityEvolved native library loaded: process x:game\n"
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

        List<String> fail = ToolsCore.failureLines("ok\n[x] FATAL signal 11 (SIGSEGV)\nfine\njava.lang.IllegalStateException: y\n", 10);
        check(fail.size() == 2, "two failure lines (" + fail.size() + ")");
        check(ToolsCore.stageWords("crash signal 11").equals("Travou") && ToolsCore.stageWords(null).startsWith("Nenhuma"), "stage words");

        if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
