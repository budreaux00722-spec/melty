package dev.scrapsky.world;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.gen.SheetData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Melty's native multiplayer link (sheet "multiplayer"): join=<address> in
 * config/scrapsky/melty.properties means "join that host", empty means "host";
 * scrapsky/melty.log gets "Melty join address: <address>" once the world is open to others
 * and "Melty joined: <address>" once a joiner is in.
 */
public final class MeltyLink {
    private MeltyLink() {}

    public static Path propertiesFile() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve("scrapsky").resolve("melty.properties");
    }

    public static Path logFile() {
        return FabricLoader.getInstance().getGameDir().resolve("scrapsky").resolve("melty.log");
    }

    /** The address to join, or "" to host. Creates the file (empty join) if missing. */
    public static String joinAddress() {
        Path f = propertiesFile();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, "# Melty fills join= with a host's address to join their game; empty = host\njoin=\n", StandardCharsets.UTF_8);
                return "";
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
            return p.getProperty("join", "").trim();
        } catch (IOException e) {
            ScrapSky.LOG.warn("melty.properties unreadable: {}", e.toString());
            return "";
        }
    }

    public static synchronized void log(String line) {
        Path f = logFile();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ScrapSky.LOG.warn("melty.log unwritable: {}", e.toString());
        }
        ScrapSky.LOG.info(line);
    }

    /** Sheet systems:mp_cap. */
    public static boolean refuseIfFull(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        if (server.getPlayerCount() > SheetData.MAX_PLAYERS) {
            handler.disconnect(Component.translatable("scrapsky.msg.full", SheetData.MAX_PLAYERS));
            return true;
        }
        return false;
    }
}
