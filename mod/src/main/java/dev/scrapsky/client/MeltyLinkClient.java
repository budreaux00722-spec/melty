package dev.scrapsky.client;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.world.MeltyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.HttpUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.net.InetAddress;

/** Client half of sheet "multiplayer": host through the bundled e4mc relay, or join the host Melty names. */
public final class MeltyLinkClient {
    private MeltyLinkClient() {}

    private static boolean joinTried;
    private static String joining;
    private static boolean announcedJoined;
    private static boolean hostAnnounced;

    /** Sheet hooks:h_log_tap - e4mc logs "Domain assigned: <domain>" when the relay is up. */
    public static void installLogTap() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            AbstractAppender tap = new AbstractAppender("ScrapSkyMeltyTap", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    String m = event.getMessage().getFormattedMessage();
                    int i = m.indexOf("Domain assigned: ");
                    if (i >= 0 && !hostAnnounced) {
                        hostAnnounced = true;
                        MeltyLink.log("Melty join address: " + m.substring(i + "Domain assigned: ".length()).trim());
                    }
                }
            };
            tap.start();
            ctx.getConfiguration().getRootLogger().addAppender(tap, Level.INFO, null);
            ctx.updateLoggers();
        } catch (RuntimeException | LinkageError e) {
            ScrapSky.LOG.warn("Couldn't watch the log for the relay address: {}", e.toString());
        }
    }

    /** Sheet systems:mp_join - on the first title screen, go straight to the host. */
    public static void onTitleScreen(Minecraft mc, TitleScreen screen) {
        if (joinTried) return;
        joinTried = true;
        String addr = MeltyLink.joinAddress();
        if (addr.isEmpty()) return;
        pendingJoin = addr;
    }

    private static String pendingJoin;

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        if (pendingJoin != null && mc.screen instanceof TitleScreen ts) {
            joining = pendingJoin;
            pendingJoin = null;
            ScrapSky.LOG.info("Joining {} (from melty.properties)", joining);
            ConnectScreen.startConnecting(ts, mc, ServerAddress.parseString(joining), new ServerData("Scrap Sky", joining, ServerData.Type.OTHER), false, null);
            return;
        }
        if (mc.player == null) return;
        if (joining != null && !announcedJoined && !mc.hasSingleplayerServer() && mc.getConnection() != null) {
            announcedJoined = true;
            MeltyLink.log("Melty joined: " + joining);
        }
        IntegratedServer server = mc.getSingleplayerServer();
        // sheet systems:mp_host - open the world to friends as soon as the host is in it
        if (server != null && !server.isPublished() && joining == null && mc.player.tickCount > 40 && !hostAnnounced && !autoHostDone) {
            autoHostDone = true;
            int port = HttpUtil.getAvailablePort();
            boolean ok = server.publishServer(server.getDefaultGameType(), false, port);
            ScrapSky.LOG.info("Opened the world to others on port {}: {}", port, ok);
            if (ok && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("e4mc")) {
                try {
                    hostAnnounced = true;
                    MeltyLink.log("Melty join address: " + InetAddress.getLocalHost().getHostAddress() + ":" + port);
                } catch (Exception ignored) {}
            }
        }
        if (server == null) autoHostDone = false;
    }

    private static boolean autoHostDone;
}
