package com.twinsanityevolved.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Ferramentas tab's reading of the game's own log (files/logs/latest-game.log, previous-game.log): plain Java, no
 * Android, so the CI's JVM tests it (tools/android/TestTools.java). Nothing here runs while the game plays: the launcher
 * reads the files the game already writes (the pace / perf lines every 600 presents, the stage file, the crash records).
 *
 * The lines read (platform/nativeee/host/graphics.cpp, runner/te_android_diag.cpp):
 *   [te.gfx] pace: presents A-B at F fps, longest gap G ms, S of N gaps over a field; a frame M ms = ...
 *   [te.android] perf: presents A-B: CPU C% of one core (P% of K cores), X ms of CPU a present; peak memory R MB
 *   [YYYY-MM-DD HH:MM:SS] ... (the time stamp of the log's lines)
 */
final class ToolsCore {
    private ToolsCore() {}

    /** One summary window of the game's (600 presents, about 10 s at 60 fps). */
    static final class Window {
        double fps = -1, frameMs = -1, longestGapMs = -1, cpuOneCore = -1, cpuAllCores = -1, peakMb = -1;
        long slowGaps = -1, gaps = -1;
    }

    /** A session's figures: its windows put together. */
    static final class Summary {
        final List<Window> windows = new ArrayList<>();
        String start, end;          // the first and last time stamps in the log (null: none)
        String endState = "?";      // finished / crash / exited / running / ?
        String renderer;            // the first [gl.es] line, if any
        int fatalLines;

        int count() { return windows.size(); }

        double avg(int what) {
            double s = 0;
            int n = 0;
            for (Window w : windows) {
                double v = value(w, what);
                if (v >= 0) { s += v; n++; }
            }
            return n > 0 ? s / n : -1;
        }

        double min(int what) {
            double m = -1;
            for (Window w : windows) {
                double v = value(w, what);
                if (v >= 0 && (m < 0 || v < m)) m = v;
            }
            return m;
        }

        double max(int what) {
            double m = -1;
            for (Window w : windows) m = Math.max(m, value(w, what));
            return m;
        }
    }

    static final int FPS = 0, FRAME_MS = 1, GAP_MS = 2, CPU = 3, CPU_ALL = 4, MEMORY = 5;

    static double value(Window w, int what) {
        switch (what) {
            case FPS: return w.fps;
            case FRAME_MS: return w.frameMs;
            case GAP_MS: return w.longestGapMs;
            case CPU: return w.cpuOneCore;
            case CPU_ALL: return w.cpuAllCores;
            default: return w.peakMb;
        }
    }

    private static final Pattern PACE = Pattern.compile(
        "\\[te\\.gfx\\] pace: presents (\\d+)-(\\d+) at ([0-9.]+) fps, longest gap ([0-9.]+) ms, (\\d+) of (\\d+) gaps over a field; a frame ([0-9.]+) ms");
    private static final Pattern PERF = Pattern.compile(
        "\\[te\\.android\\] perf: presents (\\d+)-(\\d+): CPU ([0-9.]+)% of one core \\(([0-9.]+)% of \\d+ cores\\).*peak memory ([0-9.]+) MB");
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\]");

    /** The figures of one game log; stage: the stage file's text (null: unknown). */
    static Summary summarize(String log, String stage) {
        Summary s = new Summary();
        if (stage != null) {
            String t = stage.trim();
            int sp = t.indexOf(' ');
            s.endState = sp > 0 ? t.substring(0, sp) : (t.isEmpty() ? "?" : t);
        }
        if (log == null) return s;
        Window open = null;
        String openRange = null;
        for (String line : log.split("\n")) {
            Matcher st = STAMP.matcher(line);
            if (st.find()) {
                if (s.start == null) s.start = st.group(1);
                s.end = st.group(1);
            }
            if (line.contains("FATAL") || line.contains("Fatal signal")) s.fatalLines++;
            if (s.renderer == null && line.contains("[gl.es]")) s.renderer = line.substring(line.indexOf("[gl.es]")).trim();
            Matcher p = PACE.matcher(line);
            if (p.find()) {
                Window w = new Window();
                w.fps = parse(p.group(3));
                w.longestGapMs = parse(p.group(4));
                w.slowGaps = (long) parse(p.group(5));
                w.gaps = (long) parse(p.group(6));
                w.frameMs = parse(p.group(7));
                s.windows.add(w);
                open = w;
                openRange = p.group(1) + "-" + p.group(2);
                continue;
            }
            Matcher q = PERF.matcher(line);
            if (q.find()) {
                Window w = open != null && (q.group(1) + "-" + q.group(2)).equals(openRange) ? open : null;
                if (w == null) {   // a perf line without its pace line
                    w = new Window();
                    s.windows.add(w);
                }
                w.cpuOneCore = parse(q.group(3));
                w.cpuAllCores = parse(q.group(4));
                w.peakMb = parse(q.group(5));
                open = null;
                openRange = null;
            }
        }
        return s;
    }

    private static double parse(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Seconds between two "yyyy-MM-dd HH:mm:ss" stamps of one day or across days (-1: unknown). */
    static long seconds(String from, String to) {
        if (from == null || to == null) return -1;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            return Math.max(0, (f.parse(to).getTime() - f.parse(from).getTime()) / 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    static String duration(long s) {
        if (s < 0) return "?";
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    static String num(double v, int decimals) {
        if (v < 0) return "—";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    // ---- the session history: one tab-separated line a session (files/logs/sessions.tsv) -------------------------------
    static final String SESSIONS_HEADER =
        "# start\tversion\tduration_s\tend\tavg_fps\tmin_fps\tavg_frame_ms\tworst_gap_ms\tavg_cpu_one_core\tpeak_mb\tscale\tframe_rate\tfilter\tdraw_distance\tfatal_lines\tkey";

    /** The history's line for one session; key: what tells this log apart from the others (its first stamp and size). */
    static String sessionLine(Summary s, String version, String scale, String frameRate, String filter, String drawDistance, String key) {
        String[] f = {
            s.start != null ? s.start : "?", clean(version), Long.toString(seconds(s.start, s.end)), clean(s.endState),
            num(s.avg(FPS), 1), num(s.min(FPS), 1), num(s.avg(FRAME_MS), 2), num(s.max(GAP_MS), 1), num(s.avg(CPU), 0),
            num(s.max(MEMORY), 0), clean(scale), clean(frameRate), clean(filter), clean(drawDistance),
            Integer.toString(s.fatalLines), clean(key)};
        return String.join("\t", f);
    }

    private static String clean(String v) {
        return v == null ? "?" : v.replace('\t', ' ').replace('\n', ' ');
    }

    /** The history's lines (newest last), its comment lines left out. */
    static List<String[]> sessions(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length >= 16) out.add(f);
        }
        return out;
    }

    /** True when the history already has a session with this key. */
    static boolean hasSession(String text, String key) {
        for (String[] f : sessions(text)) if (f[15].equals(key)) return true;
        return false;
    }

    /** The history kept to its newest `keep` sessions, with its header. */
    static String trimmed(String text, String added, int keep) {
        List<String> lines = new ArrayList<>();
        for (String[] f : sessions(text)) lines.add(String.join("\t", f));
        if (added != null) lines.add(added);
        int from = Math.max(0, lines.size() - keep);
        StringBuilder b = new StringBuilder(SESSIONS_HEADER).append('\n');
        for (int i = from; i < lines.size(); i++) b.append(lines.get(i)).append('\n');
        return b.toString();
    }

    /** The lines of a log that tell of a failure (FATAL, signals, aborts, exceptions), at most `max`. */
    static List<String> failureLines(String log, int max) {
        List<String> out = new ArrayList<>();
        if (log == null) return out;
        for (String line : log.split("\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (line.contains("FATAL") || l.contains("fatal signal") || l.contains("sigsegv") || l.contains("sigabrt")
                    || l.contains("abort message") || l.contains("exception") || l.contains("crash:") || l.contains("exit(")) {
                out.add(line.length() > 300 ? line.substring(0, 300) + "…" : line);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** What a stage file says, in words (pt; the launcher translates). */
    static String stageWords(String stage) {
        if (stage == null) return "Nenhuma execução registrada";
        String t = stage.trim();
        if (t.startsWith("finished")) return "Terminou normalmente";
        if (t.startsWith("crash")) return "Travou";
        if (t.startsWith("exited")) return "Encerrada pelo programa";
        if (t.startsWith("running")) return "Interrompida (ou em execução)";
        return "Desconhecido";
    }
}
