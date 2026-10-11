package dev.scrapsky;

import dev.scrapsky.block.PartBlock;
import dev.scrapsky.gen.SheetData;
import dev.scrapsky.net.Net;
import dev.scrapsky.ship.ShipEntity;
import dev.scrapsky.world.MeltyLink;
import dev.scrapsky.world.PlanetCatalog;
import dev.scrapsky.world.SelfTest;
import dev.scrapsky.world.StarterWreck;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Scrap Sky: build ships from Scrap Mechanic's parts in Minecraft and fly them to No Man's Sky's planets. */
public final class ScrapSky implements ModInitializer {
    public static final String MODID = "scrapsky";
    public static final Logger LOG = LoggerFactory.getLogger("ScrapSky");

    /** One block per row of sheet "parts". */
    public static final Map<String, PartBlock> BLOCKS = new LinkedHashMap<>();
    public static EntityType<ShipEntity> SHIP;
    public static SoundEvent PLANET_MUSIC;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    @Override
    public void onInitialize() {
        dev.scrapsky.games.GameDirs.settingsFile = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("scrapsky").resolve("games.properties");
        for (SheetData.Part part : SheetData.PARTS) {
            BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                    .strength(part.hardness(), part.hardness() * 2)
                    .sound(sound(part.sound()))
                    .mapColor(MapColor.METAL);
            if (part.kind() == SheetData.Kind.GLASS) props = props.noOcclusion().isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false).isRedstoneConductor((s, l, p) -> false);
            if (part.kind() == SheetData.Kind.THRUSTER) props = props.lightLevel(s -> 7);
            PartBlock block = Registry.register(BuiltInRegistries.BLOCK, id(part.id()), new PartBlock(props, part));
            Registry.register(BuiltInRegistries.ITEM, id(part.id()), new BlockItem(block, new Item.Properties()));
            BLOCKS.put(part.id(), block);
        }
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("parts"), FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.scrapsky"))
                .icon(() -> new ItemStack(BLOCKS.get("thruster")))
                .displayItems((params, out) -> BLOCKS.values().forEach(out::accept))
                .build());

        SHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("ship"),
                EntityType.Builder.<ShipEntity>of(ShipEntity::new, MobCategory.MISC)
                        .sized(1.0f, 1.0f).clientTrackingRange(10).updateInterval(1).build("ship"));

        PLANET_MUSIC = Registry.register(BuiltInRegistries.SOUND_EVENT, id("planet_music"), SoundEvent.createVariableRangeEvent(id("planet_music")));

        Net.registerCommon();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (MeltyLink.refuseIfFull(handler, server)) return;
            server.execute(() -> {
                StarterWreck.ensure(server, handler.player);
                Net.sendCatalog(handler.player, PlanetCatalog.get(server));
            });
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PlanetCatalog.forget());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> SelfTest.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(SelfTest::runIfRequested);
        LOG.info("Scrap Sky ready: {} Scrap Mechanic parts, {} planets", BLOCKS.size(), SheetData.PLANETS.size() - 1);
    }

    private static SoundType sound(String s) {
        return switch (s) {
            case "wood" -> SoundType.WOOD;
            case "metal" -> SoundType.METAL;
            case "glass" -> SoundType.GLASS;
            default -> SoundType.STONE;
        };
    }
}
