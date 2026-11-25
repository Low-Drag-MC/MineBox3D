package com.lowdragmc.minebox3d.physics;

/**
 * <b>Box3D refused a call</b>: one of its assertions failed while the call ran — or the bridge could not do what was
 * asked (native memory ran out, no free world slot).
 *
 * <p>Box3D's own response to a failed assertion is a debugger break, which in a game is the process
 * exiting with no stack trace. The bridge catches the assertion instead, lets Box3D finish the call,
 * and throws this on the way back to Java, carrying Box3D's condition and source line.
 *
 * <p>The Java API validates what it can before crossing, so a failed assertion means either a check is
 * missing on this side or the world was already in a bad state; it is not a recoverable error.
 */
public final class Box3DException extends RuntimeException {
    public Box3DException(String message) {
        super(message);
    }
}
