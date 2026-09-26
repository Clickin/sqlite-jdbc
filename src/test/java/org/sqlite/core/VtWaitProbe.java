// --------------------------------------
// sqlite-jdbc Project
//
// VtWaitProbe.java
// --------------------------------------
package org.sqlite.core;

/** Test bridge for the non-blocking Java-wait observation counter of {@link NativeDB}. */
public class VtWaitProbe {
    public static long javaWaitObservations() {
        return NativeDB.javaWaitObservations.get();
    }

    private VtWaitProbe() {}
}
