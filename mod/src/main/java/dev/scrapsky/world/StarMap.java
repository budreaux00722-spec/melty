package dev.scrapsky.world;

import dev.scrapsky.gen.SheetData;
import dev.scrapsky.net.Net;
import dev.scrapsky.ship.ShipEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Sheet systems:orbit_star_map and planet_arrival. */
public final class StarMap {
    public static final double ARRIVAL_Y = 230;

    private StarMap() {}

    public static boolean travel(ServerPlayer pilot, ShipEntity ship, String planetId) {
        SheetData.Planet target = null;
        for (SheetData.Planet p : SheetData.PLANETS) if (p.id().equals(planetId)) target = p;
        if (target == null) return false;
        ServerLevel level = pilot.server.getLevel(PlanetCatalog.levelKey(target));
        if (level == null) return false;
        ship.travelTo(level, ship.getX(), ARRIVAL_Y, ship.getZ());
        Net.PlanetInfo info = PlanetCatalog.get(pilot.server).info(planetId);
        if (info != null) {
            String sub = info.weather().isEmpty() ? info.descriptor() : info.descriptor() + "  -  " + info.weather();
            Net.arrival(pilot, info.name(), sub);
        }
        return true;
    }
}
