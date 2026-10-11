package dev.scrapsky.world;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.games.GameDirs;
import dev.scrapsky.games.NoMansSkyLibrary;
import dev.scrapsky.games.PlanetNames;
import dev.scrapsky.gen.SheetData;
import dev.scrapsky.net.Net;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The world's planets (sheet "planets"): names generated from No Man's Sky's own proper nouns
 * and descriptors taken from its English text, both read from the host's install and seeded by
 * the world seed so they stay the same every session. Joining players receive them from the host.
 */
public final class PlanetCatalog {
    private static PlanetCatalog current;
    private static NoMansSkyLibrary nms;
    private static boolean nmsTried;

    private final List<Net.PlanetInfo> infos;

    private PlanetCatalog(List<Net.PlanetInfo> infos) { this.infos = infos; }

    public List<Net.PlanetInfo> infos() { return infos; }

    public Net.PlanetInfo info(String id) {
        for (Net.PlanetInfo i : infos) if (i.id().equals(id)) return i;
        return null;
    }

    public static synchronized PlanetCatalog get(MinecraftServer server) {
        if (current != null) return current;
        long seed = server.overworld().getSeed();
        NoMansSkyLibrary lib = nms();
        PlanetNames names = lib == null ? null : new PlanetNames(lib.vocabulary());
        List<Net.PlanetInfo> out = new ArrayList<>();
        for (SheetData.Planet p : SheetData.PLANETS) {
            String name;
            if (p.home()) name = "Home";
            else {
                name = names != null && names.usable() ? names.name(seed * 31 + p.nameSalt()) : null;
                if (name == null) name = "Planet " + (char) ('A' + p.nameSalt() / 101 - 1);
            }
            String descriptor = lib == null ? SheetData.nms(p.descriptorRef()).fallback() : lib.descriptor(p.descriptorRef(), seed + p.nameSalt());
            String weather = lib == null || p.home() ? "" : lib.descriptor("d_weather", seed * 7 + p.nameSalt());
            out.add(new Net.PlanetInfo(p.id(), name, descriptor, weather));
            ScrapSky.LOG.info("Planet {}: {} - {} {}", p.id(), name, descriptor, weather);
        }
        current = new PlanetCatalog(List.copyOf(out));
        return current;
    }

    public static synchronized void forget() { current = null; }

    /** No Man's Sky's library for the host's own install, read once per game start. */
    public static synchronized NoMansSkyLibrary nms() {
        if (nmsTried) return nms;
        nmsTried = true;
        Path dir = GameDirs.noMansSky(ScrapSky.LOG::info);
        if (dir == null) return null;
        try {
            nms = new NoMansSkyLibrary(dir, ScrapSky.LOG::info);
        } catch (Exception e) {
            ScrapSky.LOG.warn("Couldn't read No Man's Sky at {}: {}", dir, e.toString());
        }
        return nms;
    }

    public static ResourceKey<Level> levelKey(SheetData.Planet p) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(p.dimension()));
    }

    public static String idForLevel(Level level) {
        for (SheetData.Planet p : SheetData.PLANETS) if (levelKey(p).equals(level.dimension())) return p.id();
        return "";
    }
}
