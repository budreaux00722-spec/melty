package dev.scrapsky.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the player's own installs of the companion games. Melty passes each folder in an
 * environment variable at launch (sheet "games", column envVar); when started outside Melty
 * the Steam libraries are searched.
 */
public final class GameDirs {
    private GameDirs() {}

    public static Path scrapMechanic(Consumer<String> log) {
        return find("SCRAPSKY_SM_DIR", "387990", "Scrap Mechanic", p -> Files.isDirectory(p.resolve("Data")), log);
    }

    public static Path noMansSky(Consumer<String> log) {
        return find("SCRAPSKY_NMS_DIR", "275850", "No Man's Sky", p -> Files.isDirectory(p.resolve("GAMEDATA")), log);
    }

    private static Path find(String env, String appId, String folder, java.util.function.Predicate<Path> valid, Consumer<String> log) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = System.getProperty(env.toLowerCase(java.util.Locale.ROOT).replace('_', '.'));
        if (v != null && !v.isBlank()) {
            Path p = Path.of(v.trim().replaceAll("^\"|\"$", ""));
            if (valid.test(p)) { log.accept(folder + " from " + env + ": " + p); return p; }
            log.accept(env + " points at " + p + ", which doesn't look like " + folder + "; searching Steam");
        }
        for (Path lib : steamLibraries()) {
            Path c = lib.resolve("steamapps").resolve("common").resolve(folder);
            try {
                Path acf = lib.resolve("steamapps").resolve("appmanifest_" + appId + ".acf");
                if (Files.isRegularFile(acf)) {
                    Matcher m = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"").matcher(Files.readString(acf));
                    if (m.find()) c = lib.resolve("steamapps").resolve("common").resolve(m.group(1));
                }
            } catch (IOException ignored) {}
            if (valid.test(c)) { log.accept(folder + " found in Steam library: " + c); return c; }
        }
        log.accept(folder + " not found (set " + env + ")");
        return null;
    }

    static List<Path> steamLibraries() {
        Set<Path> roots = new LinkedHashSet<>();
        for (String s : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles")}) {
            if (s != null) roots.add(Path.of(s, "Steam"));
        }
        roots.add(Path.of("C:\\Program Files (x86)\\Steam"));
        String home = System.getProperty("user.home");
        roots.add(Path.of(home, ".steam", "steam"));
        roots.add(Path.of(home, ".local", "share", "Steam"));
        roots.add(Path.of(home, "Library", "Application Support", "Steam"));
        List<Path> libs = new ArrayList<>();
        for (Path r : roots) {
            Path vdf = r.resolve("steamapps").resolve("libraryfolders.vdf");
            if (!Files.isRegularFile(vdf)) continue;
            libs.add(r);
            try {
                Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf));
                while (m.find()) libs.add(Path.of(m.group(1).replace("\\\\", "\\")));
            } catch (IOException ignored) {}
        }
        return libs;
    }
}
