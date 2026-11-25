package com.twinsanityevolved.game;

import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.*;

// The launcher's save manager (android/game/.../SaveManager.java) on a plain JVM: listing with the icon.sys title, the
// export / import round trip, the import's checks (paths outside the cards, "..", nothing written on a refused file).
public final class TestSaves {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) failures++; }

    static void write(File f, byte[] b) throws IOException { f.getParentFile().mkdirs(); try (OutputStream o = new FileOutputStream(f)) { o.write(b); } }

    static byte[] iconSys(String fullWidthTitle) throws Exception {
        byte[] b = new byte[964];
        b[0] = 'P'; b[1] = 'S'; b[2] = '2'; b[3] = 'D';
        byte[] t = fullWidthTitle.getBytes("Shift_JIS");
        System.arraycopy(t, 0, b, 0xC0, Math.min(t.length, 68));
        return b;
    }

    public static void main(String[] a) throws Exception {
        File game = Files.createTempDirectory("game").toFile();
        File save = new File(game, "memcards/mc0/BASLUS-20909TWIN");
        write(new File(save, "icon.sys"), iconSys("ＣＲＡＳＨ　ＴＷＩＮＳＡＮＩＴＹ"));
        write(new File(save, "data"), new byte[5000]);
        write(new File(game, "memcards-mods/mc0/OTHER/data"), new byte[10]);
        write(new File(game, "ps2r.ini"), "x=1".getBytes());   // not a card: never touched

        List<SaveManager.Save> saves = SaveManager.list(game);
        check(saves.size() == 2, "two saves listed across the vanilla and the -mods card (" + saves.size() + ")");
        check(saves.get(0).title.equals("CRASH TWINSANITY"), "the icon.sys title, full-width to ASCII: '" + saves.get(0).title + "'");
        check(saves.get(1).title.equals("OTHER"), "no icon.sys: the folder's name");
        check(saves.get(0).bytes == 964 + 5000, "the save's size");

        File zip = new File(game, "../backup-test.zip");
        int n;
        try (OutputStream o = new FileOutputStream(zip)) { n = SaveManager.exportZip(game, o); }
        check(n == 3, "export: the three files of the cards (" + n + ")");

        SaveManager.deleteTree(save);
        check(SaveManager.list(game).size() == 1, "a save deleted");
        n = SaveManager.importZip(zip, game);
        check(n == 3 && SaveManager.list(game).size() == 2 && new File(save, "data").length() == 5000, "import restores it");
        check(new File(game, "ps2r.ini").exists(), "the settings file untouched");
        check(!new File(game, ".import-staging").exists(), "no staging folder left");

        // refused archives: nothing written
        for (String bad : new String[] {"../evil", "memcards/../../evil", "/abs/memcards/x", "ps2r.ini", "other/x", "memcards"}) {
            File z = new File(game, "../bad.zip");
            try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(z))) { o.putNextEntry(new ZipEntry(bad)); o.write(1); o.closeEntry(); }
            boolean refused = false;
            try { SaveManager.importZip(z, game); } catch (IOException e) { refused = true; }
            check(refused && SaveManager.list(game).size() == 2, "refused entry '" + bad + "': nothing changed");
        }
        File empty = new File(game, "../empty.zip");
        try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(empty))) { o.putNextEntry(new ZipEntry("memcards/")); o.closeEntry(); }
        boolean refused = false;
        try { SaveManager.importZip(empty, game); } catch (IOException e) { refused = true; }
        check(refused && SaveManager.list(game).size() == 2, "a zip with no save is refused (the cards kept)");
        // A file/directory conflict in a second card must leave both existing cards untouched.
        File conflict = new File(game, "../conflict.zip");
        try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(conflict))) {
            for (String name : new String[]{"memcards/mc0/new/data", "memcards-mods/mc0", "memcards-mods/mc0/child"}) {
                o.putNextEntry(new ZipEntry(name)); o.write(1); o.closeEntry();
            }
        }
        refused = false;
        try { SaveManager.importZip(conflict, game); } catch (IOException e) { refused = true; }
        check(refused && new File(save, "data").length() == 5000
            && new File(game, "memcards-mods/mc0/OTHER/data").length() == 10, "conflicting archive preserves both cards");
        check(SaveManager.refuse("memcards-mods.replaced/mc0/data") != null, "reserved recovery name refused");
        check(SaveManager.refuse("memcards//mc0/data") != null, "ambiguous path refused");
        File dirPayload = new File(game, "../dir-payload.zip");
        try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(dirPayload))) {
            o.putNextEntry(new ZipEntry("memcards/")); o.write(1); o.closeEntry();
        }
        refused = false;
        try { SaveManager.importZip(dirPayload, game); } catch (IOException e) { refused = true; }
        check(refused && SaveManager.list(game).size() == 2, "directory payload refused");
        check(SaveManager.humanSize(5964).equals("6 KB"), "sizes shown readable");

        System.out.println(failures == 0 ? "TestSaves: ok" : "TestSaves: FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
