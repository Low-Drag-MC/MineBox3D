package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3ParallelJointDef}: a spring that turns frame B's z axis back parallel to frame A's — keeps a body
 * upright (a ragdoll's torso while it still stands) without holding its position.
 */
public final class ParallelJointDef extends JointDef<ParallelJoint, ParallelJointDef> {
    public float hertz;
    public float dampingRatio;
    /** N·m */
    public float maxTorque;

    public ParallelJointDef() {
        super(JointType.PARALLEL);
    }

    @Override
    ParallelJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new ParallelJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.notNegative(hertz, "spring hertz");
        Checks.notNegative(dampingRatio, "spring damping ratio");
        Checks.notNegative(maxTorque, "max torque");
    }

    public ParallelJointDef spring(float hertz, float dampingRatio, float maxTorque) {
        this.hertz = hertz;
        this.dampingRatio = dampingRatio;
        this.maxTorque = maxTorque;
        return this;
    }
}
