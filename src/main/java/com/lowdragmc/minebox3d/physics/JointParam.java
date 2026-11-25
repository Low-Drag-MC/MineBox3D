package com.lowdragmc.minebox3d.physics;

/**
 * The runtime joint properties by number, for {@code B3.jointSet / jointGet}. ⚠️ The same numbers as
 * {@code enum mb3_joint_param} in {@code native/src/mb3_joint.c}; {@code JointApiTest} sets and reads every one back.
 */
final class JointParam {

    private JointParam() {}

    static final int FORCE_THRESHOLD = 0;
    static final int TORQUE_THRESHOLD = 1;
    static final int CONSTRAINT_TUNING = 2;
    static final int COLLIDE_CONNECTED = 3;
    static final int LINEAR_SEPARATION = 4;
    static final int ANGULAR_SEPARATION = 5;
    static final int CONSTRAINT_FORCE = 6;
    static final int CONSTRAINT_TORQUE = 7;
    static final int AWAKE = 8;

    static final int DISTANCE_LENGTH = 10;
    static final int DISTANCE_SPRING = 11;
    static final int DISTANCE_SPRING_FORCE_RANGE = 12;
    static final int DISTANCE_HERTZ = 13;
    static final int DISTANCE_DAMPING = 14;
    static final int DISTANCE_LIMIT = 15;
    static final int DISTANCE_LENGTH_RANGE = 16;
    static final int DISTANCE_CURRENT_LENGTH = 17;
    static final int DISTANCE_MOTOR = 18;
    static final int DISTANCE_MOTOR_SPEED = 19;
    static final int DISTANCE_MAX_MOTOR_FORCE = 20;
    static final int DISTANCE_MOTOR_FORCE = 21;

    static final int MOTOR_LINEAR_VELOCITY = 30;
    static final int MOTOR_ANGULAR_VELOCITY = 31;
    static final int MOTOR_MAX_VELOCITY_FORCE = 32;
    static final int MOTOR_MAX_VELOCITY_TORQUE = 33;
    static final int MOTOR_LINEAR_HERTZ = 34;
    static final int MOTOR_LINEAR_DAMPING = 35;
    static final int MOTOR_ANGULAR_HERTZ = 36;
    static final int MOTOR_ANGULAR_DAMPING = 37;
    static final int MOTOR_MAX_SPRING_FORCE = 38;
    static final int MOTOR_MAX_SPRING_TORQUE = 39;

    static final int PARALLEL_HERTZ = 40;
    static final int PARALLEL_DAMPING = 41;
    static final int PARALLEL_MAX_TORQUE = 42;

    static final int PRISMATIC_SPRING = 50;
    static final int PRISMATIC_HERTZ = 51;
    static final int PRISMATIC_DAMPING = 52;
    static final int PRISMATIC_TARGET_TRANSLATION = 53;
    static final int PRISMATIC_LIMIT = 54;
    static final int PRISMATIC_LIMITS = 55;
    static final int PRISMATIC_MOTOR = 56;
    static final int PRISMATIC_MOTOR_SPEED = 57;
    static final int PRISMATIC_MAX_MOTOR_FORCE = 58;
    static final int PRISMATIC_MOTOR_FORCE = 59;
    static final int PRISMATIC_TRANSLATION = 60;
    static final int PRISMATIC_SPEED = 61;

    static final int REVOLUTE_SPRING = 70;
    static final int REVOLUTE_HERTZ = 71;
    static final int REVOLUTE_DAMPING = 72;
    static final int REVOLUTE_TARGET_ANGLE = 73;
    static final int REVOLUTE_ANGLE = 74;
    static final int REVOLUTE_LIMIT = 75;
    static final int REVOLUTE_LIMITS = 76;
    static final int REVOLUTE_MOTOR = 77;
    static final int REVOLUTE_MOTOR_SPEED = 78;
    static final int REVOLUTE_MOTOR_TORQUE = 79;
    static final int REVOLUTE_MAX_MOTOR_TORQUE = 80;

    static final int SPHERICAL_CONE_LIMIT = 90;
    static final int SPHERICAL_CONE_LIMIT_ANGLE = 91;
    static final int SPHERICAL_CONE_ANGLE = 92;
    static final int SPHERICAL_TWIST_LIMIT = 93;
    static final int SPHERICAL_TWIST_LIMITS = 94;
    static final int SPHERICAL_TWIST_ANGLE = 95;
    static final int SPHERICAL_SPRING = 96;
    static final int SPHERICAL_HERTZ = 97;
    static final int SPHERICAL_DAMPING = 98;
    static final int SPHERICAL_TARGET_ROTATION = 99;
    static final int SPHERICAL_MOTOR = 100;
    static final int SPHERICAL_MOTOR_VELOCITY = 101;
    static final int SPHERICAL_MOTOR_TORQUE = 102;
    static final int SPHERICAL_MAX_MOTOR_TORQUE = 103;

    static final int WELD_LINEAR_HERTZ = 110;
    static final int WELD_LINEAR_DAMPING = 111;
    static final int WELD_ANGULAR_HERTZ = 112;
    static final int WELD_ANGULAR_DAMPING = 113;

    static final int WHEEL_SUSPENSION = 120;
    static final int WHEEL_SUSPENSION_HERTZ = 121;
    static final int WHEEL_SUSPENSION_DAMPING = 122;
    static final int WHEEL_SUSPENSION_LIMIT = 123;
    static final int WHEEL_SUSPENSION_LIMITS = 124;
    static final int WHEEL_SPIN_MOTOR = 125;
    static final int WHEEL_SPIN_MOTOR_SPEED = 126;
    static final int WHEEL_MAX_SPIN_TORQUE = 127;
    static final int WHEEL_SPIN_SPEED = 128;
    static final int WHEEL_SPIN_TORQUE = 129;
    static final int WHEEL_STEERING = 130;
    static final int WHEEL_STEERING_HERTZ = 131;
    static final int WHEEL_STEERING_DAMPING = 132;
    static final int WHEEL_MAX_STEERING_TORQUE = 133;
    static final int WHEEL_STEERING_LIMIT = 134;
    static final int WHEEL_STEERING_LIMITS = 135;
    static final int WHEEL_TARGET_STEERING_ANGLE = 136;
    static final int WHEEL_STEERING_ANGLE = 137;
    static final int WHEEL_STEERING_TORQUE = 138;
    static final int MOVER_LINEAR_VELOCITY = 140;
    static final int MOVER_MAX_FORCE = 141;
    static final int POGO_REST_LENGTH = 150;
    static final int POGO_HERTZ = 151;
    static final int POGO_DAMPING = 152;
    static final int POGO_MAX_TENSION_FORCE = 153;
    static final int POGO_MAX_COMPRESSION_FORCE = 154;
    static final int POGO_NORMAL = 155;
    static final int POGO_CURRENT_LENGTH = 156;
    static final int POGO_CURRENT_SPEED = 157;
    static final int POGO_IMPULSE = 158;

    static final int MOTION_LOCK_LINEAR_X = 160;
    static final int MOTION_LOCK_LINEAR_Y = 161;
    static final int MOTION_LOCK_LINEAR_Z = 162;
    static final int MOTION_LOCK_ANGULAR = 163;
}
