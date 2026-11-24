package com.twinsanityevolved.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   [te.android] frames: presents A-B: frame time avg X ms, min, max, p50, p95, p99, jitter J ms; N over 1.5x and M over 2.5x the median, of G
 *   [te.gfx] gpu frame: avg X ms, min Y, max Z over N frames (1 in K sampled; ...)   -- or "unavailable (...)" once
 *   [te.android] config: presents A-B: preset P, scale Nx, anisotropy Mx, draw distance D, aspect A, frame rate F, vsync V
 *   [te.android] power: temp_c=T current_ma=I voltage_v=V power_w=W source=battery|charger level=L thermal=S headroom=H refresh_hz=R
 *     (MainActivity's Telemetry, every 10 s; "na" where the device does not tell)
 *   [te.android] hitch: present P boundary B: G ms; game X ms, drawing S + swap W ms, build wait Y ms; shaders C compiled + L linked (Z ms);
 *     textures made T, meshes made M; loading yes|no      (graphics.cpp NoteHitch: a frame over 50 ms)
 *   [twinsanity-pocket] area: 'name' @ boundary B (was 'old')        (mods.cpp: the game's area changes)
 *   [te.android] display: ...                                         (MainActivity: the panel's rate and modes)
 *   [twinsanity-pocket] tick: boundary N took X ms (draw submit a, present b, pump c, game d) load: ...; ... prof: (own code) P%, name Q%
 *     (time.cpp: a game-thread tick over TE_TICK_LOG_MS -- 50 on Android since 1.6.1 -- with the port's load work in it and
 *     the sampled sections: "(own code)" is the game's code, the rest the port's)
 *   [YYYY-MM-DD HH:MM:SS] ... (the time stamp of the log's lines)
 */
final class ToolsCore {
    private ToolsCore() {}

    /** One summary window of the game's (600 presents, about 10 s at 60 fps). */
    static final class Window {
        double fps = -1, frameMs = -1, longestGapMs = -1, cpuOneCore = -1, cpuAllCores = -1, peakMb = -1;
        long slowGaps = -1, gaps = -1;
        // the frame times' line, the GPU's line, the settings it was measured with (null / -1: not in the log)
        double ftAvg = -1, ftMin = -1, ftMax = -1, ftP50 = -1, ftP95 = -1, ftP99 = -1, jitter = -1;
        long late = -1, veryLate = -1;
        double gpuAvg = -1, gpuMin = -1, gpuMax = -1;
        String config;
    }

    /** One slow tick of the game thread (time.cpp's tick line): its time, the port's load work in it, the sampled sections. */
    static final class Tick {
        long boundary;
        double ms, gameMs = -1, loadMs;   // loadMs: the port's load work (decodes, makes, posts, waits) summed
        String load = "", prof = "";
        String top;                       // the most sampled section ("(own code)" = the game's code), null without samples
        double topPct = -1;
    }

    /** One frame over the hitch threshold and what it did (graphics.cpp NoteHitch). */
    static final class Hitch {
        long present, boundary;
        double ms, gameMs = -1, drawMs, swapMs, buildWaitMs, shaderMs;
        long shaders, programs, textures, meshes;
        boolean loading;

        /** The likeliest cause, from the frame's own figures: the share of the frame each part took. */
        String cause(Summary s) {
            if (loading) return "tela de carregamento";
            if (shaderMs >= ms * 0.3) return "compilação de shaders";
            if (buildWaitMs >= ms * 0.3) return "espera pela geometria";
            if (drawMs + swapMs >= ms * 0.5) return "renderização (envio / troca de quadro)";
            if (gameMs >= ms * 0.6) {
                // the game thread's own tick line splits it: the port's load work, or the game's code
                Tick t = s.tickNear(boundary);
                if (t != null && t.loadMs >= t.ms * 0.4) return "jogo: o port preparando texturas / modelos";
                if (t != null && "(own code)".equals(t.top) && t.topPct >= 50) return "jogo: código do próprio jogo";
                return s.areaNear(boundary) != null ? "jogo: troca de área" : "jogo (CPU da thread do jogo)";
            }
            return "indefinido";
        }
    }

    /** One telemetry sample (every 10 s in the game's process); -1 / null: the device does not tell. */
    static final class Power {
        double tempC = -1, currentMa = -1, voltageV = -1, powerW = -1, headroom = -1, refreshHz = -1;
        int thermal = -1, level = -1;
        String source;   // battery / charger / null
    }

    /** A session's figures: its windows put together. */
    static final class Summary {
        final List<Window> windows = new ArrayList<>();
        String start, end;          // the first and last time stamps in the log (null: none)
        String endState = "?";      // finished / crash / exited / running / ?
        String renderer;            // the first [gl.es] line, if any
        int fatalLines;
        final List<Power> power = new ArrayList<>();
        boolean gpuUnavailable;     // the driver has no timer query
        final List<Hitch> hitches = new ArrayList<>();
        final Map<Long, Tick> ticks = new LinkedHashMap<>();   // the slow game-thread ticks by boundary

        /** The slow tick of a hitch's picture (its boundary or the one or two before), null when none was logged. */
        Tick tickNear(long boundary) {
            for (long b = boundary + 1; b >= boundary - 2; b--) {
                Tick t = ticks.get(b);
                if (t != null) return t;
            }
            return null;
        }
        final List<long[]> areaAt = new ArrayList<>();      // {boundary} of each area change, with its name in areaNames
        final List<String> areaNames = new ArrayList<>();
        String displayMode;         // the game's display request line (null: an older build)

        /** The hitches outside the loading pictures (those are expected to be long). */
        int playHitches() {
            int n = 0;
            for (Hitch h : hitches) if (!h.loading) n++;
            return n;
        }

        double worstPlayHitch() {
            double m = -1;
            for (Hitch h : hitches) if (!h.loading) m = Math.max(m, h.ms);
            return m;
        }

        /** How many play hitches each cause has, in a stable order. */
        Map<String, Integer> causes() {
            Map<String, Integer> n = new LinkedHashMap<>();
            for (Hitch h : hitches) {
                if (h.loading) continue;
                String c = h.cause(this);
                n.put(c, n.getOrDefault(c, 0) + 1);
            }
            return n;
        }

        /** The area the game changed to within the 3 s before the boundary (or just after), null when none. */
        String areaNear(long boundary) {
            for (int i = areaAt.size() - 1; i >= 0; i--) {
                long b = areaAt.get(i)[0];
                if (b <= boundary + 30 && b >= boundary - 180) return areaNames.get(i);
            }
            return null;
        }

        /** The settings most windows were measured with (null: none logged) and how many distinct settings the session had. */
        String mainConfig() {
            Map<String, Integer> n = configCounts();
            String best = null;
            int most = 0;
            for (Map.Entry<String, Integer> e : n.entrySet()) if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
            return best;
        }

        Map<String, Integer> configCounts() {
            Map<String, Integer> n = new LinkedHashMap<>();
            for (Window w : windows) if (w.config != null) n.put(w.config, n.getOrDefault(w.config, 0) + 1);
            return n;
        }

        long sum(int what) {
            long t = -1;
            for (Window w : windows) {
                double v = value(w, what);
                if (v >= 0) t = Math.max(t, 0) + (long) v;
            }
            return t;
        }

        double powerAvg(int what) {
            double s = 0;
            int n = 0;
            for (Power p : power) {
                double v = powerValue(p, what);
                if (v >= 0) { s += v; n++; }
            }
            return n > 0 ? s / n : -1;
        }

        double powerMin(int what) {
            double m = -1;
            for (Power p : power) {
                double v = powerValue(p, what);
                if (v >= 0 && (m < 0 || v < m)) m = v;
            }
            return m;
        }

        double powerMax(int what) {
            double m = -1;
            for (Power p : power) m = Math.max(m, powerValue(p, what));
            return m;
        }

        /** True when at least one sample was taken on the charger (the power figures then are not the game's draw). */
        boolean charged() {
            for (Power p : power) if ("charger".equals(p.source)) return true;
            return false;
        }

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

    static final int FPS = 0, FRAME_MS = 1, GAP_MS = 2, CPU = 3, CPU_ALL = 4, MEMORY = 5, FT_MIN = 6, FT_MAX = 7, FT_P50 = 8,
        FT_P95 = 9, FT_P99 = 10, JITTER = 11, LATE = 12, VERY_LATE = 13, GPU_AVG = 14, GPU_MIN = 15, GPU_MAX = 16;

    static double value(Window w, int what) {
        switch (what) {
            case FPS: return w.fps;
            case FRAME_MS: return w.ftAvg >= 0 ? w.ftAvg : w.frameMs;
            case GAP_MS: return w.ftMax >= 0 ? Math.max(w.ftMax, w.longestGapMs) : w.longestGapMs;
            case CPU: return w.cpuOneCore;
            case CPU_ALL: return w.cpuAllCores;
            case FT_MIN: return w.ftMin;
            case FT_MAX: return w.ftMax;
            case FT_P50: return w.ftP50;
            case FT_P95: return w.ftP95;
            case FT_P99: return w.ftP99;
            case JITTER: return w.jitter;
            case LATE: return w.late;
            case VERY_LATE: return w.veryLate;
            case GPU_AVG: return w.gpuAvg;
            case GPU_MIN: return w.gpuMin;
            case GPU_MAX: return w.gpuMax;
            default: return w.peakMb;
        }
    }

    static final int TEMP = 0, CURRENT = 1, VOLTAGE = 2, WATTS = 3, THERMAL = 4, HEADROOM = 5, REFRESH = 6;

    static double powerValue(Power p, int what) {
        switch (what) {
            case TEMP: return p.tempC;
            case CURRENT: return p.currentMa;
            case VOLTAGE: return p.voltageV;
            case WATTS: return "battery".equals(p.source) ? p.powerW : -1;   // on the charger the current is not the game's draw
            case THERMAL: return p.thermal;
            case HEADROOM: return p.headroom;
            default: return p.refreshHz;
        }
    }

    /** The thermal status's words (PowerManager.THERMAL_STATUS_*). */
    static String thermalWords(int s) {
        switch (s) {
            case 0: return "nenhum";
            case 1: return "leve";
            case 2: return "moderado";
            case 3: return "severo";
            case 4: return "crítico";
            case 5: return "emergência";
            case 6: return "desligamento";
            default: return "—";
        }
    }

    private static final Pattern PACE = Pattern.compile(
        "\\[te\\.gfx\\] pace: presents (\\d+)-(\\d+) at ([0-9.]+) fps, longest gap ([0-9.]+) ms, (\\d+) of (\\d+) gaps over a field; a frame ([0-9.]+) ms");
    private static final Pattern PERF = Pattern.compile(
        "\\[te\\.android\\] perf: presents (\\d+)-(\\d+): CPU ([0-9.]+)% of one core \\(([0-9.]+)% of \\d+ cores\\).*peak memory ([0-9.]+) MB");
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\]");
    private static final String NUM = "([0-9.]+)";
    private static final Pattern FRAMES = Pattern.compile(
        "\\[te\\.android\\] frames: presents (\\d+)-(\\d+): frame time avg " + NUM + " ms, min " + NUM + ", max " + NUM + ", p50 " + NUM
            + ", p95 " + NUM + ", p99 " + NUM + ", jitter " + NUM + " ms; (\\d+) over 1\\.5x and (\\d+) over 2\\.5x");
    private static final Pattern GPU = Pattern.compile("\\[te\\.gfx\\] gpu frame: avg " + NUM + " ms, min " + NUM + ", max " + NUM);
    private static final Pattern CONFIG = Pattern.compile("\\[te\\.android\\] config: presents (\\d+)-(\\d+): (.+)$");
    private static final Pattern KV = Pattern.compile("([a-z_]+)=([^ ]+)");
    private static final Pattern HITCH = Pattern.compile(
        "\\[te\\.android\\] hitch: present (\\d+) boundary (\\d+): " + NUM + " ms; game (-?[0-9.]+) ms, drawing " + NUM + " \\+ swap " + NUM
            + " ms, build wait (-?[0-9.]+) ms; shaders (\\d+) compiled \\+ (\\d+) linked \\(" + NUM + " ms\\); textures made (\\d+), meshes made (\\d+); loading (yes|no)");
    private static final Pattern AREA = Pattern.compile("\\] area: '([^']*)' @ boundary (\\d+)");
    private static final Pattern TICK = Pattern.compile("\\] tick: boundary (\\d+) took " + NUM + " ms \\([^)]*game " + NUM + "\\)(.*)$");
    private static final Pattern MS_IN = Pattern.compile("([0-9]+\\.[0-9]) ms");
    private static final Pattern TOP = Pattern.compile("^\\s*(.+?) ([0-9.]+)%");

    /** The figures of one game log; stage: the stage file's text (null: unknown). */
    static Summary summarize(String log, String stage) {
        Summary s = new Summary();
        if (stage != null) {
            String t = stage.trim();
            int sp = t.indexOf(' ');
            s.endState = sp > 0 ? t.substring(0, sp) : (t.isEmpty() ? "?" : t);
        }
        if (log == null) return s;
        Map<String, Window> byRange = new LinkedHashMap<>();   // a window's lines are matched by their presents
        Window last = null;
        for (String line : log.split("\n")) {
            Matcher st = STAMP.matcher(line);
            if (st.find()) {
                if (s.start == null) s.start = st.group(1);
                s.end = st.group(1);
            }
            if (line.contains("FATAL") || line.contains("Fatal signal")) s.fatalLines++;
            if (s.renderer == null && line.contains("[gl.es]")) s.renderer = line.substring(line.indexOf("[gl.es]")).trim();
            if (line.contains("] tick: boundary ")) {
                Matcher t = TICK.matcher(line);
                if (t.find()) s.ticks.put(Long.parseLong(t.group(1)), tick(t));
                continue;
            }
            if (line.contains("] area: '")) {
                Matcher a = AREA.matcher(line);
                if (a.find()) {
                    s.areaAt.add(new long[] {Long.parseLong(a.group(2))});
                    s.areaNames.add(a.group(1));
                }
                continue;
            }
            if (!line.contains("[te.")) continue;
            Matcher p = PACE.matcher(line);
            if (p.find()) {
                Window w = new Window();
                w.fps = parse(p.group(3));
                w.longestGapMs = parse(p.group(4));
                w.slowGaps = (long) parse(p.group(5));
                w.gaps = (long) parse(p.group(6));
                w.frameMs = parse(p.group(7));
                s.windows.add(w);
                byRange.put(p.group(1) + "-" + p.group(2), w);
                last = w;
                continue;
            }
            Matcher q = PERF.matcher(line);
            if (q.find()) {
                Window w = window(s, byRange, q.group(1) + "-" + q.group(2));
                w.cpuOneCore = parse(q.group(3));
                w.cpuAllCores = parse(q.group(4));
                w.peakMb = parse(q.group(5));
                last = w;
                continue;
            }
            Matcher f = FRAMES.matcher(line);
            if (f.find()) {
                Window w = window(s, byRange, f.group(1) + "-" + f.group(2));
                w.ftAvg = parse(f.group(3));
                w.ftMin = parse(f.group(4));
                w.ftMax = parse(f.group(5));
                w.ftP50 = parse(f.group(6));
                w.ftP95 = parse(f.group(7));
                w.ftP99 = parse(f.group(8));
                w.jitter = parse(f.group(9));
                w.late = (long) parse(f.group(10));
                w.veryLate = (long) parse(f.group(11));
                last = w;
                continue;
            }
            Matcher g = GPU.matcher(line);
            if (g.find()) {
                if (last != null) {   // (the line follows its window's frames line)
                    last.gpuAvg = parse(g.group(1));
                    last.gpuMin = parse(g.group(2));
                    last.gpuMax = parse(g.group(3));
                }
                continue;
            }
            if (line.contains("[te.gfx] gpu frame: unavailable")) {
                s.gpuUnavailable = true;
                continue;
            }
            Matcher c = CONFIG.matcher(line);
            if (c.find()) {
                Window w = window(s, byRange, c.group(1) + "-" + c.group(2));
                w.config = c.group(3).trim();
                last = w;
                continue;
            }
            Matcher h = HITCH.matcher(line);
            if (h.find()) {
                Hitch x = new Hitch();
                x.present = Long.parseLong(h.group(1));
                x.boundary = Long.parseLong(h.group(2));
                x.ms = parse(h.group(3));
                x.gameMs = Double.parseDouble(h.group(4));
                x.drawMs = parse(h.group(5));
                x.swapMs = parse(h.group(6));
                x.buildWaitMs = Math.max(0, Double.parseDouble(h.group(7)));
                x.shaders = Long.parseLong(h.group(8));
                x.programs = Long.parseLong(h.group(9));
                x.shaderMs = parse(h.group(10));
                x.textures = Long.parseLong(h.group(11));
                x.meshes = Long.parseLong(h.group(12));
                x.loading = h.group(13).equals("yes");
                s.hitches.add(x);
                continue;
            }
            int da = line.indexOf("[te.android] display:");
            if (da >= 0) {
                s.displayMode = line.substring(da + 21).trim();
                continue;
            }
            int at = line.indexOf("[te.android] power:");
            if (at >= 0) s.power.add(power(line.substring(at + 19)));
        }
        return s;
    }

    static Tick tick(Matcher t) {
        Tick k = new Tick();
        k.boundary = Long.parseLong(t.group(1));
        k.ms = parse(t.group(2));
        k.gameMs = parse(t.group(3));
        String rest = t.group(4);
        int p = rest.indexOf(" prof: ");
        String load = p >= 0 ? rest.substring(0, p) : rest;
        k.prof = p >= 0 ? rest.substring(p + 7).trim() : "";
        int l = load.indexOf(" load: ");
        k.load = l >= 0 ? load.substring(l + 7).trim() : "";
        // the load work's own times: every "x.y ms" in it (decodes, makes, model parts, mesh posts, the waits)
        Matcher m = MS_IN.matcher(k.load);
        while (m.find()) k.loadMs += Double.parseDouble(m.group(1));
        if (!k.prof.isEmpty()) {
            Matcher top = TOP.matcher(k.prof.split(",")[0]);
            if (top.find()) {
                k.top = top.group(1).trim();
                k.topPct = parse(top.group(2));
            }
        }
        return k;
    }

    // the window of these presents (a line without its pace line makes one)
    private static Window window(Summary s, Map<String, Window> byRange, String range) {
        Window w = byRange.get(range);
        if (w == null) {
            w = new Window();
            s.windows.add(w);
            byRange.put(range, w);
        }
        return w;
    }

    // ---- the telemetry line (written by Telemetry in the game's process; kept here so the JVM test covers both ends) ------
    /** BatteryManager's CURRENT_NOW as mA: µA by the documentation; a device that reports mA is recognised by the size (no
     *  handheld draws under 10 mA while it plays). The sign (charge / discharge) differs between makers: dropped. -1: unknown. */
    static double currentMa(long raw) {
        if (raw == Long.MIN_VALUE || raw == Integer.MIN_VALUE || raw == 0) return -1;
        long a = Math.abs(raw);
        return a < 10_000 ? a : a / 1000.0;
    }

    /** The estimated power in W: current × voltage, on the battery only (on the charger the current is the charge). */
    static double watts(double currentMa, double voltageV, String source) {
        return currentMa > 0 && voltageV > 0 && "battery".equals(source) ? currentMa * voltageV / 1000.0 : -1;
    }

    static String powerLine(double tempC, double currentMa, double voltageV, double powerW, String source, int level, int thermal,
                            double headroom, double refreshHz) {
        return "[te.android] power: temp_c=" + na(tempC, 1) + " current_ma=" + na(currentMa, 0) + " voltage_v=" + na(voltageV, 2)
            + " power_w=" + na(powerW, 2) + " source=" + (source != null ? source : "na") + " level=" + (level >= 0 ? Integer.toString(level) : "na")
            + " thermal=" + (thermal >= 0 ? Integer.toString(thermal) : "na") + " headroom=" + na(headroom, 2) + " refresh_hz=" + na(refreshHz, 1);
    }

    private static String na(double v, int decimals) {
        return v < 0 ? "na" : String.format(Locale.US, "%." + decimals + "f", v);
    }

    static Power power(String text) {
        Power p = new Power();
        Matcher m = KV.matcher(text);
        while (m.find()) {
            String k = m.group(1), v = m.group(2);
            switch (k) {
                case "temp_c": p.tempC = parse(v); break;
                case "current_ma": p.currentMa = parse(v); break;
                case "voltage_v": p.voltageV = parse(v); break;
                case "power_w": p.powerW = parse(v); break;
                case "thermal": p.thermal = (int) parse(v); break;
                case "headroom": p.headroom = parse(v); break;
                case "refresh_hz": p.refreshHz = parse(v); break;
                case "level": p.level = (int) parse(v); break;
                case "source": p.source = v.equals("na") ? null : v; break;
                default: break;
            }
        }
        return p;
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

    /** The first time stamp of a log's text ("2026-11-23 22:10:20"), null when none: the history key without a parse. */
    static String firstStamp(String head) {
        if (head == null) return null;
        for (String line : head.split("\n")) {
            Matcher m = STAMP.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** A config line's settings by name ("preset quality, scale 4x, ..." -> preset=quality, scale=4x, ...). */
    static Map<String, String> configFields(String config) {
        Map<String, String> m = new LinkedHashMap<>();
        if (config == null) return m;
        for (String part : config.split(", ")) {
            int sp = part.lastIndexOf(' ');
            if (sp > 0) m.put(part.substring(0, sp).trim(), part.substring(sp + 1).trim());
        }
        return m;
    }

    // ---- the session history: one tab-separated line a session (files/logs/sessions.tsv) -------------------------------
    // The first 16 columns are the history's original ones (older lines have only those); the rest came with the 1.4.0
    // report (P0): the settings the session was measured with, the frame times, the GPU, the temperature and the power.
    static final String SESSIONS_HEADER =
        "# start\tversion\tduration_s\tend\tavg_fps\tmin_fps\tavg_frame_ms\tworst_gap_ms\tavg_cpu_one_core\tpeak_mb\tscale\tframe_rate\tfilter\tdraw_distance\tfatal_lines\tkey"
            + "\tpreset\tanisotropy\tp95_frame_ms\tp99_frame_ms\tjitter_ms\tlate_frames\tgpu_avg_ms\tgpu_max_ms\ttemp_avg_c\ttemp_max_c"
            + "\tpower_avg_w\tthermal_max\trefresh_hz\tconfig\thitches\thitch_max_ms";
    static final int COL_KEY = 15, COL_PRESET = 16, COL_ANISO = 17, COL_P95 = 18, COL_P99 = 19, COL_JITTER = 20, COL_LATE = 21,
        COL_GPU = 22, COL_GPU_MAX = 23, COL_TEMP = 24, COL_TEMP_MAX = 25, COL_WATTS = 26, COL_THERMAL = 27, COL_REFRESH = 28,
        COL_CONFIG = 29, COL_HITCHES = 30, COL_HITCH_MAX = 31, COLUMNS = 32;

    /** The history's line for one session; key: what tells this log apart from the others (its first stamp and size).
     *  scale / frameRate / drawDistance: ps2r.ini's, used only when the log names no settings (an older build's log). */
    static String sessionLine(Summary s, String version, String scale, String frameRate, String filter, String drawDistance, String key) {
        String config = s.mainConfig();
        Map<String, String> c = configFields(config);
        String sc = c.containsKey("scale") ? c.get("scale").replace("x", "") : scale;
        String fr = c.containsKey("frame rate") ? c.get("frame rate") : frameRate;
        String dd = c.containsKey("draw distance") ? c.get("draw distance") : drawDistance;
        String[] f = {
            s.start != null ? s.start : "?", clean(version), Long.toString(seconds(s.start, s.end)), clean(s.endState),
            num(s.avg(FPS), 1), num(s.min(FPS), 1), num(s.avg(FRAME_MS), 2), num(s.max(GAP_MS), 1), num(s.avg(CPU), 0),
            num(s.max(MEMORY), 0), clean(sc), clean(fr), clean(filter), clean(dd),
            Integer.toString(s.fatalLines), clean(key),
            clean(c.getOrDefault("preset", "?")), clean(c.getOrDefault("anisotropy", "?")),
            num(s.avg(FT_P95), 2), num(s.max(FT_P99), 2), num(s.avg(JITTER), 2), s.sum(LATE) >= 0 ? Long.toString(s.sum(LATE)) : "—",
            s.gpuUnavailable ? "na" : num(s.avg(GPU_AVG), 2), s.gpuUnavailable ? "na" : num(s.max(GPU_MAX), 2),
            num(s.powerAvg(TEMP), 1), num(s.powerMax(TEMP), 1), num(s.powerAvg(WATTS), 2),
            s.powerMax(THERMAL) >= 0 ? Integer.toString((int) s.powerMax(THERMAL)) : "—", num(s.powerMax(REFRESH), 1),
            clean(config != null ? config : "?"),
            // (a log from before 1.6.0 has no hitch lines: unknown, not none -- the display line came with them)
            s.displayMode == null && s.hitches.isEmpty() ? "—" : Integer.toString(s.playHitches()), num(s.worstPlayHitch(), 0)};
        return String.join("\t", f);
    }

    /** A history line's column, "—" when the line is older than the column. */
    static String col(String[] f, int i) {
        return i < f.length && !f[i].isEmpty() ? f[i] : "—";
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
        for (String[] f : sessions(text)) if (f[COL_KEY].equals(key)) return true;
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
