package org.sqlite.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measurement-only bridge: one start per JVM; stop drains admitted native scopes. */
public final class CostProbe {
    private CostProbe() {}

    private static native void start0(int mode);
    private static native String[] names0();
    private static native long[] stop0();

    public static synchronized void start(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Probe mode must be 0, 1 or 2");
        start0(mode);
    }

    public static synchronized Map<String, Object> stop() {
        long[] values = stop0();
        String[] names = names0();
        if (values.length != 5 + names.length * 5) {
            throw new IllegalStateException("Native cost probe schema mismatch");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", values[0]);
        result.put("thread_shards", values[1]);
        result.put("sampling_period_calls", 256);
        result.put("sampling_policy", "one per 256 calls per category per OS thread; staggered phases");
        result.put("wall_clock", "CLOCK_MONOTONIC");
        result.put("cpu_clock", "CLOCK_THREAD_CPUTIME_ID");
        result.put("timing_semantics", "Inclusive sampled sums, not extrapolated totals. Nested categories overlap; never sum them as disjoint CPU. Clock/probe cost is included, not subtracted.");
        result.put("snapshot_boundary", "Called after benchmark worker drain. Disables admission, waits up to 5 seconds for admitted native scopes (including background SQL), then snapshots atomic counters. Late entries are excluded; admitted scopes include completion after stop. Timeout fails the measurement.");
        result.put("lifecycle", "one start per JVM; no live counter reset");
        result.put("counter_synchronization", "single-writer relaxed atomic load/store counters; per-shard outermost active publication and mode recheck; no per-call global atomic RMW");
        Map<String, Object> calibration = new LinkedHashMap<>();
        calibration.put("iterations", values[2]);
        calibration.put("wall_ns", values[3]);
        calibration.put("thread_cpu_ns", values[4]);
        calibration.put("wall_ns_per_empty_timer", (double) values[3] / values[2]);
        calibration.put("thread_cpu_ns_per_empty_timer", (double) values[4] / values[2]);
        calibration.put("semantics", "Empty wall-start/CPU-start/CPU-end/wall-end clock sequence on coordinator before measurement; excludes counter/TLS/cleanup dispatch cost and is not a correction.");
        result.put("empty_timer_calibration", calibration);
        Map<String, Object> categories = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            int offset = 5 + i * 5;
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("available", values[offset] != 0);
            category.put("calls", values[offset + 1]);
            category.put("samples", values[offset + 2]);
            category.put("inclusive_wall_ns", values[offset + 3]);
            category.put("inclusive_thread_cpu_ns", values[offset + 4]);
            categories.put(names[i], category);
        }
        result.put("categories", categories);
        return result;
    }
}
