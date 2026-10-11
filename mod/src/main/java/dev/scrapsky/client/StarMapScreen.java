package dev.scrapsky.client;

import dev.scrapsky.net.Net;
import dev.scrapsky.ship.ShipEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Sheet systems:orbit_star_map - pick where the ship goes next. */
public class StarMapScreen extends Screen {
    private final List<Net.PlanetInfo> planets;
    private final String current;

    public StarMapScreen(List<Net.PlanetInfo> planets, String current) {
        super(Component.translatable("scrapsky.starmap.title"));
        this.planets = planets;
        this.current = current;
    }

    @Override
    protected void init() {
        int y = this.height / 2 - planets.size() * 26 / 2 + 10;
        for (Net.PlanetInfo p : planets) {
            if (p.id().equals(current)) continue;
            String label = p.name() + (p.descriptor().isEmpty() ? "" : "  -  " + p.descriptor());
            addRenderableWidget(Button.builder(Component.literal(label), b -> choose(p.id()))
                    .bounds(this.width / 2 - 150, y, 300, 20).build());
            y += 26;
        }
    }

    private void choose(String id) {
        ClientPlayNetworking.send(new Net.Choose(id));
        ShipEntity.awaitingStarMapClient = false;
        this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        ClientPlayNetworking.send(new Net.Choose(""));
        ShipEntity.awaitingStarMapClient = false;
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - planets.size() * 13 - 20, 0x9FE7FF);
        int y = this.height / 2 - planets.size() * 26 / 2 + 10;
        for (Net.PlanetInfo p : planets) {
            if (p.id().equals(current)) continue;
            if (!p.weather().isEmpty()) g.drawString(this.font, p.weather(), this.width / 2 + 156, y + 6, 0xA0A0A0);
            y += 26;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
