package dev.scrapsky.block;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.gen.SheetData;
import dev.scrapsky.ship.ShipAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A Scrap Mechanic part as a Minecraft block (one per row of sheet "parts"). */
public class PartBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Set only while a constructor runs, because createBlockStateDefinition is called from Block's constructor. */
    private static SheetData.Part constructing;

    private final SheetData.Part part;

    public PartBlock(Properties props, SheetData.Part part) {
        super(prepare(props, part));
        this.part = part;
        constructing = null;
        if (facing()) registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    private static Properties prepare(Properties p, SheetData.Part part) {
        constructing = part;
        return p;
    }

    public SheetData.Part part() { return part; }

    public boolean facing() {
        SheetData.Part p = part != null ? part : constructing;
        return p != null && (p.kind() == SheetData.Kind.THRUSTER || p.kind() == SheetData.Kind.BROKEN_THRUSTER || p.kind() == SheetData.Kind.SEAT);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        if (facing()) builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        if (!facing()) return defaultBlockState();
        Direction look = ctx.getHorizontalDirection();
        // seats face the way the player looks; thrusters point their nozzle back at the player
        return defaultBlockState().setValue(FACING, part.kind() == SheetData.Kind.SEAT ? look : look.getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return facing() ? state.setValue(FACING, rotation.rotate(state.getValue(FACING))) : state;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return facing() ? state.rotate(mirror.getRotation(state.getValue(FACING))) : state;
    }

    // ---- glass behaves like glass ----

    @Override
    protected boolean skipRendering(BlockState state, BlockState neighbor, Direction dir) {
        return part.kind() == SheetData.Kind.GLASS && neighbor.is(this) || super.skipRendering(state, neighbor, dir);
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return part.kind() == SheetData.Kind.GLASS ? Shapes.empty() : super.getVisualShape(state, level, pos, ctx);
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return part.kind() == SheetData.Kind.GLASS ? 1.0f : super.getShadeBrightness(state, level, pos);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return part.kind() == SheetData.Kind.GLASS || super.propagatesSkylightDown(state, level, pos);
    }

    // ---- interactions ----

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (part.kind() == SheetData.Kind.BROKEN_THRUSTER) {
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            return repair(state, level, pos, player) ? ItemInteractionResult.SUCCESS : ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (part.kind() == SheetData.Kind.BROKEN_THRUSTER) {
            if (!level.isClientSide) repair(state, level, pos, player);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (part.kind() == SheetData.Kind.SEAT) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) ShipAssembler.board((ServerLevel) level, pos, sp);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** Sheet systems:thruster_repair - 3 iron ingots and 2 redstone turn a Broken Thruster into a Thruster. */
    public static boolean repair(BlockState state, Level level, BlockPos pos, Player player) {
        Inventory inv = player.getInventory();
        if (!player.getAbilities().instabuild) {
            if (inv.countItem(Items.IRON_INGOT) < 3 || inv.countItem(Items.REDSTONE) < 2) {
                player.displayClientMessage(Component.translatable("scrapsky.msg.need_parts"), true);
                return false;
            }
            take(inv, Items.IRON_INGOT, 3);
            take(inv, Items.REDSTONE, 2);
        }
        BlockState fixed = ScrapSky.BLOCKS.get("thruster").defaultBlockState().setValue(FACING, state.getValue(FACING));
        level.setBlock(pos, fixed, Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8f, 1.2f);
        player.displayClientMessage(Component.translatable("scrapsky.msg.repaired"), true);
        return true;
    }

    private static void take(Inventory inv, Item item, int n) {
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                int k = Math.min(n, s.getCount());
                s.shrink(k);
                n -= k;
            }
        }
        inv.setChanged();
    }
}
