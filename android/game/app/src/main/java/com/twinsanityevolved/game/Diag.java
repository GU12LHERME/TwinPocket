package com.twinsanityevolved.game;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Java half of the start-up diagnostics: the same logs folder as the native half (runner/te_android_diag.cpp:
 * files/logs, latest-&lt;process&gt;.log, stage-&lt;process&gt;.txt), the Java log lines (java-&lt;process&gt;.log, logcat tag
 * TwinsanityEvolved), an uncaught-exception record per process (java-crash-&lt;process&gt;.txt), the disc image lookup the
 * native side makes, and the helpers the launcher's report uses.
 */
final class Diag {
    static final String TAG = "TwinsanityEvolved";

    private Diag() {}

    static File logsDir(Context c) {
        File d = new File(c.getFilesDir(), "logs");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    static synchronized void log(Context c, String proc, String line) {
        Log.i(TAG, "[java:" + proc + "] " + line);
        try (FileOutputStream out = new FileOutputStream(new File(logsDir(c), "java-" + proc + ".log"), true)) {
            out.write(("[" + now() + "] " + line + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) {
        }
    }

    static void installCrashHandler(final Context context, final String proc) {
        final Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String text = "[" + now() + "] uncaught Java exception in thread " + t.getName() + " (process " + proc + ")\n" + sw;
                    Log.e(TAG, text);
                    try (FileOutputStream out = new FileOutputStream(new File(logsDir(c), "java-crash-" + proc + ".txt"), false)) {
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
        log(c, proc, "process started: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), ABIs " + android.text.TextUtils.join(",", Build.SUPPORTED_ABIS));
    }

    static String readFile(File f, int maxChars) {
        if (f == null || !f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 8L * 1024 * 1024)];
            int n = 0;
            while (n < data.length) {
                int r = in.read(data, n, data.length - n);
                if (r <= 0) break;
                n += r;
            }
            String s = new String(data, 0, n, StandardCharsets.UTF_8);
            if (maxChars > 0 && s.length() > maxChars) s = "...(" + (s.length() - maxChars) + " characters before)...\n" + s.substring(s.length() - maxChars);
            return s;
        } catch (Exception e) {
            return "(cannot read " + f.getName() + ": " + e + ")";
        }
    }

    static String tailLines(File f, int lines) {
        String s = readFile(f, 0);
        if (s == null) return null;
        String[] all = s.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) b.append(all[i]).append('\n');
        return b.toString();
    }

    static boolean processAlive(Context c, String suffix) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
        if (list == null) return false;
        String name = c.getPackageName() + ":" + suffix;
        for (ActivityManager.RunningAppProcessInfo p : list) if (name.equals(p.processName)) return true;
        return false;
    }

    /** The disc image copied in by the launcher's picker (Storage Access Framework): private to the app, every process. */
    static File importedDisc(Context c) {
        return new File(new File(c.getFilesDir(), "disc"), "twinsanity.iso");
    }

    /**
     * The disc image the game will use: the copy the picker made (files/disc/twinsanity.iso) first, else the lookup in the
     * app's external folder (runner/te_android_diag.cpp FindDiscImage). [path, how] (path null when none).
     */
    static String[] findDiscImage(Context c) {
        File imported = importedDisc(c);
        if (imported.isFile()) return new String[] {imported.getAbsolutePath(), "copiada pelo seletor de ISO (armazenamento privado do app)"};
        return findDiscImage(c.getExternalFilesDir(null));
    }

    /** The disc image lookup of runner/te_android_diag.cpp FindDiscImage: [path, how] (path null when none). */
    static String[] findDiscImage(File dir) {
        if (dir == null) return new String[] {null, "the app's external files folder is not available"};
        String how = "";
        File named = new File(dir, "disc-image.txt");
        if (named.isFile()) {
            String first = readFile(named, 0);
            if (first != null) {
                first = first.split("\n")[0].trim();
                if (!first.isEmpty()) {
                    File f = new File(first);
                    if (f.isFile()) return new String[] {f.getAbsolutePath(), "named by disc-image.txt"};
                    how = "disc-image.txt names " + first + ", which is not a readable file here";
                }
            }
        }
        File iso = new File(dir, "twinsanity.iso");
        if (iso.isFile()) return new String[] {iso.getAbsolutePath(), "twinsanity.iso"};
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".iso")) return new String[] {f.getAbsolutePath(), "the first .iso in the folder"};
            }
        }
        if (how.isEmpty()) how = "no twinsanity.iso, no .iso file and no disc-image.txt in " + dir.getAbsolutePath();
        return new String[] {null, how};
    }

    /** A one-line check of a disc image: ISO 9660 "CD001" at sector 16 (2048- or 2352-byte sectors). Null when usable. */
    static String checkDiscImage(String path, StringBuilder report) {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            long len = f.length();
            long[][] layouts = {{2048, 0}, {2352, 16}, {2352, 24}};
            for (long[] l : layouts) {
                long at = 16 * l[0] + l[1];
                if (at + 2048 > len) continue;
                byte[] pvd = new byte[2048];
                f.seek(at);
                f.readFully(pvd);
                if (pvd[0] == 1 && pvd[1] == 'C' && pvd[2] == 'D' && pvd[3] == '0' && pvd[4] == '0' && pvd[5] == '1') {
                    String vol = new String(pvd, 40, 32, StandardCharsets.US_ASCII).trim();
                    report.append(String.format(Locale.US, "%.1f MB, ISO 9660, %d-byte sectors, volume \"%s\"", len / 1048576.0, l[0], vol));
                    if (l[0] == 2048) {
                        String boot = bootProgram(f, pvd);
                        report.append(boot != null ? ", SYSTEM.CNF BOOT2 = " + boot : ", no SYSTEM.CNF in the root folder");
                    }
                    return null;
                }
            }
            report.append(String.format(Locale.US, "%.1f MB", len / 1048576.0));
            return "no ISO 9660 volume descriptor at sector 16 (not a PS2 DVD image?)";
        } catch (Exception e) {
            return "cannot read it: " + e.getMessage();
        }
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    /** SYSTEM.CNF's BOOT2 line from the image's root folder (2048-byte sectors): the program the PS2 boots, or null. */
    static String bootProgram(RandomAccessFile f, byte[] pvd) {
        try {
            int rootLba = le32(pvd, 156 + 2), rootLen = le32(pvd, 156 + 10);
            if (rootLba <= 0 || rootLen <= 0 || rootLen > 1 << 20) return null;
            byte[] dir = new byte[rootLen];
            f.seek((long) rootLba * 2048);
            f.readFully(dir);
            int p = 0;
            while (p < dir.length) {
                int len = dir[p] & 0xFF;
                if (len == 0) { p = (p / 2048 + 1) * 2048; continue; }
                int nameLen = dir[p + 32] & 0xFF;
                String name = new String(dir, p + 33, nameLen, StandardCharsets.US_ASCII).toUpperCase(Locale.US);
                if (name.startsWith("SYSTEM.CNF")) {
                    int lba = le32(dir, p + 2), size = Math.min(le32(dir, p + 10), 4096);
                    byte[] cnf = new byte[Math.max(size, 0)];
                    f.seek((long) lba * 2048);
                    f.readFully(cnf);
                    for (String line : new String(cnf, StandardCharsets.US_ASCII).split("[\\r\\n]+")) {
                        if (line.trim().toUpperCase(Locale.US).startsWith("BOOT2")) {
                            int eq = line.indexOf('=');
                            return eq >= 0 ? line.substring(eq + 1).trim() : line.trim();
                        }
                    }
                    return null;
                }
                p += len;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** This app's own logcat lines (an app reads its own uid's log; the previous, crashed process's lines included). */
    static String captureLogcat(int lines) {
        StringBuilder b = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-v", "threadtime", "-t", String.valueOf(lines)});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) b.append(line).append('\n');
            }
            p.waitFor();
        } catch (Exception e) {
            b.append("(logcat could not be read: ").append(e).append(")\n");
        }
        return b.toString();
    }

    static List<File> logFiles(Context c) {
        List<File> out = new ArrayList<>();
        File[] all = logsDir(c).listFiles();
        if (all != null) for (File f : all) if (f.isFile()) out.add(f);
        return out;
    }

    static void write(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) {
        }
    }
}
