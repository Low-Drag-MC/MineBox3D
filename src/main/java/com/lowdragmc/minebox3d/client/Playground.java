package com.lowdragmc.minebox3d.client;

import com.lowdragmc.minebox3d.mc.LevelPhysics;
import com.lowdragmc.minebox3d.physics.Body;
import com.lowdragmc.minebox3d.physics.BodyDef;
import com.lowdragmc.minebox3d.physics.BodyType;
import com.lowdragmc.minebox3d.physics.Geometry;
import com.lowdragmc.minebox3d.physics.ShapeDef;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>Bodies to try the terrain and entity streaming with</b>, made in the <i>client</i> level's physics
 * (nothing is sent anywhere): {@code /minebox3d spawn box|sphere|capsule [count]}, thrown from the
 * player's eyes; {@code /minebox3d clear} removes them.
 */
final class Playground {

    private enum Mark {
        MARK
    }

    private static final Random RANDOM = new Random();

    private Playground() {
    }

    enum Kind {
        BOX, SPHERE, CAPSULE
    }

    static int spawn(Kind kind, int count) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return 0;
        }
        LevelPhysics physics = LevelPhysics.of(minecraft.level);
        if (physics == null) {
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        ShapeDef shape = new ShapeDef().density(500).friction(0.6f);
        for (int i = 0; i < count; i++) {
            Vec3 at = eye.add(look.scale(2 + 0.6 * i)).add(RANDOM.nextGaussian() * 0.1, 0, RANDOM.nextGaussian() * 0.1);
            Vec3 velocity = look.scale(6);
            Body body = physics.createBody(new BodyDef().type(BodyType.DYNAMIC).position(at.x, at.y, at.z)
                    .linearVelocity((float) velocity.x, (float) velocity.y, (float) velocity.z)
                    .angularVelocity((float) RANDOM.nextGaussian(), (float) RANDOM.nextGaussian(), (float) RANDOM.nextGaussian())
                    .name("playground"));
            body.createShape(shape, switch (kind) {
                case BOX -> Geometry.Box.of(0.3f, 0.3f, 0.3f);
                case SPHERE -> new Geometry.Sphere(0, 0, 0, 0.3f);
                case CAPSULE -> new Geometry.Capsule(0, -0.3f, 0, 0, 0.3f, 0, 0.2f);
            });
            body.attach(Mark.class, Mark.MARK);
        }
        return count;
    }

    static int clear() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0;
        }
        LevelPhysics physics = LevelPhysics.existing(minecraft.level);
        if (physics == null) {
            return 0;
        }
        List<Body> mine = new ArrayList<>();
        for (Body body : physics.world().bodies()) {
            if (body.attachment(Mark.class) != null) {
                mine.add(body);
            }
        }
        mine.forEach(Body::destroy);
        return mine.size();
    }
}
