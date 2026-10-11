package dev.scrapsky.ship;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.block.PartBlock;
import dev.scrapsky.gen.SheetData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sheet systems:ship_assembly - every Scrap Sky part touching the seat becomes one flying Ship. */
public final class ShipAssembler {
    public static final int MAX_PARTS = 1024;

    private ShipAssembler() {}

    public record Scan(List<BlockPos> positions, int mass, int lift, int thrusters) {}

    public static Scan scan(ServerLevel level, BlockPos seat) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seat);
        seen.add(seat);
        int mass = 0, lift = 0, thrusters = 0;
        while (!queue.isEmpty() && found.size() <= MAX_PARTS) {
            BlockPos p = queue.poll();
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof PartBlock pb)) continue;
            found.add(p);
            mass += pb.part().mass();
            lift += pb.part().lift();
            if (pb.part().kind() == SheetData.Kind.THRUSTER) thrusters++;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (seen.add(n)) queue.add(n);
            }
        }
        return new Scan(found, mass, lift, thrusters);
    }

    /** Use of a Driver's Seat: assemble and seat the player, or say why not. */
    public static ShipEntity board(ServerLevel level, BlockPos seat, ServerPlayer player) {
        if (player.isPassenger()) return null;
        Scan scan = scan(level, seat);
        if (scan.positions().size() > MAX_PARTS) {
            player.displayClientMessage(Component.translatable("scrapsky.msg.too_big", MAX_PARTS), true);
            return null;
        }
        if (scan.thrusters() == 0) {
            player.displayClientMessage(Component.translatable("scrapsky.msg.no_thruster"), true);
            return null;
        }
        if (scan.lift() < scan.mass()) {
            player.displayClientMessage(Component.translatable("scrapsky.msg.too_heavy", scan.mass(), scan.lift()), true);
            return null;
        }
        BlockState seatState = level.getBlockState(seat);
        Direction facing = seatState.getValue(PartBlock.FACING);
        float yaw = facing.toYRot();
        Rotation toLocal = inverse(ShipEntity.rotationFor(yaw));

        List<ShipEntity.ShipBlock> blocks = new ArrayList<>();
        for (BlockPos p : scan.positions()) {
            BlockPos rel = p.subtract(seat).rotate(toLocal);
            blocks.add(new ShipEntity.ShipBlock(rel, level.getBlockState(p).rotate(toLocal)));
        }
        // remove top-down so nothing pops off
        List<BlockPos> order = new ArrayList<>(scan.positions());
        order.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos p : order) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE);
        for (BlockPos p : order) level.blockUpdated(p, Blocks.AIR);

        ShipEntity ship = new ShipEntity(ScrapSky.SHIP, level);
        ship.setBlocks(blocks, scan.mass(), scan.lift());
        ship.placeAtSeat(seat, yaw);
        level.addFreshEntity(ship);
        player.startRiding(ship, true);
        level.playSound(null, seat, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1f, 0.6f);
        player.displayClientMessage(Component.translatable("scrapsky.msg.flying"), false);
        return ship;
    }

    public static Rotation inverse(Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> r;
        };
    }
}
