package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#PARALLEL} joint — see {@link ParallelJointDef}. */
public final class ParallelJoint extends Joint {

    ParallelJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.PARALLEL;
    }

    public float springHertz() {
        return get(JointParam.PARALLEL_HERTZ);
    }

    public void setSpringHertz(float hertz) {
        Checks.notNegative(hertz, "spring hertz");
        set(JointParam.PARALLEL_HERTZ, hertz);
    }

    public float springDampingRatio() {
        return get(JointParam.PARALLEL_DAMPING);
    }

    public void setSpringDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "spring damping ratio");
        set(JointParam.PARALLEL_DAMPING, dampingRatio);
    }

    /** N·m */
    public float maxTorque() {
        return get(JointParam.PARALLEL_MAX_TORQUE);
    }

    public void setMaxTorque(float torque) {
        Checks.notNegative(torque, "max torque");
        set(JointParam.PARALLEL_MAX_TORQUE, torque);
    }
}
