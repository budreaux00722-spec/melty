package dev.scrapsky.net;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.ship.ShipEntity;
import dev.scrapsky.world.PlanetCatalog;
import dev.scrapsky.world.StarMap;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Sheet hooks:h_payloads - the four messages between the game and its players. */
public final class Net {
    private Net() {}

    public record PlanetInfo(String id, String name, String descriptor, String weather) {
        public static final StreamCodec<ByteBuf, PlanetInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlanetInfo::id,
                ByteBufCodecs.STRING_UTF8, PlanetInfo::name,
                ByteBufCodecs.STRING_UTF8, PlanetInfo::descriptor,
                ByteBufCodecs.STRING_UTF8, PlanetInfo::weather,
                PlanetInfo::new);
    }

    /** server -> client: the world's planets (names and No Man's Sky descriptors). */
    public record Catalog(List<PlanetInfo> planets) implements CustomPacketPayload {
        public static final Type<Catalog> TYPE = new Type<>(ScrapSky.id("catalog"));
        public static final StreamCodec<FriendlyByteBuf, Catalog> CODEC = StreamCodec.composite(
                PlanetInfo.CODEC.apply(ByteBufCodecs.list()), Catalog::planets, Catalog::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** server -> client: the pilot reached orbit; show the Star Map. */
    public record OpenStarMap(String currentId) implements CustomPacketPayload {
        public static final Type<OpenStarMap> TYPE = new Type<>(ScrapSky.id("open_star_map"));
        public static final StreamCodec<FriendlyByteBuf, OpenStarMap> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenStarMap::currentId, OpenStarMap::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** client -> server: fly to this planet ("" = stay). */
    public record Choose(String planetId) implements CustomPacketPayload {
        public static final Type<Choose> TYPE = new Type<>(ScrapSky.id("choose"));
        public static final StreamCodec<FriendlyByteBuf, Choose> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Choose::planetId, Choose::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** server -> client: show the arrival title. */
    public record Arrival(String title, String subtitle) implements CustomPacketPayload {
        public static final Type<Arrival> TYPE = new Type<>(ScrapSky.id("arrival"));
        public static final StreamCodec<FriendlyByteBuf, Arrival> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Arrival::title, ByteBufCodecs.STRING_UTF8, Arrival::subtitle, Arrival::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(Catalog.TYPE, Catalog.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenStarMap.TYPE, OpenStarMap.CODEC);
        PayloadTypeRegistry.playS2C().register(Arrival.TYPE, Arrival.CODEC);
        PayloadTypeRegistry.playC2S().register(Choose.TYPE, Choose.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Choose.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player.getVehicle() instanceof ShipEntity ship && !payload.planetId().isEmpty()) {
                StarMap.travel(player, ship, payload.planetId());
            }
        }));
    }

    public static void sendCatalog(ServerPlayer p, PlanetCatalog c) {
        if (ServerPlayNetworking.canSend(p, Catalog.TYPE)) ServerPlayNetworking.send(p, new Catalog(c.infos()));
    }

    public static void openStarMap(ServerPlayer p, PlanetCatalog c, String currentId) {
        sendCatalog(p, c);
        if (ServerPlayNetworking.canSend(p, OpenStarMap.TYPE)) ServerPlayNetworking.send(p, new OpenStarMap(currentId));
    }

    public static void arrival(ServerPlayer p, String title, String subtitle) {
        if (ServerPlayNetworking.canSend(p, Arrival.TYPE)) ServerPlayNetworking.send(p, new Arrival(title, subtitle));
    }
}
