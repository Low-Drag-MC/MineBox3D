package com.lowdragmc.minebox3d.mc.terrain;

import com.lowdragmc.minebox3d.physics.SurfaceMaterial;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>The surface a block gives rigid bodies</b>: Box3D friction and restitution from Minecraft's
 * slipperiness, and a registry for blocks that want something else.
 *
 * <p>⚠️ Read once per block state for every block whose collision shape Minecraft caches per state (all
 * but the {@code dynamicShape} ones): a provider must give the same answer for every block of a state.
 */
public final class TerrainMaterials {

    /** Fills {@code out}, which starts as the default for the block. */
    @FunctionalInterface
    public interface Provider {
        void fill(Level level, BlockPos pos, BlockState state, SurfaceMaterial out);
    }

    private static final Map<Block, Provider> PROVIDERS = new ConcurrentHashMap<>();

    static {
        // a slime block throws bodies back up the way it does players
        register(Blocks.SLIME_BLOCK, (level, pos, state, out) -> out.restitution = 0.8f);
    }

    private TerrainMaterials() {}

    /** Gives every block of {@code block} its surface from {@code provider}, after the defaults. */
    public static void register(Block block, Provider provider) {
        PROVIDERS.put(block, provider);
    }

    /** The surface of the block at {@code pos} into {@code out}. */
    public static void fill(Level level, BlockPos pos, BlockState state, SurfaceMaterial out) {
        out.set(new SurfaceMaterial());
        out.friction = friction(state.getFriction(level, pos, null));
        out.restitution = 0;
        Provider provider = PROVIDERS.get(state.getBlock());
        if (provider != null) {
            provider.fill(level, pos, state, out);
        }
    }

    /**
     * Box3D's friction from Minecraft's: Minecraft stores slipperiness (0.6 for most blocks, 0.98 for ice), Box3D
     * a Coulomb coefficient (0.6 by default). {@code 1.5 * (1 - slipperiness)} gives 0.6 for ordinary blocks, 0.03
     * for ice and 0.3 for slime.
     */
    public static float friction(float slipperiness) {
        return Math.max(0f, 1.5f * (1f - slipperiness));
    }
}
