package com.lowdragmc.minebox3d.test.gametest;

import com.lowdragmc.minebox3d.MineBox3D;
import com.lowdragmc.minebox3d.mc.entity.EntityAuthority;
import com.lowdragmc.minebox3d.mc.entity.EntityCollisions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** <b>Whose word an entity's position is</b> ({@link EntityAuthority}): Minecraft's rule, and a mod's say over it. */
@GameTestHolder(MineBox3D.MOD_ID)
public final class EntityAuthorityGameTest {

    private static final String SERVER_MOVES = "minebox3d_test_server_moves";
    private static final String PUSHES_NOTHING = "minebox3d_test_pushes_nothing";

    static {
        EntityAuthority.register(new EntityAuthority.Provider() {
            @Override
            public Boolean clientMoves(Entity entity) {
                return entity.getTags().contains(SERVER_MOVES) ? Boolean.FALSE : null;
            }

            @Override
            public Boolean pushesBodies(Entity entity) {
                return entity.getTags().contains(PUSHES_NOTHING) ? Boolean.FALSE : null;
            }
        });
    }

    private EntityAuthorityGameTest() {
    }

    /** A mod's say that the entity's moves push no body ({@link EntityAuthority#pushesBodies}): Unreal's Mover. */
    static <T extends Entity> T pushingNothing(T entity) {
        entity.addTag(PUSHES_NOTHING);
        return entity;
    }

    /**
     * A player is its client's to move, and a mob the server's — until a mod says a player is the server's (a mover that
     * runs on the server and only lets its client predict): then the server sweeps it among the bodies and keeps its
     * base, as for any entity it moves. The moves of both push bodies, unless a mod says they push none.
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

        helper.assertTrue(EntityAuthority.pushesBodies(player) && EntityAuthority.pushesBodies(pig),
                "the moves of a player and of a mob push bodies");
        pushingNothing(player);
        helper.assertFalse(EntityAuthority.pushesBodies(player), "unless a mod says its moves push none");
        player.discard();
        pig.discard();
        helper.succeed();
    }
}
