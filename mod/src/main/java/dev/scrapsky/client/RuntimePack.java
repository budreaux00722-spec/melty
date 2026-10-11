package dev.scrapsky.client;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.games.GameDirs;
import dev.scrapsky.games.ScrapMechanicLibrary;
import dev.scrapsky.gen.SheetData;
import dev.scrapsky.io.Images;
import dev.scrapsky.io.WemToOgg;
import dev.scrapsky.world.PlanetCatalog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sheet systems:runtime_pack. At start the client reads the player's own Scrap Mechanic parts
 * (names, textures, colours) and No Man's Sky soundtrack and writes them as a resource pack in
 * the instance folder, which is always on top. Nothing from either game is shipped with the mod.
 */
public final class RuntimePack {
    private RuntimePack() {}

    /** What was read this start (for the Melty test checks). */
    public static int smParts, nmsTracks;
    /** One line per part: what its texture held (for the Melty test report). */
    public static final List<String> smDiag = new ArrayList<>();
    public static boolean smColourOnly;

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("scrapsky").resolve("runtime-pack");
    }

    /** Added to the client's pack repository by PackRepositoryMixin. */
    public static final RepositorySource SOURCE = onLoad -> {
        Path d = dir();
        if (!Files.isRegularFile(d.resolve("pack.mcmeta"))) return;
        PackLocationInfo info = new PackLocationInfo("scrapsky_runtime", Component.literal("Scrap Sky: your Scrap Mechanic and No Man's Sky"), PackSource.BUILT_IN, Optional.empty());
        Pack pack = Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(d), PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, true));
        if (pack != null) onLoad.accept(pack);
    };

    public static void generate() {
        long t = System.currentTimeMillis();
        Path d = dir();
        try {
            Files.createDirectories(d);
            Files.writeString(d.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"Scrap Sky: parts and music from your own games\"}}", StandardCharsets.UTF_8);
            writeScrapMechanic(d);
            writeNoMansSkyMusic(d);
        } catch (IOException | RuntimeException e) {
            ScrapSky.LOG.error("Scrap Sky couldn't prepare game content", e);
        }
        ScrapSky.LOG.info("Scrap Sky runtime pack ready in {} ms", System.currentTimeMillis() - t);
    }

    private static void writeScrapMechanic(Path d) throws IOException {
        Path tex = d.resolve("assets/scrapsky/textures/block");
        Files.createDirectories(tex);
        StringBuilder lang = new StringBuilder("{\n");
        Path sm = GameDirs.scrapMechanic(ScrapSky.LOG::info);
        Map<String, ScrapMechanicLibrary.Resolved> parts = Map.of();
        if (sm != null) {
            try {
                parts = new ScrapMechanicLibrary(sm, ScrapSky.LOG::info).resolveAll();
            } catch (IOException | RuntimeException e) {
                ScrapSky.LOG.warn("Couldn't read Scrap Mechanic at {}: {}", sm, e.toString());
            }
        }
        List<String> entries = new ArrayList<>();
        for (SheetData.Part p : SheetData.PARTS) {
            ScrapMechanicLibrary.Resolved r = parts.get(p.id());
            Path face = tex.resolve(p.id() + ".png"), front = tex.resolve(p.id() + "_front.png");
            if (r == null) {
                Files.deleteIfExists(face);
                Files.deleteIfExists(front);
                continue;
            }
            if (r.face() != null) {
                Images.Img img = p.kind() == SheetData.Kind.BROKEN_THRUSTER ? Images.darken(r.face(), 0.45f) : r.face();
                Files.write(face, Images.png(img));
                if (p.kind() == SheetData.Kind.THRUSTER || p.kind() == SheetData.Kind.BROKEN_THRUSTER || p.kind() == SheetData.Kind.SEAT) {
                    Files.write(front, Images.png(Images.darken(img, 0.6f)));
                }
            }
            smParts++;
            smDiag.add(p.id() + ": " + r.diag());
            if (r.diag().contains("colour only")) smColourOnly = true;
            String title = p.kind() == SheetData.Kind.BROKEN_THRUSTER ? "Broken " + r.title() : r.title();
            entries.add("  \"block.scrapsky." + p.id() + "\": \"" + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        lang.append(String.join(",\n", entries)).append("\n}\n");
        Path langDir = d.resolve("assets/scrapsky/lang");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("en_us.json"), lang.toString(), StandardCharsets.UTF_8);
    }

    private static void writeNoMansSkyMusic(Path d) throws IOException {
        Path music = d.resolve("assets/scrapsky/sounds/music");
        Path sounds = d.resolve("assets/scrapsky/sounds.json");
        var nms = PlanetCatalog.nms();
        List<Path> tracks = List.of();
        if (nms != null) {
            try (InputStream a = RuntimePack.class.getResourceAsStream("/assets/scrapsky/ww2ogg/packed_codebooks_aoTuV_603.bin");
                 InputStream b = RuntimePack.class.getResourceAsStream("/assets/scrapsky/ww2ogg/packed_codebooks.bin")) {
                WemToOgg.CodebookLibrary aotuv = WemToOgg.CodebookLibrary.load(a), std = WemToOgg.CodebookLibrary.load(b);
                tracks = nms.music(music, 6, aotuv, std);
            } catch (IOException | RuntimeException e) {
                ScrapSky.LOG.warn("No Man's Sky music unavailable: {}", e.toString());
            }
        }
        nmsTracks = tracks.size();
        if (tracks.isEmpty()) { Files.deleteIfExists(sounds); return; }
        StringBuilder s = new StringBuilder("{\n  \"planet_music\": {\n    \"replace\": true,\n    \"sounds\": [\n");
        for (int i = 0; i < tracks.size(); i++) {
            String name = tracks.get(i).getFileName().toString().replace(".ogg", "");
            s.append("      {\"name\": \"scrapsky:music/").append(name).append("\", \"stream\": true}").append(i + 1 < tracks.size() ? "," : "").append('\n');
        }
        s.append("    ]\n  }\n}\n");
        Files.writeString(sounds, s.toString(), StandardCharsets.UTF_8);
    }
}
