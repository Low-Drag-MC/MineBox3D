package com.lowdragmc.minebox3d.physics;

/**
 * MineBox3D's constraint joint ({@link JointType#CONSTRAINT}): one UE physics asset constraint between a parent (body
 * A) and a child (body B) — each of the six degrees of freedom free, limited or locked, soft limits, linear and angular
 * drives. What it does is {@link #settings}; the frames are the base definition's.
 */
public final class ConstraintJointDef extends JointDef<ConstraintJoint, ConstraintJointDef> {

    public final ConstraintJointSettings settings = new ConstraintJointSettings();

    public ConstraintJointDef() {
        super(JointType.CONSTRAINT);
    }

    public ConstraintJointDef settings(ConstraintJointSettings settings) {
        this.settings.set(settings);
        return this;
    }

    @Override
    ConstraintJoint wrap(PhysicsWorld world, long id, Body bodyA, Body bodyB) {
        return new ConstraintJoint(world, id, bodyA, bodyB);
    }

    @Override
    void checkFields() {
        settings.check();
    }
}
