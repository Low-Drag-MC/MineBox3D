package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.entity.EntityAuthority;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** <b>Whose word an entity's position is</b> ({@link EntityAuthority}): Minecraft's rule, and a mod's say over it. */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityAuthorityGameTest {

    private static final String SERVER_MOVES = "minebox3d_test_server_moves";

    static {
        EntityAuthority.register(entity -> entity.getTags().contains(SERVER_MOVES) ? Boolean.FALSE : null);
    }

    private EntityAuthorityGameTest() {
    }

    /**
     * A player is its client's to move, and a mob the server's — until a mod says a player is the server's (a mover that
     * runs on the server and only lets its client predict): then the server sweeps it among the bodies, keeps its base
     * and pushes from it, as for any entity it moves.
     */
    @GameTest(template = "empty")
    @PrefixGameTestTemplate(false)
    @SuppressWarnings("removal")
    public static void aModSaysWhoseWordAPlayersPositionIs(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(1, 2, 1));
        helper.assertTrue(EntityAuthority.clientMoves(player), "a player is its client's, by Minecraft's rule");
        helper.assertTrue(EntityCollisions.isClientMoved(player), "and the server takes its word");
        helper.assertFalse(EntityAuthority.clientMoves(pig), "a mob is the server's");

        player.addTag(SERVER_MOVES);
        helper.assertFalse(EntityAuthority.clientMoves(player), "a mod's say over it");
        helper.assertFalse(EntityCollisions.isClientMoved(player), "and the server moves it among the bodies");
        player.removeTag(SERVER_MOVES);
        helper.assertTrue(EntityAuthority.clientMoves(player), "with no say, Minecraft's rule again");
        player.discard();
        helper.succeed();
    }
}
