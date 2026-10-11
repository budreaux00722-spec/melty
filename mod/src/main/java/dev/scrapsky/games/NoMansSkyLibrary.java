package dev.scrapsky.games;

import dev.scrapsky.gen.SheetData;
import dev.scrapsky.io.HgPak;
import dev.scrapsky.io.Mbin;
import dev.scrapsky.io.WemToOgg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads No Man's Sky's own content from the player's install (GAMEDATA/PCBANKS/*.pak):
 * the English localisation table, used for planet descriptors and the planet-name
 * vocabulary, and the soundtrack's Wwise WEM files, converted to Ogg for Minecraft.
 * What is picked is set by sheet "nms_content".
 */
public final class NoMansSkyLibrary {
    private final Path root;
    private final Consumer<String> log;
    private final List<Path> paks;
    private final Map<String, String> loc = new LinkedHashMap<>();

    public NoMansSkyLibrary(Path root, Consumer<String> log) throws IOException {
        this.root = root;
        this.log = log;
        Path banks = root.resolve("GAMEDATA").resolve("PCBANKS");
        if (!Files.isDirectory(banks)) throw new IOException("no GAMEDATA/PCBANKS in " + root);
        try (Stream<Path> s = Files.list(banks)) {
            paks = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pak")).sorted().toList();
        }
        loadLocalisation();
        log.accept("No Man's Sky: " + paks.size() + " archives, " + loc.size() + " English text entries");
    }

    public Map<String, String> localisation() { return loc; }

    private List<Path> paksFor(String pattern) {
        Pattern p = Pattern.compile(pattern);
        List<Path> hit = paks.stream().filter(x -> p.matcher(x.getFileName().toString()).matches()).toList();
        return hit.isEmpty() ? paks : hit;
    }

    private void loadLocalisation() {
        SheetData.NmsRow row = SheetData.nms("loc_english");
        Pattern file = Pattern.compile(row.fileMatch());
        for (Path pak : paksFor(row.pak())) {
            try (HgPak hp = new HgPak(pak)) {
                for (HgPak.Entry e : hp.find(file)) {
                    try {
                        Map<String, String> m = Mbin.readLocalisation(hp.read(e));
                        m.forEach(loc::putIfAbsent);
                    } catch (RuntimeException ex) {
                        log.accept("skipped " + e.path() + ": " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                log.accept("skipped archive " + pak.getFileName() + ": " + ex.getMessage());
            }
        }
    }

    /** A descriptor for a sheet row (d_lush, d_weather, ...), picked with the given seed; the row's fallback when nothing matches. */
    public String descriptor(String rowId, long seed) {
        SheetData.NmsRow row = SheetData.nms(rowId);
        Pattern k = Pattern.compile(row.keyMatch()), v = Pattern.compile(row.valueMatch());
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : loc.entrySet()) {
            String val = e.getValue().trim();
            if (k.matcher(e.getKey()).find() && v.matcher(val).matches() && !hits.contains(val)) hits.add(val);
        }
        if (hits.isEmpty()) {
            log.accept("No Man's Sky text for " + rowId + " not found; using \"" + row.fallback() + "\"");
            return row.fallback().equals("-") ? "" : row.fallback();
        }
        return hits.get(Math.floorMod(new Random(seed ^ rowId.hashCode()).nextInt(), hits.size()));
    }

    /** Proper nouns from the English table: capitalised words of 4-10 letters never seen in lower case. */
    public List<String> vocabulary() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        Pattern word = Pattern.compile("[A-Za-z]{4,10}");
        for (String text : loc.values()) {
            if (text.contains("<") && text.contains(">")) text = text.replaceAll("<[^>]*>", " ");
            var m = word.matcher(text);
            while (m.find()) {
                String w = m.group();
                boolean cap = Character.isUpperCase(w.charAt(0)) && w.substring(1).chars().allMatch(Character::isLowerCase);
                String key = w.toLowerCase(Locale.ROOT);
                if (cap) seen.putIfAbsent(key, Boolean.TRUE);
                else if (w.equals(key)) seen.put(key, Boolean.FALSE);
            }
        }
        List<String> out = new ArrayList<>();
        seen.forEach((w, proper) -> { if (proper) out.add(w); });
        return out;
    }

    /**
     * Converts the soundtrack: the largest WEMs that are Wwise Vorbis and at least 60 s long,
     * written as music_N.ogg into cacheDir (on the player's PC only). Reuses the cache when the
     * archives are unchanged. Returns the written files.
     */
    public List<Path> music(Path cacheDir, int max, WemToOgg.CodebookLibrary... libraries) throws IOException {
        SheetData.NmsRow row = SheetData.nms("planet_music");
        Files.createDirectories(cacheDir);
        List<Path> audioPaks = paksFor(row.pak());
        StringBuilder key = new StringBuilder("v1");
        for (Path p : audioPaks) key.append('|').append(p.getFileName()).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis());
        Path stamp = cacheDir.resolve("source.txt");
        List<Path> cached = new ArrayList<>();
        for (int i = 0; i < max; i++) { Path f = cacheDir.resolve("music_" + i + ".ogg"); if (Files.isRegularFile(f)) cached.add(f); }
        if (Files.isRegularFile(stamp) && Files.readString(stamp).equals(key.toString()) && !cached.isEmpty()) {
            log.accept("No Man's Sky music: using " + cached.size() + " cached tracks");
            return cached;
        }
        for (Path f : cached) Files.deleteIfExists(f);

        record Cand(Path pak, HgPak.Entry entry) {}
        List<Cand> cands = new ArrayList<>();
        Pattern file = Pattern.compile(row.fileMatch());
        for (Path pak : audioPaks) {
            try (HgPak hp = new HgPak(pak)) {
                for (HgPak.Entry e : hp.find(file)) cands.add(new Cand(pak, e));
            } catch (IOException ex) {
                log.accept("skipped archive " + pak.getFileName() + ": " + ex.getMessage());
            }
        }
        cands.sort(Comparator.comparingLong((Cand c) -> c.entry().size()).reversed());
        List<Path> out = new ArrayList<>();
        int tried = 0;
        for (Cand c : cands) {
            if (out.size() >= max || tried >= max * 6) break;
            tried++;
            try (HgPak hp = new HgPak(c.pak())) {
                byte[] wem = hp.read(c.entry());
                WemToOgg.Result info = WemToOgg.probe(wem);
                if (info.seconds() < 60) continue;
                WemToOgg.Result r = null;
                IOException last = null;
                for (WemToOgg.CodebookLibrary lib : libraries) {
                    try { r = WemToOgg.convert(wem, lib); break; } catch (WemToOgg.ParseException ex) { last = ex; }
                }
                if (r == null) { log.accept("music " + c.entry().path() + " not converted: " + (last == null ? "?" : last.getMessage())); continue; }
                Path f = cacheDir.resolve("music_" + out.size() + ".ogg");
                Files.write(f, r.ogg());
                out.add(f);
                log.accept(String.format(Locale.ROOT, "No Man's Sky music %d <- %s (%.0f s)", out.size() - 1, c.entry().path(), r.seconds()));
            } catch (IOException | RuntimeException ex) {
                log.accept("music " + c.entry().path() + " skipped: " + ex.getMessage());
            }
        }
        Files.writeString(stamp, key.toString(), StandardCharsets.UTF_8);
        if (out.isEmpty()) log.accept("No Man's Sky music: no tracks converted; planets play Minecraft's music");
        return out;
    }
}
