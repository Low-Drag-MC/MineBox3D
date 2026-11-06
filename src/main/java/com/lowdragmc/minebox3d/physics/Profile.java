package com.lowdragmc.minebox3d.physics;

/**
 * Box3D's {@code b3Profile}: how long the last step spent in each phase, milliseconds. Read with
 * {@link PhysicsWorld#profile}; a result object the caller keeps.
 */
public final class Profile {
    public float step;
    public float pairs;
    public float collide;
    public float solve;
    public float solverSetup;
    public float constraints;
    public float prepareConstraints;
    public float integrateVelocities;
    public float warmStart;
    public float solveImpulses;
    public float integratePositions;
    public float relaxImpulses;
    public float restitution;
    public float storeImpulses;
    public float splitIslands;
    public float transforms;
    public float sensorHits;
    public float jointEvents;
    public float hitEvents;
    public float refit;
    public float bullets;
    public float sleepIslands;
    public float sensors;

    /** How many floats {@code b3Profile} is. */
    static final int FIELDS = 23;

    /** In {@code b3Profile}'s order. */
    void read(float[] v) {
        step = v[0];
        pairs = v[1];
        collide = v[2];
        solve = v[3];
        solverSetup = v[4];
        constraints = v[5];
        prepareConstraints = v[6];
        integrateVelocities = v[7];
        warmStart = v[8];
        solveImpulses = v[9];
        integratePositions = v[10];
        relaxImpulses = v[11];
        restitution = v[12];
        storeImpulses = v[13];
        splitIslands = v[14];
        transforms = v[15];
        sensorHits = v[16];
        jointEvents = v[17];
        hitEvents = v[18];
        refit = v[19];
        bullets = v[20];
        sleepIslands = v[21];
        sensors = v[22];
    }
}
