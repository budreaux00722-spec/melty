package dev.scrapsky.world;

import dev.scrapsky.ScrapSky;
import dev.scrapsky.block.PartBlock;
import dev.scrapsky.gen.SheetData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

/** Sheet systems:starter_wreck - the crash-landed ship from sheet "wreck", built once per world. */
public final class StarterWreck {
    private StarterWreck() {}

    public static final class State extends SavedData {
        public boolean built;
        public BlockPos anchor = BlockPos.ZERO;

        public static State load(CompoundTag tag, HolderLookup.Provider registries) {
            State s = new State();
            s.built = tag.getBoolean("Built");
            s.anchor = BlockPos.of(tag.getLong("Anchor"));
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("Built", built);
            tag.putLong("Anchor", anchor.asLong());
            return tag;
        }
    }

    public static State state(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(State::new, State::load, null), "scrapsky_wreck");
    }

    public static void ensure(MinecraftServer server, ServerPlayer player) {
        // every Scrap Sky part shows in the recipe book
        player.awardRecipes(server.getRecipeManager().getRecipes().stream()
                .filter(r -> r.id().getNamespace().equals(ScrapSky.MODID)).toList());
        ServerLevel level = server.overworld();
        State st = state(level);
        if (st.built) return;
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos anchor = build(level, spawn.offset(8, 0, 0));
        st.built = true;
        st.anchor = anchor;
        st.setDirty();
        player.sendSystemMessage(Component.translatable("scrapsky.msg.wreck"));
        ScrapSky.LOG.info("Starter wreck built at {}", anchor);
    }

    /** Builds the wreck with its anchor on the surface at (x, z); returns the anchor. */
    public static BlockPos build(ServerLevel level, BlockPos near) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
        BlockPos anchor = new BlockPos(near.getX(), y, near.getZ());
        // clear the space above the hull and give it solid ground
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            for (int dy = 0; dy <= 4; dy++) level.setBlock(anchor.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos below = anchor.offset(dx, -1, dz);
            if (level.getBlockState(below).canBeReplaced()) level.setBlock(below, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // salvage crate (sheet "supplies")
        for (SheetData.Supply sup : SheetData.SUPPLIES) {
            BlockPos at = anchor.offset(sup.dx(), sup.dy(), sup.dz());
            if (!level.getBlockState(at).is(Blocks.CHEST)) level.setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(sup.item()));
                for (int i = 0; i < chest.getContainerSize(); i++) {
                    if (chest.getItem(i).isEmpty()) { chest.setItem(i, new net.minecraft.world.item.ItemStack(item, sup.count())); break; }
                }
            }
        }
        for (SheetData.WreckBlock w : SheetData.WRECK) {
            PartBlock block = ScrapSky.BLOCKS.get(w.part());
            BlockState s = block.defaultBlockState();
            if (!w.facing().equals("-") && block.facing()) s = s.setValue(PartBlock.FACING, Direction.byName(w.facing()));
            level.setBlock(anchor.offset(w.dx(), w.dy(), w.dz()), s, Block.UPDATE_ALL);
        }
        return anchor;
    }

    public static BlockPos seat(BlockPos anchor) {
        for (SheetData.WreckBlock w : SheetData.WRECK) if (w.id().equals("seat")) return anchor.offset(w.dx(), w.dy(), w.dz());
        throw new IllegalStateException("wreck sheet has no seat");
    }
}
