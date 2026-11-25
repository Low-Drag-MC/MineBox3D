package com.lowdragmc.minebox3d.physics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Consumer;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * <b>A chain of constraint joints as a physics asset makes one</b>: EntityStudio's yunling belt chain (a 26 g link at the
 * top, a 383 g bead four links down, a pendant below), its bodies, masses and joint frames as the asset made them, hanging
 * from a still anchor. It once spun its top link up to thousands of turns a second and the step gave out within half a
 * second: the fully locked point pushed the parent at the child's anchor and kept its impulse along the parent's turning
 * axes (doc 05 §11).
 */
class ConstraintJointChainTest {

    /** Each link: position (3), rotation (4), mass, centre (3), inertia (9, column by column), capsule ends (6) and radius. */
    private static final double[][] LINKS = {
        {
            0.051783859729766846,
            0.8911759853363037,
            -0.18063051998615265,
            -0.0016964226,
            0.23084609,
            0.9340859,
            0.27237946,
            0.02587147,
            1.02851955E-4,
            0.04539337,
            6.6830252E-9,
            4.0216164E-6,
            -7.4326296E-9,
            1.913987E-15,
            -7.4326296E-9,
            7.3507E-7,
            8.4632757E-13,
            1.913987E-15,
            8.4632757E-13,
            4.0216332E-6,
            6.850633E-5,
            0.030206405,
            1.0593841E-8,
            1.3719758E-4,
            0.060580336,
            2.7722082E-9,
            0.007736831
        },
        {
            0.02177060768008232,
            0.8472994565963745,
            -0.15528734028339386,
            -0.025385065,
            0.22945258,
            0.9011899,
            0.3668293,
            0.049807563,
            -0.0017004528,
            0.028992303,
            1.3969841E-9,
            1.6022195E-5,
            0,
            0,
            0,
            2.3414125E-6,
            0,
            0,
            0,
            1.6022195E-5,
            -0.0017004528,
            0.0059683863,
            1.3969841E-9,
            -0.0017004528,
            0.05201622,
            1.3969841E-9,
            0.009920198
        },
        {
            -0.0174437016248703,
            0.8108385801315308,
            -0.1322689652442932,
            -0.05601507,
            0.22395393,
            0.84366447,
            0.48470312,
            0.06853862,
            -0.0018996311,
            0.028558524,
            3.0267984E-9,
            2.9787681E-5,
            0,
            0,
            0,
            4.2400834E-6,
            0,
            0,
            0,
            2.9787681E-5,
            -0.0018996312,
            0.0016494449,
            3.0267984E-9,
            -0.0018996312,
            0.055467606,
            3.0267984E-9,
            0.011376269
        },
        {
            -0.06614337861537933,
            0.7860068082809448,
            -0.11357451975345612,
            -0.0863084,
            0.21411212,
            0.7689153,
            0.5962179,
            0.12611222,
            -0.0012200541,
            0.029471304,
            1.7271748E-8,
            4.4837994E-5,
            1.2289E-6,
            -3.9494528E-13,
            1.2289E-6,
            2.0949075E-5,
            7.6958795E-12,
            -3.9494528E-13,
            7.6958795E-12,
            4.4901048E-5,
            -0.0019807485,
            0.044298086,
            1.2507966E-8,
            -4.5935967E-4,
            0.014644523,
            2.203553E-8,
            0.019103123
        },
        {
            -0.12181584537029266,
            0.7744874954223633,
            -0.10036313533782959,
            -0.12185856,
            0.19607054,
            0.65487254,
            0.7196169,
            0.38279805,
            -0.011283122,
            0.033579655,
            -0.0029429323,
            2.611353E-4,
            -2.9336147E-6,
            -2.6991927E-7,
            -2.9336147E-6,
            2.3459252E-4,
            -2.4713652E-6,
            -2.6992416E-7,
            -2.4713627E-6,
            2.6122833E-4,
            -0.010806988,
            0.037939623,
            -0.0025418221,
            -0.011759258,
            0.029219689,
            -0.003344043,
            0.038397998
        },
        {
            -0.17556160688400269,
            0.776694655418396,
            -0.09523183852434158,
            0.7109357,
            0.084653825,
            0.6567918,
            0.23670335,
            0.092419595,
            8.35726E-5,
            0.058157336,
            -6.808075E-4,
            1.9269939E-5,
            2.4536233E-8,
            4.373638E-10,
            2.4536233E-8,
            1.45080985E-5,
            -8.4883126E-8,
            4.373639E-10,
            -8.4883126E-8,
            1.9268551E-5,
            1.118435E-4,
            0.052670546,
            -7.786107E-4,
            5.5301705E-5,
            0.06364412,
            -5.830043E-4,
            0.019103235
        },
        {
            -0.18977239727973938,
            0.7115470767021179,
            -0.061841003596782684,
            0.7341102,
            0.14805256,
            0.6437996,
            0.15711346,
            0.31063116,
            -2.3386836E-4,
            0.07059335,
            0.0039229523,
            5.7899544E-4,
            2.0133266E-6,
            1.3941768E-7,
            2.0133266E-6,
            7.1804025E-5,
            -3.5118035E-5,
            1.3941768E-7,
            -3.511804E-5,
            5.7657156E-4,
            -4.619563E-4,
            0.12805162,
            0.007901443,
            -5.780399E-6,
            0.013135072,
            -5.5538956E-5,
            0.021568276
        },
        {
            -0.1886526495218277,
            0.6441895365715027,
            -0.03054560348391533,
            0.7462854,
            0.19005537,
            0.6305923,
            0.096385546,
            0.1775326,
            -5.540025E-4,
            0.028099062,
            0.0037525203,
            1.2024515E-4,
            -1.0545339E-6,
            8.667972E-8,
            -1.0545339E-6,
            3.26349E-5,
            7.2028142E-6,
            8.667656E-8,
            7.2028142E-6,
            1.1966581E-4,
            -2.166357E-4,
            0.05613129,
            0.001448194,
            -8.9136924E-4,
            6.683543E-5,
            0.006056847,
            0.019629156
        },
    };
    /** Each joint: frame A's position (3) and rotation (4) on the link above, frame B's rotation (4) at its own link's origin. */
    private static final float[][] JOINTS = {
        {
            0.07621674f,
            0.12537606f,
            -0.12171024f,
            -0.4492337f,
            0.88738805f,
            -0.046718314f,
            0.09246016f,
            0.70178396f,
            0.700199f,
            -0.092686735f,
            -0.09289715f
        },
        {
            8.1956394E-8f,
            0.058891665f,
            -5.820767E-11f,
            0.75248504f,
            0.649215f,
            -0.07240753f,
            -0.083925106f,
            0.6818644f,
            0.7230317f,
            -0.080640405f,
            -0.07604853f
        },
        {
            1.3521641E-7f,
            0.05828373f,
            -1.1141311E-9f,
            0.68228185f,
            0.72202104f,
            -0.08618325f,
            -0.07582606f,
            0.57897335f,
            0.8072254f,
            -0.095598616f,
            -0.06354539f
        },
        {
            7.0780516E-8f,
            0.057773307f,
            1.990702E-8f,
            0.78256863f,
            0.61745536f,
            -0.049302507f,
            -0.06248659f,
            0.6901283f,
            0.7192967f,
            -0.05743453f,
            -0.055105165f
        },
        {
            2.2351742E-8f,
            0.05836659f,
            -2.8172508E-8f,
            0.7139634f,
            0.6937017f,
            -0.08938482f,
            -0.032320485f,
            0.5839683f,
            0.8061928f,
            -0.09361281f,
            -0.01646005f
        },
        {
            -0.0038068797f,
            0.053890795f,
            -0.001050268f,
            0.050437763f,
            0.72252274f,
            0.6878577f,
            0.04763232f,
            0.70748705f,
            0.70626545f,
            -0.021692112f,
            -0.013445699f
        },
        {
            -1.6880223E-8f,
            0.074572735f,
            2.9802326E-8f,
            0.6948529f,
            0.70686936f,
            0.09182977f,
            -0.09530218f,
            0.70555156f,
            0.708096f,
            0.015930919f,
            -0.023307657f
        },
        {
            -1.816079E-8f,
            0.07428109f,
            0.0f,
            -0.6886182f,
            -0.7109538f,
            -0.11193658f,
            0.08843061f,
            -0.6992033f,
            -0.71167755f,
            -0.057311036f,
            0.036678687f
        },
    };

    private static final float STEP = 1 / 60f;

    /** The anchor, still where the walk put her hips, and the links below it, colliding with nothing. */
    private static Body[] chain(PhysicsWorld world) {
        Body[] bodies = new Body[LINKS.length + 1];
        bodies[0] = world.createBody(new BodyDef()
                .type(BodyType.KINEMATIC)
                .position(5.120644345879555E-4, 0.8553354144096375, -5.522841238416731E-4)
                .rotation(-0.2950088f, 0.063235626f, 0.029076753f, 0.95295626f));
        for (int i = 0; i < LINKS.length; i++) {
            double[] d = LINKS[i];
            Body link = world.createBody(new BodyDef()
                    .type(BodyType.DYNAMIC)
                    .position(d[0], d[1], d[2])
                    .rotation((float) d[3], (float) d[4], (float) d[5], (float) d[6])
                    .damping(0.3f, 1));
            link.createShape(
                    new ShapeDef().filter(1, 0, 0),
                    new Geometry.Capsule(
                            (float) d[20],
                            (float) d[21],
                            (float) d[22],
                            (float) d[23],
                            (float) d[24],
                            (float) d[25],
                            (float) d[26]));
            MassData mass = new MassData().set((float) d[7], (float) d[8], (float) d[9], (float) d[10]);
            mass.inertia.set(
                    (float) d[11],
                    (float) d[12],
                    (float) d[13],
                    (float) d[14],
                    (float) d[15],
                    (float) d[16],
                    (float) d[17],
                    (float) d[18],
                    (float) d[19]);
            link.setMassData(mass);
            bodies[i + 1] = link;
        }
        return bodies;
    }

    private static <J extends Joint> void join(PhysicsWorld world, Body[] bodies, JointDef<J, ?> def, int joint) {
        float[] f = JOINTS[joint];
        world.createJoint(def.bodies(bodies[joint], bodies[joint + 1])
                .localFrameA(f[0], f[1], f[2], new Quaternionf(f[3], f[4], f[5], f[6]))
                .localFrameB(0, 0, 0, new Quaternionf(f[7], f[8], f[9], f[10])));
    }

    private static Body[] constrained(PhysicsWorld world, Consumer<ConstraintJointSettings> tune) {
        Body[] bodies = chain(world);
        for (int i = 0; i < JOINTS.length; i++) {
            ConstraintJointSettings settings = new ConstraintJointSettings();
            tune.accept(settings);
            join(world, bodies, new ConstraintJointDef().settings(settings), i);
        }
        return bodies;
    }

    private static float fastestSpin(Body[] bodies) {
        float most = 0;
        Vector3f w = new Vector3f();
        for (int i = 1; i < bodies.length; i++) {
            float spin = bodies[i].angularVelocity(w).length();
            most = Float.isFinite(spin) ? Math.max(most, spin) : Float.POSITIVE_INFINITY;
        }
        return most;
    }

    @Test
    void aPointLockedEveryWayIsHeldAsBox3DsSphericalJointHoldsIt() {
        try (PhysicsWorld ours = PhysicsWorld.create(new WorldDef().gravity(0, -32, 0));
                PhysicsWorld box3d = PhysicsWorld.create(new WorldDef().gravity(0, -32, 0))) {
            // no angle limited or driven, no conditioning: nothing left but the point
            Body[] a = constrained(ours, s -> s.massConditioning = false);
            Body[] b = chain(box3d);
            for (int i = 0; i < JOINTS.length; i++) {
                join(box3d, b, new SphericalJointDef(), i);
            }
            Vector3d pa = new Vector3d(), pb = new Vector3d();
            float apart = 0;
            for (int frame = 0; frame < 30; frame++) {
                ours.step(STEP, 4);
                box3d.step(STEP, 4);
                for (int i = 1; i < a.length; i++) {
                    apart = Math.max(apart, (float) a[i].position(pa).distance(b[i].position(pb)));
                }
            }
            assertTrue(apart < 1.0e-3f, "half a second in, every link within a millimetre of Box3D's: " + apart);
        }
    }

    @Test
    void aLightChainUnderAHeavyBeadHangsWithoutBlowingUp() {
        try (PhysicsWorld world = PhysicsWorld.create(new WorldDef().gravity(0, -32, 0))) {
            // as the asset makes them: twist ±20°, both swings 45° in an ellipse, conditioning on
            Body[] bodies = constrained(world, s -> {
                s.twistMotion = s.swing1Motion = s.swing2Motion = ConstraintMotion.LIMITED;
                s.lowerTwistLimit = (float) Math.toRadians(-20);
                s.upperTwistLimit = (float) Math.toRadians(20);
                s.swing1Limit = s.swing2Limit = (float) Math.toRadians(45);
            });
            float most = 0;
            for (int frame = 0; frame < 5 * 60; frame++) {
                world.step(STEP, 4);
                most = Math.max(most, fastestSpin(bodies));
            }
            assertFalse(world.isFaulted());
            assertTrue(most < 500, "no link past 500 rad/s (it was thousands, then no number at all): " + most);
        }
    }
}
