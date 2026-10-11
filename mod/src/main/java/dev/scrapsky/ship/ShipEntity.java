package dev.scrapsky.ship;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.net.Net;
import dev.scrapsky.world.PlanetCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A flying ship made of Scrap Mechanic parts (sheet systems: ship_flight, ship_landing,
 * orbit_star_map). Blocks are stored in the ship's own frame: the Driver's Seat is at (0,0,0)
 * and local +Z is forward. The entity stands on the column of the seat, at the ship's lowest layer.
 */
public class ShipEntity extends Entity {
    public static final double ORBIT_Y = 300;
    public static final double REARM_Y = 285;

    public record ShipBlock(BlockPos rel, BlockState state) {}

    private static final EntityDataAccessor<CompoundTag> DATA_BLOCKS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.COMPOUND_TAG);

    private List<ShipBlock> blocks = List.of();
    private int mass = 1, lift = 0, minY = 0, maxY = 0, radius = 0;
    private boolean transferring;
    private boolean awaitingStarMap;

    // pilot input, set on the pilot's client each tick
    private boolean inForward, inBack, inLeft, inRight, inUp, inDown;

    public ShipEntity(EntityType<? extends ShipEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BLOCKS, new CompoundTag());
    }

    public List<ShipBlock> blocks() { return blocks; }
    public int minY() { return minY; }
    public int mass() { return mass; }
    public int lift() { return lift; }

    public void setBlocks(List<ShipBlock> list, int mass, int lift) {
        CompoundTag tag = new CompoundTag();
        writeBlocks(tag, list, mass, lift);
        this.entityData.set(DATA_BLOCKS, tag);
        applyBlocks(tag);
    }

    /** Puts the ship so the seat is back where it was. */
    public void placeAtSeat(BlockPos seat, float yaw) {
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.moveTo(seat.getX() + 0.5, seat.getY() + minY, seat.getZ() + 0.5, yaw, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_BLOCKS.equals(key)) applyBlocks(this.entityData.get(DATA_BLOCKS));
    }

    private void applyBlocks(CompoundTag tag) {
        List<ShipBlock> list = new ArrayList<>();
        ListTag states = tag.getList("States", Tag.TAG_COMPOUND);
        int[] pos = tag.getIntArray("Pos");
        var lookup = this.level().holderLookup(Registries.BLOCK);
        int lo = 0, hi = 0, r = 0;
        for (int i = 0; i < states.size() && i * 3 + 2 < pos.length; i++) {
            BlockPos p = new BlockPos(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
            list.add(new ShipBlock(p, NbtUtils.readBlockState(lookup, states.getCompound(i))));
            lo = Math.min(lo, p.getY());
            hi = Math.max(hi, p.getY());
            r = Math.max(r, Math.max(Math.abs(p.getX()), Math.abs(p.getZ())));
        }
        this.blocks = List.copyOf(list);
        this.mass = Math.max(1, tag.getInt("Mass"));
        this.lift = tag.getInt("Lift");
        this.minY = lo;
        this.maxY = hi;
        this.radius = r;
        this.refreshDimensions();
    }

    private static void writeBlocks(CompoundTag tag, List<ShipBlock> list, int mass, int lift) {
        ListTag states = new ListTag();
        int[] pos = new int[list.size() * 3];
        for (int i = 0; i < list.size(); i++) {
            ShipBlock b = list.get(i);
            states.add(NbtUtils.writeBlockState(b.state()));
            pos[i * 3] = b.rel().getX();
            pos[i * 3 + 1] = b.rel().getY();
            pos[i * 3 + 2] = b.rel().getZ();
        }
        tag.put("States", states);
        tag.putIntArray("Pos", pos);
        tag.putInt("Mass", mass);
        tag.putInt("Lift", lift);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // a square that holds the ship at any heading
        return EntityDimensions.fixed(Math.max(1, radius * 2 + 1), Math.max(1, maxY - minY + 1));
    }

    // ---------------------------------------------------------------- riding

    @Override
    public LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof Player p ? p : null;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty() && passenger instanceof Player;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dims, float scale) {
        // the seat is at local (0,0,0), on the entity's column; sit on top of the seat block
        return new Vec3(0, -minY + 0.5, 0);
    }

    @Override
    public boolean isPickable() { return !this.isRemoved(); }

    @Override
    public boolean canBeCollidedWith() { return true; }

    @Override
    public boolean isPushable() { return false; }

    /** A ship lowers itself under thrust: it never takes or passes on fall damage. */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive() || this.isVehicle()) return InteractionResult.PASS;
        if (!this.level().isClientSide) return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        return InteractionResult.SUCCESS;
    }

    /** The pilot always steps off on top of the Driver's Seat. */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos seat = BlockPos.containing(this.getX(), this.getY() - minY + 0.01, this.getZ());
        return new Vec3(seat.getX() + 0.5, seat.getY() + 1.0, seat.getZ() + 0.5);
    }

    private Entity remount;

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        passenger.resetFallDistance();
        if (!this.level().isClientSide && !transferring && !this.isRemoved()) {
            Vec3 standOn = getDismountLocationForPassenger(passenger instanceof LivingEntity le ? le : null);
            if (tryLand()) {
                // the ship is gone: stand the pilot on the seat it became
                if (passenger instanceof ServerPlayer sp) sp.connection.teleport(standOn.x, standOn.y, standOn.z, sp.getYRot(), sp.getXRot());
                else passenger.moveTo(standOn.x, standOn.y, standOn.z);
            } else if (passenger instanceof Player p) {
                // somewhere it can't land: keep the pilot aboard rather than dropping them
                p.displayClientMessage(Component.translatable("scrapsky.msg.cant_land"), true);
                if (p.isAlive()) remount = p;
            }
        }
    }

    public void setInput(boolean forward, boolean back, boolean left, boolean right, boolean up, boolean down) {
        this.inForward = forward; this.inBack = back; this.inLeft = left; this.inRight = right; this.inUp = up; this.inDown = down;
    }

    /** Blocks per tick: 0.35 + 0.55 * min(1, (lift - mass) / mass) (sheet systems:ship_flight). */
    public double speed() {
        return 0.35 + 0.55 * Math.min(1.0, Math.max(0, lift - mass) / (double) mass);
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        this.resetFallDistance();
        if (this.isControlledByLocalInstance()) {
            float yaw = this.getYRot();
            if (inLeft) yaw -= 4f;
            if (inRight) yaw += 4f;
            this.setYRot(yaw);
            double sp = speed();
            double f = (inForward ? 1 : 0) - (inBack ? 1 : 0);
            double v = (inUp ? 1 : 0) - (inDown ? 1 : 0);
            if (this.level().isClientSide && awaitingStarMapClient && v > 0) v = 0;
            Vec3 target = new Vec3(-Mth.sin(yaw * Mth.DEG_TO_RAD) * f * sp, v * sp * 0.7, Mth.cos(yaw * Mth.DEG_TO_RAD) * f * sp);
            Vec3 cur = this.getDeltaMovement();
            this.setDeltaMovement(cur.lerp(target, 0.25));
            this.move(MoverType.SELF, this.getDeltaMovement());
            if (this.getControllingPassenger() instanceof Player p) {
                // turn the pilot with the ship
                p.setYRot(p.getYRot() + (yaw - this.yRotO));
                p.setYHeadRot(p.getYHeadRot() + (yaw - this.yRotO));
            }
        } else if (!this.level().isClientSide) {
            this.setDeltaMovement(Vec3.ZERO);
        }
        if (!this.level().isClientSide) serverTick();
    }

    /** Client-side flag so the ship stops climbing while the Star Map is open. */
    public static boolean awaitingStarMapClient;

    private void serverTick() {
        if (remount != null) {
            Entity r = remount;
            remount = null;
            if (r.isAlive() && !r.isPassenger() && r.level() == this.level() && r.distanceToSqr(this) < 400) r.startRiding(this, true);
        }
        for (Entity e : this.getPassengers()) e.resetFallDistance();
        if (this.getControllingPassenger() instanceof ServerPlayer pilot) {
            if (!awaitingStarMap && this.getY() > ORBIT_Y) {
                awaitingStarMap = true;
                this.level().playSound(null, this.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1f, 0.7f);
                Net.openStarMap(pilot, PlanetCatalog.get(pilot.server), PlanetCatalog.idForLevel(this.level()));
            } else if (awaitingStarMap && this.getY() < REARM_Y) {
                awaitingStarMap = false;
            }
        }
    }

    // ---------------------------------------------------------------- landing

    public static Rotation rotationFor(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (q) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Snaps to the grid and a quarter turn and puts every block back; false (and keeps flying) if anything is in the way. */
    public boolean tryLand() {
        Level level = this.level();
        Rotation rot = rotationFor(this.getYRot());
        BlockPos seat = BlockPos.containing(this.getX(), this.getY() - minY + 0.01, this.getZ());
        List<BlockPos> targets = new ArrayList<>();
        for (ShipBlock b : blocks) {
            BlockPos p = seat.offset(b.rel().rotate(rot));
            BlockState there = level.getBlockState(p);
            if (!(there.canBeReplaced() || there.is(net.minecraft.tags.BlockTags.LEAVES)) || level.isOutsideBuildHeight(p))
                ScrapSky.LOG.info("Ship can't land at {}: {} is in the way at {}", seat, there, p);
            // open ground: air, plants, snow, water, or leaves (cleared), never other blocks
            if (!(there.canBeReplaced() || there.is(net.minecraft.tags.BlockTags.LEAVES)) || level.isOutsideBuildHeight(p)) return false;
            targets.add(p);
        }
        for (int i = 0; i < blocks.size(); i++) {
            level.setBlock(targets.get(i), blocks.get(i).state().rotate(rot), Block.UPDATE_ALL);
        }
        level.playSound(null, seat, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1f, 0.6f);
        ScrapSky.LOG.info("Ship landed with its seat at {} (ship y {})", seat, this.getY());
        this.discard();
        return true;
    }

    /** Moves this ship and its pilot to another dimension (the Star Map). */
    public void travelTo(ServerLevel target, double x, double y, double z) {
        if (!(this.getControllingPassenger() instanceof ServerPlayer pilot)) return;
        transferring = true;
        List<ShipBlock> keep = this.blocks;
        int m = this.mass, l = this.lift;
        float yaw = this.getYRot();
        pilot.stopRiding();
        this.discard();
        pilot.teleportTo(target, x, y + 1, z, yaw, pilot.getXRot());
        ShipEntity ship = new ShipEntity(ScrapSky.SHIP, target);
        ship.setBlocks(keep, m, l);
        ship.moveTo(x, y + ship.minY, z, yaw, 0);
        ship.awaitingStarMap = true; // arrive high up; re-arms once below REARM_Y
        target.addFreshEntity(ship);
        pilot.startRiding(ship, true);
    }

    // ---------------------------------------------------------------- save

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Ship")) {
            this.entityData.set(DATA_BLOCKS, tag.getCompound("Ship"));
            applyBlocks(tag.getCompound("Ship"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Ship", this.entityData.get(DATA_BLOCKS).copy());
    }
}
