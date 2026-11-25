package com.lowdragmc.minebox3d.physics;

import java.lang.ref.Cleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The one cleaner thread that frees native objects dropped without being closed, and where it says so. */
final class Cleaners {

    static final Cleaner CLEANER = Cleaner.create();
    static final Logger LOGGER = LoggerFactory.getLogger("MineBox3D");

    private Cleaners() {}
}
