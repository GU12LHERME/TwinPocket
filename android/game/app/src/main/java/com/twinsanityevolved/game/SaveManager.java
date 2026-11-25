package com.twinsanityevolved.game;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The game's saves as the launcher manages them (plain Java: tools/android/TestSaves.java runs it on the CI's JVM).
 *
 * Where they are: the game folder (files/game) holds the memory cards as folders -- memcards (the vanilla card),
 * memcards-mods (the card while a mod is on), memcards-&lt;serial&gt; (a region's own card) -- each with a folder per card
 * port (mc0, mc1) and, in it, a folder per save (the PS2's own layout: icon.sys, the icon, the data files).
 *
 * - list: every save folder of every card, with its title (icon.sys, Shift-JIS), size and last change.
 * - exportZip: the card folders into one .zip (paths kept relative to the game folder).
 * - importZip: a .zip of this layout back -- every entry checked first (only memcards* paths, no "..", no absolute path,
 *   a size cap), the cards it holds replace the current ones of the same name; the launcher makes a backup before.
 * - delete: one save folder, after the launcher's confirmation and backup.
 * Nothing here runs while the game runs (the launcher refuses it then: the game writes the cards).
 */
final class SaveManager {
    static final int MAX_IMPORT_ENTRIES = 16384;
    static final long MAX_IMPORT_BYTES = 256L << 20;   // a memory card is 8 MB: far more than any real backup

    static final class Save {
        final File dir;          // the save's folder
        final String card;       // the card folder's name (memcards, memcards-mods, ...)
        final String port;       // mc0 / mc1
        final String title;      // icon.sys's title, else the folder's name
        final long bytes;
        final long modified;

        Save(File dir, String card, String port, String title, long bytes, long modified) {
            this.dir = dir;
            this.card = card;
            this.port = port;
            this.title = title;
            this.bytes = bytes;
            this.modified = modified;
        }
    }

    private SaveManager() {}

    static boolean isCardFolder(String name) {
        return name.equals("memcards") || (name.startsWith("memcards-") && name.length() > 9);
    }

    static List<File> cardFolders(File gameDir) {
        List<File> out = new ArrayList<>();
        File[] all = gameDir.listFiles();
        if (all == null) return out;
        Arrays.sort(all);
        for (File f : all) if (f.isDirectory() && isCardFolder(f.getName())) out.add(f);
        return out;
    }

    static List<Save> list(File gameDir) {
        List<Save> out = new ArrayList<>();
        for (File card : cardFolders(gameDir)) {
            File[] ports = card.listFiles();
            if (ports == null) continue;
            Arrays.sort(ports);
            for (File port : ports) {
                if (!port.isDirectory()) continue;
                File[] saves = port.listFiles();
                if (saves == null) continue;
                Arrays.sort(saves);
                for (File s : saves) {
                    if (!s.isDirectory()) continue;
                    out.add(new Save(s, card.getName(), port.getName(), title(s), size(s), newest(s)));
                }
            }
        }
        return out;
    }

    /** icon.sys's title (offset 0xC0, 68 bytes, Shift-JIS; the line break at the second line's offset ignored) */
    static String title(File saveDir) {
        File icon = new File(saveDir, "icon.sys");
        if (icon.length() >= 0xC0 + 68) {
            byte[] b = new byte[0xC0 + 68];
            try (InputStream in = new FileInputStream(icon)) {
                int got = 0;
                while (got < b.length) {
                    int n = in.read(b, got, b.length - got);
                    if (n < 0) break;
                    got += n;
                }
                if (got == b.length && b[0] == 'P' && b[1] == 'S' && b[2] == '2' && b[3] == 'D') {
                    int end = 0xC0;
                    while (end < b.length && b[end] != 0) end++;
                    String t = new String(b, 0xC0, end - 0xC0, Charset.forName("Shift_JIS"));
                    t = toAscii(t).trim().replaceAll("\\s+", " ");
                    if (!t.isEmpty()) return t;
                }
            } catch (Exception ignored) {
                // an unreadable icon.sys: the folder's name
            }
        }
        return saveDir.getName();
    }

    // the PS2's titles are full-width Shift-JIS: their full-width forms to ASCII
    static String toAscii(String s) {
        StringBuilder r = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) r.append((char) (c - 0xFEE0));
            else if (c == 0x3000) r.append(' ');
            else r.append(c);
        }
        return r.toString();
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] all = f.listFiles();
        if (all != null) for (File c : all) n += size(c);
        return n;
    }

    static long newest(File f) {
        long t = f.lastModified();
        File[] all = f.listFiles();
        if (all != null) for (File c : all) t = Math.max(t, newest(c));
        return t;
    }

    /** The card folders under gameDir into `out` as a .zip; the number of files written. */
    static synchronized int exportZip(File gameDir, OutputStream out) throws IOException {
        int files = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (File card : cardFolders(gameDir)) files += add(zip, card, card.getName());
        }
        return files;
    }

    private static int add(ZipOutputStream zip, File f, String path) throws IOException {
        if (f.isDirectory()) {
            int n = 0;
            zip.putNextEntry(new ZipEntry(path + "/"));
            zip.closeEntry();
            File[] all = f.listFiles();
            if (all != null) {
                Arrays.sort(all);
                for (File c : all) n += add(zip, c, path + "/" + c.getName());
            }
            return n;
        }
        ZipEntry e = new ZipEntry(path);
        e.setTime(f.lastModified());
        zip.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            copy(in, zip);
        }
        zip.closeEntry();
        return 1;
    }

    /** Why an entry name may not be extracted (null: it may) */
    static String refuse(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\") || name.contains(":"))
            return "caminho inválido: " + name;
        for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) return "caminho inválido: " + name;
        String top = name.split("/")[0];
        if (name.length() > 1024 || name.contains("//") || name.split("/").length > 32 || top.endsWith(".replaced") || !isCardFolder(top)) return "não é um cartão de memória: " + name;
        return null;
    }

    /**
     * A .zip of card folders into gameDir: checked whole first (refuse(), the size cap; nothing written when an entry is
     * refused), then each card folder it holds replaces the one of the same name. The number of files written.
     */
    static synchronized int importZip(File zipFile, File gameDir) throws IOException {
        List<String> cards = new ArrayList<>();
        long total = 0;
        int files = 0;
        int entries = 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = in.getNextEntry()) != null) {
                if (++entries > MAX_IMPORT_ENTRIES) throw new IOException("backup com entradas demais");
                if (!names.add(e.getName().replaceFirst("/$", ""))) throw new IOException("entrada duplicada: " + e.getName());
                String why = refuse(e.getName());
                if (why != null) throw new IOException(why);
                String top = e.getName().split("/")[0];
                if (!e.isDirectory() && e.getName().equals(top)) throw new IOException("cartão deve ser uma pasta");
                if (!cards.contains(top)) cards.add(top);
                {
                    if (!e.isDirectory()) files++;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (e.isDirectory()) throw new IOException("diretório com dados");
                        total += n;
                        if (total > MAX_IMPORT_BYTES) throw new IOException("backup grande demais (mais de 256 MB)");
                    }
                }
            }
        }
        if (files == 0) throw new IOException("o arquivo não tem nenhum save");
        // checked: extracted into a staging folder, then each card swapped in
        File staging = new File(gameDir, ".import-staging");
        if (staging.exists()) throw new IOException("importação anterior pendente; preserve a pasta para recuperação");
        if (!staging.mkdirs()) throw new IOException("não foi possível criar " + staging);
        try {
            try (ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    File target = new File(staging, e.getName());
                    if (!target.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator))
                        throw new IOException("caminho inválido: " + e.getName());
                    if (e.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("não foi possível criar pasta");
                        continue;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("não foi possível criar pasta");
                    try (OutputStream out = new FileOutputStream(target)) {
                        copy(in, out);
                    }
                    if (e.getTime() > 0) target.setLastModified(e.getTime());
                }
            }
            // Keep all originals until every replacement succeeds.
            File originals = new File(staging, ".originals");
            if (!originals.mkdir()) throw new IOException("não foi possível criar backup temporário");
            List<String> moved = new ArrayList<>();
            List<String> installed = new ArrayList<>();
            try {
                for (String card : cards) {
                    File now = new File(gameDir, card);
                    File old = new File(originals, card);
                    if (now.exists()) {
                        if (!now.renameTo(old)) throw new IOException("não foi possível substituir " + card);
                        moved.add(card);
                    }
                    if (!new File(staging, card).renameTo(now))
                        throw new IOException("não foi possível instalar " + card);
                    installed.add(card);
                }
            } catch (IOException failure) {
                boolean restored = true;
                for (String card : installed)
                    if (!deleteTree(new File(gameDir, card))) restored = false;
                for (String card : moved)
                    if (!new File(originals, card).renameTo(new File(gameDir, card))) restored = false;
                if (!restored) throw new IOException("restauração incompleta; originais preservados em " + originals, failure);
                throw failure;
            }
            deleteTree(originals);
        } finally {
            File originals = new File(staging, ".originals");
            String[] remaining = originals.list();
            if (!originals.exists() || (remaining != null && remaining.length == 0)) deleteTree(staging);
        }
        return files;
    }

    static synchronized boolean deleteTree(File f) {
        if (!f.exists()) return true;
        File[] all = f.listFiles();
        if (all != null) for (File c : all) deleteTree(c);
        return f.delete();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes + 512) / 1024 + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
