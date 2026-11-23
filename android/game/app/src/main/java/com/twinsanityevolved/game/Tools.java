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
            String log = Diag.readFile(f, 0);
            ToolsCore.Summary s = ToolsCore.summarize(log, name.startsWith("latest") ? stage : null);
            if (s.start == null) continue;
            String key = s.start + "#" + f.length();
            if (text != null && ToolsCore.hasSession(text, key)) continue;
            if (name.startsWith("previous")) s.endState = guessEnd(log);
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
        return ToolsCore.summarize(Diag.readFile(new File(dir, "latest-game.log"), 0), Diag.readFile(new File(dir, "stage-game.txt"), 2000));
    }

    // ---- the device ------------------------------------------------------------------------------------------------
    static String deviceLines(Context c, String version) {
        StringBuilder b = new StringBuilder();
        b.append("app: Crash Twinsanity ").append(version).append(" (").append(c.getPackageName()).append(")\n");
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
        try {
            File t = new File(c.getCacheDir(), "te-check.tmp");
            byte[] data = "crash twinsanity storage check".getBytes(StandardCharsets.UTF_8);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(t)) { o.write(data); o.getFD().sync(); }
            byte[] back = new byte[data.length];
            int n;
            try (FileInputStream in = new FileInputStream(t)) { n = in.read(back); }
            boolean ok = n == data.length && java.util.Arrays.equals(data, back);
            t.delete();
            long free = new StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes() / 1048576;
            storage = (ok ? (free < 500 ? "AVISO" : "OK") : "FALHA") + "  escrita e leitura " + (ok ? "corretas" : "diferentes") + ", " + free + " MB livres";
        } catch (Exception e) {
            storage = "FALHA  " + e;
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
             .append(st.get(Settings.AUDIO_DRIVER, LauncherActivity.DEFAULT_AUDIO_DRIVER)).append('\n');
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
            z.write(("Crash Twinsanity Android " + version + " — registros técnicos (" + Diag.now() + ")\n"
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
    static String performanceText(ToolsCore.Summary s) {
        if (s.count() == 0) return null;
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%-8s %-8s %-10s %-10s %-8s %-8s\n", "janela", "FPS", "quadro ms", "pior ms", "CPU %", "mem MB"));
        int i = 1;
        for (ToolsCore.Window w : s.windows) {
            b.append(String.format(Locale.US, "%-8d %-8s %-10s %-10s %-8s %-8s\n", i++, ToolsCore.num(w.fps, 1), ToolsCore.num(w.frameMs, 2),
                ToolsCore.num(w.longestGapMs, 1), ToolsCore.num(w.cpuOneCore, 0), ToolsCore.num(w.peakMb, 0)));
        }
        return b.toString();
    }
}
