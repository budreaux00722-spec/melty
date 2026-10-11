package dev.scrapsky.world;

import com.mojang.brigadier.CommandDispatcher;
import dev.scrapsky.ScrapSky;
import dev.scrapsky.block.PartBlock;
import dev.scrapsky.gen.SheetData;
import dev.scrapsky.ship.ShipAssembler;
import dev.scrapsky.ship.ShipEntity;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays the core loop by itself and reports each step (sheet "tests"): build the wreck, repair
 * a thruster, assemble the ship, reach every planet, land. Run with /scrapsky selftest, or start
 * the game with -Dscrapsky.selftest=true. Results go to the log and scrapsky/selftest.log.
 */
public final class SelfTest {
    private SelfTest() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("scrapsky").requires(s -> s.hasPermission(2))
                .then(Commands.literal("selftest").executes(c -> {
                    List<String> r = run(c.getSource().getServer());
                    r.forEach(line -> c.getSource().sendSystemMessage(Component.literal(line)));
                    return r.stream().noneMatch(l -> l.startsWith("FAIL")) ? 1 : 0;
                })));
    }

    public static void runIfRequested(MinecraftServer server) {
        if (!Boolean.getBoolean("scrapsky.selftest")) return;
        server.execute(() -> {
            run(server);
            if (Boolean.getBoolean("scrapsky.selftest.exit")) server.halt(false);
        });
    }

    public static List<String> run(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        ServerLevel level = server.overworld();
        BlockPos at = level.getSharedSpawnPos().offset(48, 0, 48);
        level.getChunk(at); // load
        try {
            BlockPos anchor = StarterWreck.build(level, at);
            int ok = 0;
            for (SheetData.WreckBlock w : SheetData.WRECK) {
                BlockState s = level.getBlockState(anchor.offset(w.dx(), w.dy(), w.dz()));
                if (s.getBlock() == ScrapSky.BLOCKS.get(w.part())) ok++;
            }
            report(out, ok == SheetData.WRECK.size(), "t_wreck", "wreck has " + ok + "/" + SheetData.WRECK.size() + " blocks");

            ServerPlayer pilot = testPilot(server, level);
            pilot.getInventory().clearContent();
            pilot.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
            pilot.getInventory().add(new ItemStack(Items.REDSTONE, 2));
            BlockPos seat = StarterWreck.seat(anchor);
            ShipEntity early = ShipAssembler.board(level, seat, pilot);
            report(out, early == null, "t_repair", "ship refuses to fly with only broken thrusters");
            BlockPos broken = null;
            for (SheetData.WreckBlock w : SheetData.WRECK) if (w.part().equals("thruster_broken")) { broken = anchor.offset(w.dx(), w.dy(), w.dz()); break; }
            boolean repaired = PartBlock.repair(level.getBlockState(broken), level, broken, pilot);
            report(out, repaired && level.getBlockState(broken).getBlock() == ScrapSky.BLOCKS.get("thruster") && pilot.getInventory().countItem(Items.IRON_INGOT) == 0,
                    "t_repair", "broken thruster repaired with 3 iron and 2 redstone");

            pilot.moveTo(seat.getX() + 0.5, seat.getY() + 1, seat.getZ() + 0.5);
            ShipEntity ship = ShipAssembler.board(level, seat, pilot);
            int expected = (int) SheetData.WRECK.stream().filter(w -> !w.id().startsWith("debris")).count();
            report(out, ship != null && ship.blocks().size() == expected && level.getBlockState(seat).isAir() && pilot.getVehicle() == ship,
                    "t_assembly", "assembled " + (ship == null ? 0 : ship.blocks().size()) + "/" + expected + " blocks, pilot seated, world cleared");
            if (ship == null) return finish(out);
            report(out, ship.speed() > 0.35 && ship.speed() <= 0.9, "t_flight", String.format("speed %.2f blocks/tick (mass %d, lift %d)", ship.speed(), ship.mass(), ship.lift()));

            for (SheetData.Planet p : SheetData.PLANETS) {
                if (p.home()) continue;
                ServerLevel pl = server.getLevel(PlanetCatalog.levelKey(p));
                if (pl == null) { report(out, false, "t_star_map", "planet " + p.id() + " missing"); continue; }
                pl.getChunk(0, 0);
                int y = pl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8);
                {
                    var info = PlanetCatalog.get(server).info(p.id());
                    report(out, y > pl.getMinBuildHeight() + 10, "t_star_map", "planet " + p.id() + " \"" + (info == null ? "?" : info.name()) + "\" generated, ground at y=" + y
                            + ", biome " + pl.getBiome(new BlockPos(8, y, 8)).unwrapKey().map(k -> k.location().toString()).orElse("?"));
                }
            }
            SheetData.Planet lush = SheetData.PLANETS.stream().filter(p -> p.id().equals("lush")).findFirst().orElseThrow();
            try {
                ship.moveTo(ship.getX(), ShipEntity.ORBIT_Y + 2, ship.getZ());
                boolean went = StarMap.travel(pilot, ship, "lush");
                ShipEntity arrived = pilot.getVehicle() instanceof ShipEntity s2 ? s2 : null;
                report(out, went && arrived != null && arrived.level().dimension().equals(PlanetCatalog.levelKey(lush)),
                        "t_star_map", "ship and pilot travelled to the lush planet");
                if (arrived != null) ship = arrived;
            } catch (RuntimeException e) {
                report(out, false, "t_star_map", "travel failed: " + e);
            }

            ServerLevel here = (ServerLevel) ship.level();
            BlockPos clear = BlockPos.containing(ship.getX(), 200, ship.getZ());
            here.getChunk(clear);
            ship.moveTo(clear.getX() + 0.5, clear.getY() + ship.minY(), clear.getZ() + 0.5);
            pilot.stopRiding(); // lands
            report(out, ship.isRemoved() && here.getBlockState(clear).getBlock() == ScrapSky.BLOCKS.get("driver_seat"), "t_landing", "ship landed and became blocks again");
        } catch (RuntimeException e) {
            report(out, false, "selftest", "crashed: " + e);
            ScrapSky.LOG.error("selftest crashed", e);
        }
        return finish(out);
    }

    /** A real ServerPlayer with an unconnected network handler (Fabric's FakePlayer refuses to ride). */
    private static ServerPlayer testPilot(MinecraftServer server, ServerLevel level) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("scrapsky-test-pilot".getBytes()), "TestPilot");
        ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), p, CommonListenerCookie.createInitial(profile, false));
        level.addNewPlayer(p);
        return p;
    }

    private static List<String> finish(List<String> out) {
        boolean pass = out.stream().noneMatch(l -> l.startsWith("FAIL"));
        out.add(pass ? "SELFTEST PASSED" : "SELFTEST FAILED");
        Path f = FabricLoader.getInstance().getGameDir().resolve("scrapsky").resolve("selftest.log");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, String.join(System.lineSeparator(), out) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}
        return out;
    }

    private static void report(List<String> out, boolean ok, String test, String what) {
        String line = (ok ? "PASS " : "FAIL ") + test + ": " + what;
        out.add(line);
        ScrapSky.LOG.info("[selftest] {}", line);
    }
}
