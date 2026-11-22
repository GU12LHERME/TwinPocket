package com.twinsanityevolved.game;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's settings file, ps2r.ini -- the ONE place the settings live: the game's own menus write it (the graphic
 * options, Options > android settings) and the launcher reads and writes the same keys here, so a choice made on either
 * side is the other's next time. The file is the engine's (core/config/settings_file.h): `key = value` lines, `#`/`;`
 * comments, optional `[section]` headers; an edit replaces the key's line in place (or appends it) and keeps every other
 * line byte for byte, written whole through a temporary and renamed into place.
 *
 * Where it is: the rule runner/te_android.cpp follows (and names to the runner with --config) -- files/game/ps2r.ini when
 * that file exists, else files/game/userdata/ps2r.ini.
 *
 * The values written are only the words the game accepts (its reader refuses a whole file for one bad value).
 */
final class Settings {
    // keys and words shared with the game (platform/nativeee/host/display.cpp, te_port_settings.h)
    static final String AUDIO_DRIVER = "android.audio_driver";
    static final String AUDIO_BUFFER = "android.audio_buffer";
    static final String STICK_DEADZONE = "android.stick_deadzone";
    static final String STICK_SENSITIVITY = "android.stick_sensitivity";
    static final String DRAW_DISTANCE = "android.draw_distance";
    static final String TEXTURE_FILTER = "video.texture_filter";
    static final String SCALE = "video.scale";
    static final String VSYNC = "video.vsync";
    static final String FRAME_RATE = "video.frame_rate";
    static final String FPS_COUNTER = "display.fps_counter";
    static final String ASPECT = "video.aspect";

    private final File file;

    Settings(Context c) {
        File game = new File(c.getFilesDir(), "game");
        File portable = new File(game, "ps2r.ini");
        file = portable.isFile() ? portable : new File(new File(game, "userdata"), "ps2r.ini");
    }

    File file() {
        return file;
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        String text = Diag.readFile(file, 0);
        if (text == null || text.isEmpty()) return out;
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1 && parts[i].isEmpty()) break;   // the final newline
            out.add(parts[i]);
        }
        return out;
    }

    // The key a line sets ("" for none), with the [section] in force (`section` is a one-element holder)
    private static String lineKey(String line, String[] section) {
        String l = line;
        int c = indexOfAny(l, "#;");
        if (c >= 0) l = l.substring(0, c);
        l = l.trim();
        if (l.isEmpty()) return "";
        if (l.startsWith("[") && l.endsWith("]")) {
            section[0] = l.substring(1, l.length() - 1).trim();
            return "";
        }
        int eq = l.indexOf('=');
        if (eq < 0) return "";
        String key = l.substring(0, eq).trim();
        if (!section[0].isEmpty() && key.indexOf('.') < 0) key = section[0] + "." + key;
        return key;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int at = s.indexOf(chars.charAt(i));
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** The key's value, or `fallback` when the file or the key is not there. */
    String get(String key, String fallback) {
        String[] section = {""};
        String value = null;
        for (String line : lines()) {
            if (key.equals(lineKey(line, section))) {
                String l = line;
                int c = indexOfAny(l, "#;");
                if (c >= 0) l = l.substring(0, c);
                value = l.substring(l.indexOf('=') + 1).trim();
            }
        }
        return value != null ? value : fallback;
    }

    boolean has(String key) {
        return get(key, null) != null;
    }

    /** Writes `key = value` (its line replaced in place, or appended). False when the file could not be written. */
    boolean set(String key, String value) {
        List<String> lines = lines();
        String[] section = {""};
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            String k = lineKey(lines.get(i), section);
            if (!key.equals(k)) continue;
            // the line keeps the name it was written with (flat "section.key", or "key" under its [section])
            String name = lines.get(i).substring(0, lines.get(i).indexOf('=')).trim();
            String newLine = name + " = " + value;
            if (lines.get(i).equals(newLine)) return true;
            lines.set(i, newLine);
            done = true;
        }
        if (!done) {
            // appended as a flat dotted line; after a [section] header the engine reads a dotted key as itself (IniLineKey)
            lines.add(key + " = " + value);
        }
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            return false;
        }
        return tmp.renameTo(file);
    }

    /**
     * Once: the launcher's earlier audio options (SharedPreferences, versions up to 0.2.0) moved into ps2r.ini, when the
     * file names no audio driver yet -- so a player's choice survives the move to the shared file.
     */
    void migrate(SharedPreferences prefs) {
        if (prefs.getBoolean("settings_migrated", false)) return;
        String driver = prefs.getString("audio_driver", null);
        if (driver != null && !has(AUDIO_DRIVER)
                && (driver.equals("openslES") || driver.equals("aaudio") || driver.equals("android") || driver.equals("auto")))
            set(AUDIO_DRIVER, driver);
        int buffer = prefs.getInt("audio_buffer", 0);
        if (buffer != 0 && !has(AUDIO_BUFFER) && (buffer == 1024 || buffer == 2048 || buffer == 4096))
            set(AUDIO_BUFFER, Integer.toString(buffer));
        prefs.edit().putBoolean("settings_migrated", true).apply();
    }
}
