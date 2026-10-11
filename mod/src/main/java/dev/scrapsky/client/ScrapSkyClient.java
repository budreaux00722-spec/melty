package dev.scrapsky.client;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.net.Net;
import dev.scrapsky.ship.ShipEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ScrapSkyClient implements ClientModInitializer {
    private static List<Net.PlanetInfo> catalog = List.of();

    @Override
    public void onInitializeClient() {
        MeltyLinkClient.installLogTap();
        RuntimePack.generate();
        BlockRenderLayerMap.INSTANCE.putBlock(ScrapSky.BLOCKS.get("hull_glass"), RenderType.translucent());
        EntityRendererRegistry.register(ScrapSky.SHIP, ShipRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(Net.Catalog.TYPE, (p, ctx) -> catalog = p.planets());
        ClientPlayNetworking.registerGlobalReceiver(Net.OpenStarMap.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            ShipEntity.awaitingStarMapClient = true;
            ctx.client().setScreen(new StarMapScreen(catalog, p.currentId()));
        }));
        ClientPlayNetworking.registerGlobalReceiver(Net.Arrival.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            var gui = ctx.client().gui;
            gui.setTimes(10, 80, 20);
            gui.setSubtitle(Component.literal(p.subtitle()));
            gui.setTitle(Component.literal(p.title()));
        }));

        // sheet hooks:h_client_tick - the pilot's own movement keys steer the ship (sheet "controls")
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            MeltyTest.tick(mc);
            if (mc.player != null && mc.player.getVehicle() instanceof ShipEntity ship) {
                var o = mc.options;
                if (MeltyTest.active()) ship.setInput(MeltyTest.forward, false, false, false, MeltyTest.up, MeltyTest.down);
                else ship.setInput(o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(), o.keyJump.isDown(), o.keySprint.isDown());
            }
            if (!MeltyTest.active()) MeltyLinkClient.tick(mc);
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof TitleScreen ts) {
                MeltyTest.onTitleScreen(mc, ts);
                if (!MeltyTest.active()) MeltyLinkClient.onTitleScreen(mc, ts);
            }
        });
    }

    public static Minecraft mc() { return Minecraft.getInstance(); }
}
