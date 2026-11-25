package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3WeldJointDef}: holds the two bodies together where they are — rigidly at zero hertz, or as a spring.
 * ⚠️ Box3D's solver cannot hold long chains of welded bodies perfectly rigid.
 */
public final class WeldJointDef extends JointDef<WeldJoint, WeldJointDef> {
    /** Hz, 0 = as stiff as possible. */
    public float linearHertz;
    /** Hz, 0 = as stiff as possible. */
    public float angularHertz;

    public float linearDampingRatio;
    public float angularDampingRatio;

    public WeldJointDef() {
        super(JointType.WELD);
    }

    @Override
    WeldJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new WeldJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        Checks.notNegative(linearHertz, "linear hertz");
        Checks.notNegative(angularHertz, "angular hertz");
        Checks.notNegative(linearDampingRatio, "linear damping ratio");
        Checks.notNegative(angularDampingRatio, "angular damping ratio");
    }

    public WeldJointDef springs(
            float linearHertz, float linearDampingRatio, float angularHertz, float angularDampingRatio) {
        this.linearHertz = linearHertz;
        this.linearDampingRatio = linearDampingRatio;
        this.angularHertz = angularHertz;
        this.angularDampingRatio = angularDampingRatio;
        return this;
    }
}
