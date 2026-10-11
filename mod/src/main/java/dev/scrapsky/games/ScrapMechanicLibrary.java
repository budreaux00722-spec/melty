package dev.scrapsky.games;

import dev.scrapsky.gen.SheetData;
import dev.scrapsky.io.Images;
import dev.scrapsky.io.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads Scrap Mechanic's own part catalogue from the player's install: the JSON shapesets
 * (blockList / partList), the English item names and the parts' diffuse textures. Each row of
 * sheet "parts" is resolved by uuid, then texture file name, then shapeset name.
 */
public final class ScrapMechanicLibrary {
    /** One resolved part: its name in Scrap Mechanic and a 32x32 block face made from its own texture. */
    public record Resolved(SheetData.Part part, String uuid, String title, String texturePath, Images.Img face, String how) {}

    private final Path root;
    private final Consumer<String> log;
    private final List<Map<String, Object>> blocks = new ArrayList<>();
    private final List<Map<String, Object>> parts = new ArrayList<>();
    private final Map<String, String> titles = new HashMap<>();

    public ScrapMechanicLibrary(Path root, Consumer<String> log) throws IOException {
        this.root = root;
        this.log = log;
        for (String top : new String[]{"Data", "Survival", "ChallengeData"}) {
            Path sets = root.resolve(top).resolve("Objects").resolve("Database").resolve("ShapeSets");
            for (Path f : list(sets, ".json", ".shapeset")) {
                try {
                    Map<String, Object> o = MiniJson.obj(MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8)));
                    for (Object b : MiniJson.arr(o.get("blockList"))) blocks.add(MiniJson.obj(b));
                    for (Object p : MiniJson.arr(o.get("partList"))) parts.add(MiniJson.obj(p));
                } catch (RuntimeException e) {
                    log.accept("skipped shapeset " + root.relativize(f) + ": " + e.getMessage());
                }
            }
            Path lang = root.resolve(top).resolve("Gui").resolve("Language").resolve("English");
            for (Path f : list(lang, ".json")) {
                try {
                    for (Map.Entry<String, Object> e : MiniJson.obj(MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8))).entrySet()) {
                        String t = MiniJson.str(MiniJson.obj(e.getValue()).get("title"));
                        if (t != null) titles.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), t);
                    }
                } catch (RuntimeException e) {
                    log.accept("skipped language file " + root.relativize(f) + ": " + e.getMessage());
                }
            }
        }
        log.accept("Scrap Mechanic: " + blocks.size() + " blocks, " + parts.size() + " parts, " + titles.size() + " names");
    }

    public int blockCount() { return blocks.size(); }
    public int partCount() { return parts.size(); }

    private static List<Path> list(Path dir, String... exts) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir, 4)) {
            return s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String e : exts) if (n.endsWith(e)) return true;
                return false;
            }).sorted().toList();
        }
    }

    public Map<String, Resolved> resolveAll() {
        Map<String, Resolved> out = new LinkedHashMap<>();
        for (SheetData.Part p : SheetData.PARTS) {
            Resolved r = resolve(p);
            if (r != null) out.put(p.id(), r);
        }
        return out;
    }

    public Resolved resolve(SheetData.Part p) {
        boolean block = p.kind() == SheetData.Kind.HULL || p.kind() == SheetData.Kind.GLASS;
        List<Map<String, Object>> pool = block ? blocks : parts;
        Map<String, Object> hit = null;
        String how = null;
        for (String u : p.smUuids()) {
            for (Map<String, Object> e : pool) if (u.equalsIgnoreCase(MiniJson.str(e.get("uuid")))) { hit = e; how = "uuid"; break; }
            if (hit != null) break;
        }
        Pattern tex = Pattern.compile("(?i).*" + p.smTextureMatch() + ".*");
        if (hit == null) for (Map<String, Object> e : pool) {
            String t = texture(e, block);
            if (t != null && tex.matcher(t).matches()) { hit = e; how = "texture"; break; }
        }
        Pattern name = Pattern.compile("(?i).*" + p.smNameMatch() + ".*");
        if (hit == null) for (Map<String, Object> e : pool) {
            String n = MiniJson.str(e.get("name"));
            if (n != null && name.matcher(n).matches()) { hit = e; how = "name"; break; }
        }
        if (hit == null) { log.accept("Scrap Mechanic part for " + p.id() + " not found; using placeholder"); return null; }
        String uuid = MiniJson.str(hit.get("uuid"));
        String title = uuid == null ? null : titles.get(uuid.toLowerCase(Locale.ROOT));
        if (title == null) title = prettify(MiniJson.str(hit.get("name")));
        String texPath = texture(hit, block);
        Images.Img face = null;
        if (texPath != null) {
            try {
                Path f = gamePath(texPath);
                Images.Img img = Images.decode(f.getFileName().toString(), Files.readAllBytes(f));
                int color = parseColor(MiniJson.str(hit.get("color")), p.fallbackColor());
                if (block) {
                    // one Scrap Mechanic block shows 1/tiling of its tiling texture
                    double tiling = hit.get("tiling") instanceof Double d ? Math.max(1, d) : 4;
                    int cw = (int) Math.max(8, img.w() / tiling), ch = (int) Math.max(8, img.h() / tiling);
                    face = Images.paint(Images.cropScale(img, 0, 0, cw, ch, 32), color, p.kind() == SheetData.Kind.GLASS);
                } else {
                    face = Images.paint(Images.cropScale(img, img.w() / 4, img.h() / 4, img.w() / 2, img.h() / 2, 32), color, false);
                }
            } catch (IOException | RuntimeException e) {
                log.accept("texture " + texPath + " for " + p.id() + " unreadable (" + e.getMessage() + "); using its colour");
            }
        }
        log.accept("Scrap Mechanic " + p.id() + " <- \"" + title + "\" (" + how + (texPath != null ? ", " + texPath : "") + ")");
        return new Resolved(p, uuid, title, texPath, face, how);
    }

    private String texture(Map<String, Object> e, boolean block) {
        if (block) return MiniJson.str(e.get("dif"));
        Object rend = e.get("renderable");
        Map<String, Object> r;
        if (rend instanceof String s) {
            try {
                r = MiniJson.obj(MiniJson.parse(Files.readString(gamePath(s), StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException ex) {
                return null;
            }
        } else r = MiniJson.obj(rend);
        for (Object lod : MiniJson.arr(r.get("lodList"))) {
            Map<String, Object> l = MiniJson.obj(lod);
            List<Object> subs = new ArrayList<>(MiniJson.arr(l.get("subMeshList")));
            subs.addAll(MiniJson.obj(l.get("subMeshMap")).values());
            for (Object sm : subs) {
                for (Object t : MiniJson.arr(MiniJson.obj(sm).get("textureList"))) {
                    String ts = MiniJson.str(t);
                    if (ts != null && !ts.isBlank()) return ts;
                }
            }
        }
        return null;
    }

    Path gamePath(String p) {
        String s = p.replace('\\', '/');
        String[][] map = {{"$GAME_DATA", "Data"}, {"$SURVIVAL_DATA", "Survival"}, {"$CHALLENGE_DATA", "ChallengeData"}};
        for (String[] m : map) if (s.regionMatches(true, 0, m[0], 0, m[0].length())) { s = m[1] + s.substring(m[0].length()); break; }
        Path r = root.resolve(s.startsWith("/") ? s.substring(1) : s);
        if (Files.exists(r)) return r;
        // Windows installs are case-insensitive; match each segment ignoring case on other systems
        Path cur = root;
        for (String seg : (s.startsWith("/") ? s.substring(1) : s).split("/")) {
            Path direct = cur.resolve(seg);
            if (Files.exists(direct)) { cur = direct; continue; }
            Path found = null;
            try (Stream<Path> st = Files.list(cur)) {
                found = st.filter(x -> x.getFileName().toString().equalsIgnoreCase(seg)).findFirst().orElse(null);
            } catch (IOException ignored) {}
            if (found == null) return r;
            cur = found;
        }
        return cur;
    }

    private static int parseColor(String hex, int fallback) {
        try { return hex == null ? fallback : Integer.parseInt(hex.length() > 6 ? hex.substring(0, 6) : hex, 16); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String prettify(String n) {
        if (n == null) return null;
        String s = n.replaceFirst("^(blk|obj|jnt)_(interactive_|interactiveupgradeable_)?", "").replace("_creative", "").replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().split("\\s+")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }
}
