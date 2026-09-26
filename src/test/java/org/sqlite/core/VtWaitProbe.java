// --------------------------------------
// sqlite-jdbc Project
//
// VtWaitProbe.java
// --------------------------------------
package org.sqlite.core;

/** Test bridge for the non-blocking Java-wait observation counter of {@link DB}. */
public class VtWaitProbe {
    public static long javaWaitObservations() {
        return DB.javaWaitObservations.get();
    }

    private VtWaitProbe() {}
}
