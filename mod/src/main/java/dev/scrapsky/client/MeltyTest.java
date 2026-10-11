package dev.scrapsky.client;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.gen.SheetData;
import dev.scrapsky.io.MiniJson;
import dev.scrapsky.ship.ShipAssembler;
import dev.scrapsky.ship.ShipEntity;
import dev.scrapsky.world.PlanetCatalog;
import dev.scrapsky.world.StarterWreck;
import dev.scrapsky.block.PartBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Melty's test runs (recipe "test"): while %LOCALAPPDATA%\Melty\test\<slug>.json exists, the game
 * makes a world with the given seed and plays the core loop on screen: the wreck, a repair,
 * take-off, the climb to orbit, the Star Map, arrival on a planet and landing. Each check is
 * appended to the file Melty names as "pass <check>" or "fail <check>: why".
 */
public final class MeltyTest {
    private MeltyTest() {}

    enum Step { IDLE, WORLD, SETUP, CLIMB, STAR_MAP, DESCEND, DONE }

    private static Step step = Step.IDLE;
    private static Path checks;
    private static long seed;
    private static int timer;
    private static final Set<String> reported = new LinkedHashSet<>();
    public static boolean flying;
    public static boolean up, down, forward;

    public static boolean active() { return step != Step.IDLE && step != Step.DONE; }

    static Path findTestFile() {
        String la = System.getenv("LOCALAPPDATA");
        if (la == null) return null;
        Path dir = Path.of(la, "Melty", "test");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return n.endsWith(".json") && n.contains("scrap");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static void onTitleScreen(Minecraft mc, TitleScreen screen) {
        if (step != Step.IDLE) return;
        Path f = findTestFile();
        if (f == null) return;
        try {
            Map<String, Object> j = MiniJson.obj(MiniJson.parse(Files.readString(f, StandardCharsets.UTF_8)));
            String c = MiniJson.str(j.get("checks"));
            if (c == null) return;
            checks = Path.of(c);
            seed = j.get("seed") instanceof Double d ? d.longValue() : 1L;
            Object run = j.get("run");
            ScrapSky.LOG.info("Melty test run {} (seed {}), checks -> {}", run, seed, checks);
        } catch (IOException | RuntimeException e) {
            ScrapSky.LOG.warn("Melty test file unreadable: {}", e.toString());
            return;
        }
        report("scrap-mechanic-parts", RuntimePack.smParts >= 6, RuntimePack.smParts + " of " + SheetData.PARTS.size() + " parts read from Scrap Mechanic");
        report("no-mans-sky-music", RuntimePack.nmsTracks >= 1, RuntimePack.nmsTracks + " No Man's Sky tracks converted");
        step = Step.WORLD;
        pendingCreate = true;
    }

    private static boolean pendingCreate;

    public static void tick(Minecraft mc) {
        if (!active()) return;
        if (pendingCreate && mc.screen instanceof TitleScreen ts) {
            pendingCreate = false;
            String name = "ScrapSky-Test-" + System.currentTimeMillis();
            LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, false, false), WorldPresets::createNormalWorldDimensions, ts);
            return;
        }
        var server = mc.getSingleplayerServer();
        if (mc.player == null || server == null) return;
        timer++;
        switch (step) {
            case WORLD -> {
                if (mc.player.tickCount > 60) { step = Step.SETUP; timer = 0; server.execute(MeltyTest::setup); }
            }
            case CLIMB -> {
                up = true; forward = timer % 200 < 120; down = false;
                if (mc.screen instanceof StarMapScreen) { step = Step.STAR_MAP; timer = 0; up = forward = false; }
                if (timer > 20 * 120) fail("reach-planet", "never reached orbit");
            }
            case STAR_MAP -> {
                if (timer == 40) {
                    dev.scrapsky.net.Net.Choose c = new dev.scrapsky.net.Net.Choose("lush");
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(c);
                    ShipEntity.awaitingStarMapClient = false;
                    mc.setScreen(null);
                }
                if (timer > 60 && mc.level != null && mc.level.dimension().location().getPath().startsWith("planet_")) {
                    report("reach-planet", mc.player.getVehicle() instanceof ShipEntity, "arrived on " + mc.level.dimension().location() + " in the ship");
                    step = Step.DESCEND; timer = 0;
                }
                if (timer > 20 * 30) fail("reach-planet", "the Star Map choice didn't move the ship");
            }
            case DESCEND -> {
                down = true; up = forward = false;
                boolean low = mc.player.getVehicle() != null && mc.player.getVehicle().getDeltaMovement().y > -0.05 && timer > 40;
                if (low || timer > 20 * 60) {
                    down = false;
                    step = Step.DONE;
                    server.execute(MeltyTest::land);
                }
            }
            default -> {}
        }
    }

    private static void setup() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = server.overworld();
        var cat = PlanetCatalog.get(server);
        var nms = PlanetCatalog.nms();
        report("planet-names", nms != null && nms.localisation().size() > 100, "planets: " + cat.infos().stream().map(i -> i.name()).toList());
        BlockPos anchor = StarterWreck.build(level, player.blockPosition().offset(6, 0, 0));
        int ok = 0;
        for (SheetData.WreckBlock w : SheetData.WRECK) if (level.getBlockState(anchor.offset(w.dx(), w.dy(), w.dz())).getBlock() == ScrapSky.BLOCKS.get(w.part())) ok++;
        report("starter-wreck", ok == SheetData.WRECK.size(), ok + "/" + SheetData.WRECK.size() + " wreck blocks");
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 6));
        player.getInventory().add(new ItemStack(Items.REDSTONE, 4));
        boolean repaired = true;
        for (SheetData.WreckBlock w : SheetData.WRECK) {
            if (!w.part().equals("thruster_broken")) continue;
            BlockPos p = anchor.offset(w.dx(), w.dy(), w.dz());
            repaired &= PartBlock.repair(level.getBlockState(p), level, p, player);
        }
        report("thruster-repair", repaired, "both thrusters repaired with iron and redstone");
        BlockPos seat = StarterWreck.seat(anchor);
        player.teleportTo(level, seat.getX() + 0.5, seat.getY() + 1, seat.getZ() + 2.5, 180f, 0f);
        ShipEntity ship = ShipAssembler.board(level, seat, player);
        report("ship-assembles", ship != null && player.getVehicle() == ship, ship == null ? "no ship" : ship.blocks().size() + " blocks flying");
        Minecraft.getInstance().execute(() -> { step = ship != null ? Step.CLIMB : Step.DONE; timer = 0; });
    }

    private static void land() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (!(player.getVehicle() instanceof ShipEntity ship)) { fail("ship-lands", "not in a ship"); return; }
        // settle onto the ground, then land
        ServerLevel level = (ServerLevel) ship.level();
        int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ship.getBlockX(), ship.getBlockZ());
        ship.moveTo(ship.getX(), Math.max(ground, ship.getY() - 40) + 1, ship.getZ());
        player.stopRiding();
        report("ship-lands", ship.isRemoved(), ship.isRemoved() ? "the ship became blocks again on the planet" : "couldn't land");
    }

    private static void fail(String check, String why) {
        report(check, false, why);
        step = Step.DONE;
        up = down = forward = false;
    }

    static synchronized void report(String check, boolean ok, String detail) {
        if (checks == null || !reported.add(check)) return;
        String line = ok ? "pass " + check : "fail " + check + ": " + detail;
        ScrapSky.LOG.info("[melty test] {} ({})", line, detail);
        try {
            Files.createDirectories(checks.toAbsolutePath().getParent());
            Files.writeString(checks, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ScrapSky.LOG.warn("couldn't write Melty test checks: {}", e.toString());
        }
    }
}
