package com.lowdragmc.minebox3d.physics;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/** A few values keyed by their class, for bodies and shapes: small, so a linear search beats a map. */
final class Attachments {

    private static final Object[] EMPTY = new Object[0];

    /** key, value, key, value … */
    private Object[] entries = EMPTY;

    @Nullable
    <T> T get(Class<T> type) {
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i] == type) {
                return type.cast(entries[i + 1]);
            }
        }
        return null;
    }

    <T> void put(Class<T> type, @Nullable T value) {
        if (value != null && !type.isInstance(value)) {
            throw new ClassCastException(value + " is not a " + type.getName());
        }
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i] == type) {
                if (value != null) {
                    entries[i + 1] = value;
                } else {
                    Object[] smaller = new Object[entries.length - 2];
                    System.arraycopy(entries, 0, smaller, 0, i);
                    System.arraycopy(entries, i + 2, smaller, i, entries.length - i - 2);
                    entries = smaller;
                }
                return;
            }
        }
        if (value != null) {
            entries = Arrays.copyOf(entries, entries.length + 2);
            entries[entries.length - 2] = type;
            entries[entries.length - 1] = value;
        }
    }
}
