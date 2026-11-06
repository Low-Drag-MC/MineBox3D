package com.lowdragmc.minebox3d.physics;

/** A {@link JointType#WELD} joint — see {@link WeldJointDef}. Zero hertz = as stiff as possible. */
public final class WeldJoint extends Joint {

    WeldJoint(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        super(world, id, bodyA, bodyB);
    }

    @Override
    public JointType type() {
        return JointType.WELD;
    }

    public float linearHertz() {
        return get(JointParam.WELD_LINEAR_HERTZ);
    }

    public void setLinearHertz(float hertz) {
        Checks.notNegative(hertz, "linear hertz");
        set(JointParam.WELD_LINEAR_HERTZ, hertz);
    }

    public float linearDampingRatio() {
        return get(JointParam.WELD_LINEAR_DAMPING);
    }

    public void setLinearDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "linear damping ratio");
        set(JointParam.WELD_LINEAR_DAMPING, dampingRatio);
    }

    public float angularHertz() {
        return get(JointParam.WELD_ANGULAR_HERTZ);
    }

    public void setAngularHertz(float hertz) {
        Checks.notNegative(hertz, "angular hertz");
        set(JointParam.WELD_ANGULAR_HERTZ, hertz);
    }

    public float angularDampingRatio() {
        return get(JointParam.WELD_ANGULAR_DAMPING);
    }

    public void setAngularDampingRatio(float dampingRatio) {
        Checks.notNegative(dampingRatio, "angular damping ratio");
        set(JointParam.WELD_ANGULAR_DAMPING, dampingRatio);
    }
}
