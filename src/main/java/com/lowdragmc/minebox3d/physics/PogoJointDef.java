package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3PogoJointDef}: a spring along frame B's y axis between a character body (B) and what it stands on
 * (A), holding the body {@link #restLength} above the point frame A marks — the ground under a dynamic mover. Its
 * reaction goes into A: a body stood on is weighed down. Box3D's sample makes one each step under where a ray down from
 * the character lands. ⚠️ Box3D marks it experimental.
 */
public final class PogoJointDef extends JointDef<PogoJoint, PogoJointDef> {
    /** The ground's normal at the contact, world space, unit. */
    public float normalX, normalY, normalZ;
    /** Spring stiffness, Hz; 0, Box3D's default, turns the joint off. */
    public float hertz;

    public float dampingRatio;
    /** m */
    public float restLength;
    /** N: how hard it pulls the body down onto the ground — how sticky the body is to it. */
    public float maxTensionForce;
    /** N: how hard it pushes the body up off the ground. */
    public float maxCompressionForce;
    /** The spring's state carried over from the one made the step before: impulse (N·s) and speed (m/s). */
    public float impulse, velocity;

    public PogoJointDef() {
        super(JointType.POGO);
    }

    @Override
    PogoJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new PogoJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.unitVector(normalX, normalY, normalZ, "normal");
        Checks.notNegative(hertz, "hertz");
        Checks.notNegative(dampingRatio, "damping ratio");
        Checks.notNegative(restLength, "rest length");
        Checks.notNegative(maxTensionForce, "max tension force");
        Checks.notNegative(maxCompressionForce, "max compression force");
        Checks.finite(impulse, velocity, 0, "impulse and velocity");
    }

    public PogoJointDef normal(float x, float y, float z) {
        normalX = x;
        normalY = y;
        normalZ = z;
        return this;
    }

    public PogoJointDef spring(float hertz, float dampingRatio, float restLength) {
        this.hertz = hertz;
        this.dampingRatio = dampingRatio;
        this.restLength = restLength;
        return this;
    }

    public PogoJointDef maxForces(float tension, float compression) {
        maxTensionForce = tension;
        maxCompressionForce = compression;
        return this;
    }
}
