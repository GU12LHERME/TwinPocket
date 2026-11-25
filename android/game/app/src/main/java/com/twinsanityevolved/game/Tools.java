package com.twinsanityevolved.game;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.StatFs;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Ferramentas tab's work (the reading is ToolsCore's): the session history kept from the game's logs, the component
 * checks, the device's lines and the ZIP of the technical records. All of it runs on the launcher's io thread, on demand
 * or once a game has ended -- never in the game's process, never while it plays.
 *
 * What a report or the ZIP holds: the logs of files/logs (the game's, the launcher's, the probe's, the stage and crash
 * records, logcat of this app), the session history, the last checks, the device's lines and a copy of ps2r.ini. Never a
 * save, a memory card or the disc image (they are not in files/logs), and no name the player gave a file.
 */
final class Tools {
    private Tools() {}

    static final String SESSIONS = "sessions.tsv", TESTS = "tests.txt", DEVICE = "device.txt";
    static final int KEEP_SESSIONS = 50;

    // ---- the session history ---------------------------------------------------------------------------------------
    /** The game logs not yet in the history added to it (previous before latest). Not while the game runs. */
    static synchronized void recordSessions(Context c, String version) {
        if (Diag.processAlive(c, "game")) return;
        File dir = Diag.logsDir(c);
        File hist = new File(dir, SESSIONS);
        String text = Diag.readFile(hist, 0);
        String before = text;
        Settings st = new Settings(c);
        String stage = Diag.readFile(new File(dir, "stage-game.txt"), 2000);
        for (String name : new String[] {"previous-game.log", "latest-game.log"}) {
            File f = new File(dir, name);
            if (!f.isFile() || f.length() == 0) continue;
            // the key from the log's first stamp alone: a session already in the history costs a 4 KB read, not a parse
            String first = ToolsCore.firstStamp(Diag.readHead(f, 4096));
            if (first != null && text != null && ToolsCore.hasSession(text, first + "#" + f.length())) continue;
            ToolsCore.Summary s = summaryOf(f, name.startsWith("latest") ? stage : null);
            if (s.start == null) continue;
            String key = s.start + "#" + f.length();
            if (text != null && ToolsCore.hasSession(text, key)) continue;
            if (name.startsWith("previous")) s.endState = guessEnd(Diag.readFile(f, 0));
            String line = ToolsCore.sessionLine(s, version, st.get(Settings.SCALE, "1"), st.get(Settings.FRAME_RATE, "60"),
                st.get(Settings.TEXTURE_FILTER, "bilinear"), st.get(Settings.DRAW_DISTANCE, "original"), key);
            text = ToolsCore.trimmed(text, line, KEEP_SESSIONS);
        }
        if (text != null && !text.equals(before)) Diag.write(hist, text);
    }

    // a previous log's end (its stage file was overwritten by the next run): what its last lines say
    private static String guessEnd(String log) {
        if (log == null) return "?";
        if (log.contains("ended normally")) return "finished";
        if (log.contains("FATAL") || log.contains("Fatal signal")) return "crash";
        if (log.contains("exit(")) return "exited";
        return "?";
    }

    static ToolsCore.Summary lastSession(Context c) {
        File dir = Diag.logsDir(c);
        return summaryOf(new File(dir, "latest-game.log"), Diag.readFile(new File(dir, "stage-game.txt"), 2000));
    }

    // THE SUMMARIES, KEPT (1.7.0): a game log is ~1 MB after a long session and its summary a regex pass over every line;
    // Ferramentas, the report, Desempenho and the history each asked for it again. Kept per file (its path, length, time
    // and the stage text it was read with) until the file changes: a second ask is free.
    private static final java.util.Map<String, ToolsCore.Summary> summaries = new java.util.HashMap<>();

    static synchronized ToolsCore.Summary summaryOf(File f, String stage) {
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified() + "|" + (stage == null ? "" : stage.trim());
        ToolsCore.Summary s = summaries.get(key);
        if (s != null) return s;
        s = ToolsCore.summarize(f.isFile() ? Diag.readFile(f, 0) : null, stage);
        if (summaries.size() > 4) summaries.clear();
        summaries.put(key, s);
        return s;
    }

    // ---- the device ------------------------------------------------------------------------------------------------
    static String deviceLines(Context c, String version) {
        StringBuilder b = new StringBuilder();
        b.append("app: TwinPocket ").append(version).append(" (").append(c.getPackageName()).append(")\n");
        b.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        if (Build.VERSION.SDK_INT >= 31) b.append("soc: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
        b.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ABIs ")
         .append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        b.append("cores: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            b.append("memory: ").append(mi.totalMem / 1048576).append(" MB total, ").append(mi.availMem / 1048576)
             .append(" MB available, low ").append(mi.lowMemory).append('\n');
            ConfigurationInfo ci = am.getDeviceConfigurationInfo();
            if (ci != null) b.append("opengl es: ").append(ci.getGlEsVersion()).append('\n');
        }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display d = wm.getDefaultDisplay();
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            b.append("display: ").append(m.widthPixels).append('x').append(m.heightPixels).append(", ")
             .append(String.format(Locale.US, "%.1f", d.getRefreshRate())).append(" Hz, ").append(m.densityDpi).append(" dpi\n");
        }
        StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
        b.append("storage: ").append(fs.getAvailableBytes() / 1048576).append(" MB free of ").append(fs.getTotalBytes() / 1048576).append(" MB\n");
        return b.toString();
    }

    static String controllers() {
        StringBuilder b = new StringBuilder();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            boolean pad = (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!pad) continue;
            Vibrator v = d.getVibrator();
            b.append("  ").append(d.getName()).append(" (vendor ").append(String.format(Locale.US, "%04x", d.getVendorId()))
             .append(", product ").append(String.format(Locale.US, "%04x", d.getProductId())).append(")")
             .append(v != null && v.hasVibrator() ? ", motor" : ", sem motor").append('\n');
        }
        return b.toString();
    }

    // ---- the component checks --------------------------------------------------------------------------------------
    /** The checks, each "OK" / "AVISO" / "FALHA" with what was seen; written to tests.txt as well. */
    static String runChecks(Context c, String version) {
        StringBuilder b = new StringBuilder();
        b.append("Verificação dos componentes — ").append(Diag.now()).append(" — versão ").append(version).append("\n\n");

        // storage: a file written, read back and removed in the app's own folder
        String storage;
        File t = null;
        try {
            t = File.createTempFile("te-check-", ".tmp", c.getCacheDir());
            byte[] data = "TwinPocket storage check".getBytes(StandardCharsets.UTF_8);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(t)) { o.write(data); o.getFD().sync(); }
            byte[] back = new byte[data.length];
            int n;
            try (FileInputStream in = new FileInputStream(t)) { n = in.read(back); }
            boolean ok = n == data.length && java.util.Arrays.equals(data, back);
            long free = new StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes() / 1048576;
            storage = (ok ? (free < 500 ? "AVISO" : "OK") : "FALHA") + "  escrita e leitura " + (ok ? "corretas" : "diferentes") + ", " + free + " MB livres";
        } catch (Exception e) {
            storage = "FALHA  " + e;
        } finally {
            if (t != null) t.delete();
        }
        b.append("Armazenamento   ").append(storage).append('\n');

        // settings: the file the game and the launcher share
        Settings st = new Settings(c);
        File ini = st.file();
        b.append("Configurações   ").append(ini.isFile() ? (ini.canWrite() ? "OK  ps2r.ini legível e gravável" : "FALHA  ps2r.ini sem escrita")
            : "AVISO  ps2r.ini ainda não existe (o jogo cria no primeiro início)").append('\n');

        // the disc image: present and readable (its name is not written)
        String[] disc = Diag.findDiscImage(c);
        if (disc[0] == null) b.append("Imagem do disco AVISO  nenhuma ISO/CHD selecionada\n");
        else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            b.append("Imagem do disco ").append(err == null ? "OK  " + r : "FALHA  " + err).append('\n');
        }

        // the native library
        File lib = new File(c.getApplicationInfo().nativeLibraryDir, "libmain.so");
        b.append("Biblioteca      ").append(lib.isFile() ? "OK  libmain.so " + lib.length() / 1048576 + " MB" : "FALHA  libmain.so não encontrada").append('\n');

        // graphics: what the system says of OpenGL ES (the game needs 3.2)
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo ci = am != null ? am.getDeviceConfigurationInfo() : null;
        int gl = ci != null ? ci.reqGlEsVersion : 0;
        b.append("Gráficos        ").append(gl >= 0x30002 ? "OK" : "FALHA").append("  OpenGL ES ").append(ci != null ? ci.getGlEsVersion() : "?")
         .append(gl >= 0x30002 ? "" : " (o jogo precisa do 3.2)").append('\n');

        // audio: an output and its native rate / burst
        AudioManager au = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (au == null) b.append("Áudio           FALHA  sem serviço de áudio\n");
        else {
            int outs = Build.VERSION.SDK_INT >= 23 ? au.getDevices(AudioManager.GET_DEVICES_OUTPUTS).length : -1;
            b.append("Áudio           ").append(outs == 0 ? "FALHA" : "OK").append("  ").append(outs >= 0 ? outs + " saída(s), " : "")
             .append(au.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)).append(" Hz, bloco de ")
             .append(au.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)).append(" frames, driver escolhido ")
             .append("OpenSL ES (fixo)").append('\n');
        }

        // controls and vibration
        String pads = controllers();
        b.append("Controles       ").append(pads.isEmpty() ? "AVISO  nenhum controle detectado" : "OK").append('\n');
        if (!pads.isEmpty()) b.append(pads);
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        boolean motor = v != null && v.hasVibrator();
        b.append("Vibração        ").append(motor || pads.contains(", motor") ? "OK" : "AVISO").append("  motor do aparelho: ")
         .append(motor ? "sim" : "não").append(", opção do jogo: ").append(st.get("android.rumble", "auto")).append('\n');

        // memory
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long avail = mi.availMem / 1048576;
            b.append("Memória         ").append(avail < 600 ? "AVISO" : "OK").append("  ").append(avail).append(" MB disponíveis de ")
             .append(mi.totalMem / 1048576).append(" MB").append('\n');
        }

        // the last run
        String stage = Diag.readFile(new File(Diag.logsDir(c), "stage-game.txt"), 2000);
        String words = ToolsCore.stageWords(stage);
        boolean bad = stage != null && (stage.trim().startsWith("crash") || (stage.trim().startsWith("running") && !Diag.processAlive(c, "game")));
        b.append("Última execução ").append(stage == null ? "AVISO" : bad ? "FALHA" : "OK").append("  ").append(words).append('\n');
        String out = b.toString();
        Diag.write(new File(Diag.logsDir(c), TESTS), out);
        return out;
    }

    // ---- the ZIP ---------------------------------------------------------------------------------------------------
    /** The technical records as a ZIP (the files of files/logs, a copy of ps2r.ini, the device's lines); the entries written. */
    static int exportZip(Context c, String version, OutputStream out) throws java.io.IOException {
        File dir = Diag.logsDir(c);
        Diag.write(new File(dir, DEVICE), deviceLines(c, version) + "\ncontroles:\n" + controllers());
        int n = 0;
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            List<File> files = Diag.logFiles(c);
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".tmp")) continue;
                n += put(z, "logs/" + name, f);
            }
            n += put(z, "settings/ps2r.ini", new Settings(c).file());
            z.putNextEntry(new ZipEntry("LEIA-ME.txt"));
            z.write(("TwinPocket " + version + " — registros técnicos (" + Diag.now() + ")\n"
                + "logs/report.txt: o relatório completo; logs/sessions.tsv: o histórico de sessões; logs/tests.txt: a última verificação;\n"
                + "logs/device.txt: o aparelho; logs/latest-game.log e previous-game.log: as duas últimas execuções do jogo.\n"
                + "Sem saves, cartões de memória ou imagem do disco.\n").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            n++;
        }
        return n;
    }

    private static int put(ZipOutputStream z, String name, File f) throws java.io.IOException {
        if (f == null || !f.isFile()) return 0;
        z.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
        }
        z.closeEntry();
        return 1;
    }

    /** The logs folder emptied except the session history (it survives: it is what tells the versions apart). */
    static int clearLogs(Context c) {
        int n = 0;
        for (File f : Diag.logFiles(c)) {
            if (f.getName().equals(SESSIONS)) continue;
            if (f.delete()) n++;
        }
        return n;
    }

    // ---- the text shown -----------------------------------------------------------------------------------------------
    /** The last run's performance: the summary (min / average / max), the settings, then one row per ~10 s window and per
     *  telemetry sample. null when the run has no measurement yet. Plain Java on purpose (ToolsCore's figures). */
    static String performanceText(ToolsCore.Summary s) {
        if (s.count() == 0 && s.power.isEmpty() && s.hitches.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        b.append(Lang.T("RESUMO (mín / média / máx)")).append('\n');
        row3(b, Lang.T("FPS"), s.min(ToolsCore.FPS), s.avg(ToolsCore.FPS), s.max(ToolsCore.FPS), 1, "");
        row3(b, Lang.T("Tempo de quadro"), s.min(ToolsCore.FT_MIN), s.avg(ToolsCore.FRAME_MS), s.max(ToolsCore.GAP_MS), 2, " ms");
        if (s.avg(ToolsCore.FT_P95) >= 0) {
            b.append(String.format(Locale.US, "  %-22s p50 %s · p95 %s · p99 %s ms · jitter %s ms\n", Lang.T("Frame pacing"),
                ToolsCore.num(s.avg(ToolsCore.FT_P50), 2), ToolsCore.num(s.avg(ToolsCore.FT_P95), 2), ToolsCore.num(s.max(ToolsCore.FT_P99), 2),
                ToolsCore.num(s.avg(ToolsCore.JITTER), 2)));
            b.append(String.format(Locale.US, "  %-22s %d %s %d %s\n", Lang.T("Quadros atrasados"), Math.max(0, s.sum(ToolsCore.LATE)),
                Lang.T("acima de 1,5× a mediana ·"), Math.max(0, s.sum(ToolsCore.VERY_LATE)), Lang.T("acima de 2,5×")));
        }
        if (s.gpuUnavailable) b.append(String.format(Locale.US, "  %-22s %s\n", Lang.T("GPU por quadro"), Lang.T("indisponível neste aparelho")));
        else row3(b, Lang.T("GPU por quadro"), s.min(ToolsCore.GPU_MIN), s.avg(ToolsCore.GPU_AVG), s.max(ToolsCore.GPU_MAX), 2, " ms");
        row3(b, Lang.T("CPU (um núcleo)"), s.min(ToolsCore.CPU), s.avg(ToolsCore.CPU), s.max(ToolsCore.CPU), 0, " %");
        b.append(String.format(Locale.US, "  %-22s %s MB\n", Lang.T("Memória máxima"), ToolsCore.num(s.max(ToolsCore.MEMORY), 0)));
        row3(b, Lang.T("Temperatura bateria"), s.powerMin(ToolsCore.TEMP), s.powerAvg(ToolsCore.TEMP), s.powerMax(ToolsCore.TEMP), 1, " °C");
        row3(b, Lang.T("Corrente"), s.powerMin(ToolsCore.CURRENT), s.powerAvg(ToolsCore.CURRENT), s.powerMax(ToolsCore.CURRENT), 0, " mA");
        if (s.powerAvg(ToolsCore.WATTS) >= 0)
            row3(b, Lang.T("Consumo estimado"), s.powerMin(ToolsCore.WATTS), s.powerAvg(ToolsCore.WATTS), s.powerMax(ToolsCore.WATTS), 2, " W");
        else
            b.append(String.format(Locale.US, "  %-22s %s\n", Lang.T("Consumo estimado"),
                s.charged() ? Lang.T("não estimado (no carregador)") : Lang.T("indisponível")));
        int worst = (int) s.powerMax(ToolsCore.THERMAL);
        b.append(String.format(Locale.US, "  %-22s %s%s\n", Lang.T("Estado térmico (pior)"),
            worst >= 0 ? Lang.T(ToolsCore.thermalWords(worst)) + " (" + worst + ")" : Lang.T("indisponível"),
            s.powerMax(ToolsCore.HEADROOM) >= 0 ? " · " + Lang.T("folga térmica máx. ") + ToolsCore.num(s.powerMax(ToolsCore.HEADROOM), 2) : ""));
        double rmin = s.powerMin(ToolsCore.REFRESH), rmax = s.powerMax(ToolsCore.REFRESH);
        b.append(String.format(Locale.US, "  %-22s %s\n", Lang.T("Atualização da tela"), rmax < 0 ? Lang.T("indisponível")
            : (Math.abs(rmax - rmin) < 0.05 ? ToolsCore.num(rmax, 1) + " Hz" : ToolsCore.num(rmin, 1) + "–" + ToolsCore.num(rmax, 1) + " Hz")));
        if (s.displayMode != null) b.append(String.format(Locale.US, "  %-22s %s\n", Lang.T("Tela"), s.displayMode));
        if (s.displayMode != null || !s.hitches.isEmpty()) {
            b.append(String.format(Locale.US, "  %-22s %d%s\n", Lang.T("Engasgos (> 50 ms)"), s.playHitches(),
                s.playHitches() > 0 ? " · " + Lang.T("pior ") + ToolsCore.num(s.worstPlayHitch(), 0) + " ms" : ""));
        }
        java.util.Map<String, Integer> configs = s.configCounts();
        if (!configs.isEmpty()) {
            b.append('\n').append(Lang.T("CONFIGURAÇÕES GRÁFICAS (janelas de ~10 s)")).append('\n');
            for (java.util.Map.Entry<String, Integer> e : configs.entrySet())
                b.append("  ").append(e.getValue()).append("× ").append(e.getKey()).append('\n');
        }
        if (!s.hitches.isEmpty()) {
            b.append('\n').append(Lang.T("ENGASGOS (quadros acima de 50 ms)")).append('\n');
            for (java.util.Map.Entry<String, Integer> e : s.causes().entrySet())
                b.append("  ").append(e.getValue()).append("× ").append(Lang.T(e.getKey())).append('\n');
            int loading = 0, minimized = 0;
            for (ToolsCore.Hitch h : s.hitches) {
                if (h.minimized) minimized++;
                else if (h.loading) loading++;
            }
            if (loading > 0) b.append("  ").append(loading).append("× ").append(Lang.T("tela de carregamento (esperado)")).append('\n');
            if (minimized > 0) b.append("  ").append(minimized).append("× ").append(Lang.T("app minimizado (esperado)")).append('\n');
            b.append(String.format(Locale.US, "%-8s %-7s %-7s %-7s %-6s %-7s %-4s %-4s %-5s %s\n", "quadro", "ms", "jogo", "render", "geom.", "shader",
                "tex", "malha", Lang.T("área"), Lang.T("causa")));
            int shown = 0;
            for (ToolsCore.Hitch h : s.hitches) {
                if (!h.inPlay()) continue;
                if (shown++ >= 60) {
                    b.append("  …\n");
                    break;
                }
                String area = s.areaNear(h.boundary);
                if (area != null) {
                    int slash = Math.max(area.lastIndexOf('\\'), area.lastIndexOf('/'));
                    area = area.substring(slash + 1);
                }
                b.append(String.format(Locale.US, "%-8d %-7s %-7s %-7s %-6s %-7s %-4d %-4d %-5s %s\n", h.present, ToolsCore.num(h.ms, 0),
                    ToolsCore.num(h.gameMs, 0), ToolsCore.num(h.drawMs + h.swapMs, 0), ToolsCore.num(h.buildWaitMs, 0),
                    (h.shaders + h.programs) > 0 ? (h.shaders + h.programs) + "/" + ToolsCore.num(h.shaderMs, 0) : "0",
                    h.textures, h.meshes, area != null ? area : "—", Lang.T(h.cause(s))));
            }
            // the game thread's own tick lines for those hitches: the port's load work in them and the sampled sections
            StringBuilder detail = new StringBuilder();
            int details = 0;
            for (ToolsCore.Hitch h : s.hitches) {
                if (!h.inPlay()) continue;
                ToolsCore.Tick t = s.tickNear(h.boundary);
                if (t == null) continue;
                if (details++ >= 40) {
                    detail.append("  …\n");
                    break;
                }
                detail.append(String.format(Locale.US, "  %d: %s ms · %s %s ms%s%s\n", h.present, ToolsCore.num(t.ms, 0),
                    Lang.T("port"), ToolsCore.num(t.loadMs, 0), t.load.isEmpty() ? "" : " (" + t.load + ")",
                    t.prof.isEmpty() ? "" : " · " + Lang.T("perfil: ") + t.prof));
            }
            if (details > 0) {
                b.append('\n').append(Lang.T("ENGASGOS: A THREAD DO JOGO (\"(own code)\" = código do jogo; o resto, o port)")).append('\n');
                b.append(detail);
            }
        }
        if (s.count() > 0) {
            b.append('\n').append(Lang.T("JANELAS")).append('\n');
            b.append(String.format(Locale.US, "%-4s %-6s %-7s %-7s %-7s %-7s %-6s %-5s %-5s\n", "#", "FPS", "quadro", "p95", "máx", "GPU", "jitter", "CPU%", "MB"));
            int i = 1;
            for (ToolsCore.Window w : s.windows) {
                b.append(String.format(Locale.US, "%-4d %-6s %-7s %-7s %-7s %-7s %-6s %-5s %-5s\n", i++, ToolsCore.num(w.fps, 1),
                    ToolsCore.num(ToolsCore.value(w, ToolsCore.FRAME_MS), 2), ToolsCore.num(w.ftP95, 2),
                    ToolsCore.num(ToolsCore.value(w, ToolsCore.GAP_MS), 1), ToolsCore.num(w.gpuAvg, 2), ToolsCore.num(w.jitter, 2),
                    ToolsCore.num(w.cpuOneCore, 0), ToolsCore.num(w.peakMb, 0)));
            }
        }
        if (!s.power.isEmpty()) {
            b.append('\n').append(Lang.T("ENERGIA E TEMPERATURA (a cada 10 s)")).append('\n');
            b.append(String.format(Locale.US, "%-4s %-6s %-6s %-6s %-6s %-9s %-8s %-6s\n", "#", "°C", "mA", "W", "bat%", "fonte", "térmico", "Hz"));
            int i = 1;
            for (ToolsCore.Power p : s.power) {
                b.append(String.format(Locale.US, "%-4d %-6s %-6s %-6s %-6s %-9s %-8s %-6s\n", i++, ToolsCore.num(p.tempC, 1),
                    ToolsCore.num(p.currentMa, 0), ToolsCore.num(p.powerW, 2), p.level >= 0 ? Integer.toString(p.level) : "—",
                    p.source == null ? "—" : "battery".equals(p.source) ? Lang.T("bateria") : Lang.T("carregador"),
                    p.thermal >= 0 ? Integer.toString(p.thermal) : "—", ToolsCore.num(p.refreshHz, 1)));
            }
        }
        return b.toString();
    }

    private static void row3(StringBuilder b, String label, double min, double avg, double max, int decimals, String unit) {
        if (avg < 0 && max < 0) {
            b.append(String.format(Locale.US, "  %-22s %s\n", label, Lang.T("indisponível")));
            return;
        }
        b.append(String.format(Locale.US, "  %-22s %s / %s / %s%s\n", label, ToolsCore.num(min, decimals), ToolsCore.num(avg, decimals),
            ToolsCore.num(max, decimals), unit));
    }
}
