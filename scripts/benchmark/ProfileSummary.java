package io.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.*;

/** Offline only: java io.gateway.ProfileSummary input.jfr [input.jfr ...] output.json. */
public final class ProfileSummary {
    static final Set<String> ALLOCATIONS = Set.of("jdk.ObjectAllocationSample", "profiler.AllocationSample",
        "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB");

    static String field(RecordedObject event, String... names) {
        for (String name : names) {
            for (ValueDescriptor descriptor : event.getFields()) {
                if (descriptor.getName().equals(name)) return name;
            }
        }
        return null;
    }

    static String className(RecordedEvent event, String... names) {
        String name = field(event, names);
        if (name == null) return "[missing class field]";
        Object value = event.getValue(name);
        return value instanceof RecordedClass klass ? klass.getName() : String.valueOf(value);
    }

    static List<String> frames(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null || trace.getFrames().isEmpty()) return List.of("[no stack]");
        var frames = new ArrayList<String>();
        for (RecordedFrame frame : trace.getFrames()) {
            RecordedMethod method = frame.getMethod();
            if (method == null) { frames.add("[unknown frame]"); continue; }
            String owner = method.getType() == null ? "" : method.getType().getName();
            frames.add((owner.isEmpty() ? "" : owner + ".") + method.getName() + " [" + frame.getType() + "]");
        }
        if (trace.isTruncated()) frames.add("[truncated]");
        return frames;
    }

    static void add(Map<String, Long> counts, String key, long value) {
        counts.merge(key, value, Long::sum);
    }

    static void metric(Map<String, Map<String, Long>> buckets, String key, String metric, long value) {
        add(buckets.computeIfAbsent(key, ignored -> new TreeMap<>()), metric, value);
    }

    static Map<String, Long> sorted(Map<String, Long> counts) {
        var result = new LinkedHashMap<String, Long>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey())).forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    static Map<String, Object> summarize(Path path) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("file", path.toString());
        var errors = new TreeSet<String>();
        var seen = new TreeMap<String, Long>();
        var schemas = new TreeMap<String, Object>();
        var settings = new TreeMap<String, String>();
        var eventNames = new HashMap<Long, String>();
        boolean async = false;
        // The producer, not the event name alone, establishes whether ExecutionSample is CPU.
        try (var recording = new RecordingFile(path)) {
            for (var type : recording.readEventTypes()) {
                eventNames.put(type.getId(), type.getName());
                if ("Async-profiler Recording".equalsIgnoreCase(type.getLabel())) async = true;
            }
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                String type = event.getEventType().getName();
                add(seen, type, 1);
                schemas.computeIfAbsent(type, ignored -> event.getFields().stream()
                    .map(f -> Map.of("name", f.getName(), "type", f.getTypeName())).toList());
                if (type.equals("jdk.ActiveRecording") && field(event, "name") != null
                        && event.getString("name") != null && event.getString("name").startsWith("async-profiler")) async = true;
                if (type.equals("jdk.ActiveSetting") && field(event, "id") != null
                        && field(event, "name") != null && field(event, "value") != null) {
                    String owner = eventNames.getOrDefault(event.getLong("id"), Long.toString(event.getLong("id")));
                    settings.put(owner + "/" + event.getString("name"), event.getString("value"));
                }
            }
        }
        String selectedEvent = settings.get("jdk.ActiveRecording/event");
        String selectedEngine = settings.get("jdk.ActiveRecording/engine");
        boolean cpu = async && "cpu".equals(selectedEvent) && "perf_events".equals(selectedEngine);
        if (async && !"cpu".equals(selectedEvent))
            errors.add("Expected async-profiler event=cpu, found " + selectedEvent + "; samples are not classified as CPU");
        if (async && !"perf_events".equals(selectedEngine))
            errors.add("Expected Linux perf_events CPU backend, found " + selectedEngine
                + "; event=cpu alone does not prove CPU sampling, and samples are not classified as CPU");
        if (async && "true".equals(settings.get("jdk.ExecutionSample/nobatch"))
                && settings.containsKey("jdk.ExecutionSample/wall")) {
            cpu = false;
            errors.add("Unbatched wall samples share ExecutionSample; refusing to classify mixed samples as CPU");
        }
        result.put("producer", async ? "async-profiler" : "jdk-jfr");
        result.put("settings", settings);
        result.put("seen_event_types", seen);
        result.put("event_fields", schemas);
        var leaves = new HashMap<String, Long>();
        var leafLocations = new HashMap<String, Long>();
        var inclusive = new HashMap<String, Long>();
        var stacks = new HashMap<String, Long>();
        var allocationClasses = new TreeMap<String, Map<String, Long>>();
        var allocationStacks = new TreeMap<String, Map<String, Long>>();
        var allocationTypes = new TreeMap<String, Map<String, Long>>();
        var monitorClasses = new TreeMap<String, Map<String, Long>>();
        var monitorStacks = new TreeMap<String, Map<String, Long>>();
        var parkClasses = new TreeMap<String, Map<String, Long>>();
        var parkStacks = new TreeMap<String, Map<String, Long>>();
        var inflationClasses = new TreeMap<String, Map<String, Long>>();
        var inflationStacks = new TreeMap<String, Map<String, Long>>();
        var pauses = new TreeMap<String, Map<String, Long>>();
        long cpuSamples = 0, allocationSamples = 0, cpuMissingStacks = 0;
        try (var recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                String type = event.getEventType().getName();
                if (cpu && (type.equals("jdk.ExecutionSample") || type.equals("profiler.ExecutionSample"))) {
                    List<String> frames = frames(event);
                    cpuSamples++;
                    if (event.getStackTrace() == null || event.getStackTrace().getFrames().isEmpty()) cpuMissingStacks++;
                    add(leaves, frames.getFirst(), 1);
                    if (event.getStackTrace() != null && !event.getStackTrace().getFrames().isEmpty()) {
                        var leaf = event.getStackTrace().getFrames().getFirst();
                        add(leafLocations, frames.getFirst() + " line=" + leaf.getLineNumber()
                                + " bci=" + leaf.getBytecodeIndex(), 1);
                    }
                    // Inclusive samples count each frame once, even when recursion repeats it.
                    for (String frame : new HashSet<>(frames)) add(inclusive, frame, 1);
                    add(stacks, String.join(" <- ", frames), 1);
                } else if (ALLOCATIONS.contains(type)) {
                    allocationSamples++;
                    String weightField = field(event, "weight", "tlabSize", "allocationSize", "size");
                    String sizeField = field(event, "allocationSize", "size");
                    String classField = field(event, "objectClass", "allocationClass", "class");
                    if (weightField == null || classField == null) {
                        errors.add("Unsupported allocation schema for " + type + ": expected class and byte weight/size fields");
                        continue;
                    }
                    Object weight = event.getValue(weightField);
                    if (!(weight instanceof Number)) {
                        errors.add("Non-numeric allocation weight in " + type + "/" + weightField);
                        continue;
                    }
                    String klass = className(event, classField);
                    String stack = klass + " :: " + String.join(" <- ", frames(event));
                    for (var bucket : List.of(allocationClasses, allocationStacks, allocationTypes)) {
                        String key = bucket == allocationClasses ? klass : bucket == allocationStacks ? stack : type;
                        metric(bucket, key, "samples", 1);
                        metric(bucket, key, "weight_bytes", ((Number) weight).longValue());
                        // Preserve field identity: sampled weight is not an exact allocated-byte census.
                        metric(bucket, key, "weight_from_" + weightField + "_bytes", ((Number) weight).longValue());
                        if (sizeField != null && event.getValue(sizeField) instanceof Number size)
                            metric(bucket, key, "recorded_" + sizeField + "_bytes", size.longValue());
                    }
                } else if (type.equals("jdk.JavaMonitorInflate")) {
                    String klass = className(event, "monitorClass");
                    String cause = event.getString("cause");
                    String key = klass + " :: " + cause;
                    metric(inflationClasses, key, "events", 1);
                    metric(inflationStacks, key + " :: " + String.join(" <- ", frames(event)), "events", 1);
                } else if (type.equals("jdk.JavaMonitorEnter") || type.equals("jdk.ThreadPark")) {
                    if (field(event, "duration") == null) {
                        errors.add("Missing wait duration in " + type); continue;
                    }
                    boolean monitor = type.equals("jdk.JavaMonitorEnter");
                    String klass = className(event, monitor ? "monitorClass" : "parkedClass");
                    String stack = klass + " :: " + String.join(" <- ", frames(event));
                    var classes = monitor ? monitorClasses : parkClasses;
                    var stackTotals = monitor ? monitorStacks : parkStacks;
                    metric(classes, klass, "events", 1);
                    metric(classes, klass, "duration_ns", event.getDuration().toNanos());
                    metric(stackTotals, stack, "events", 1);
                    metric(stackTotals, stack, "duration_ns", event.getDuration().toNanos());
                } else if (type.equals("jdk.GarbageCollection") || type.equals("jdk.GCPhasePause")
                        || type.startsWith("jdk.Safepoint")) {
                    metric(pauses, type, "events", 1);
                    metric(pauses, type, "duration_ns", event.getDuration().toNanos());
                    if (field(event, "sumOfPauses") != null)
                        metric(pauses, type, "sum_of_pauses_ns", event.getDuration("sumOfPauses").toNanos());
                }
            }
        }
        if (async && cpuSamples == 0) errors.add("No CPU profile samples; inspect event types, settings, and perf permissions");
        if (async && cpuSamples > 0 && cpuMissingStacks == cpuSamples) errors.add("CPU samples contain no usable stacks");
        if (async && allocationSamples == 0) errors.add("No allocation profile records; expected alloc=512k for this cost profile");
        var cpuResult = new LinkedHashMap<String, Object>();
        cpuResult.put("available", cpu && cpuSamples > 0);
        cpuResult.put("samples", cpuSamples);
        cpuResult.put("samples_without_stack", cpuMissingStacks);
        cpuResult.put("leaf_frames", sorted(leaves));
        cpuResult.put("leaf_locations", sorted(leafLocations));
        cpuResult.put("inclusive_frames", sorted(inclusive));
        cpuResult.put("stacks_leaf_first", sorted(stacks));
        cpuResult.put("units", "CPU samples, not nanoseconds; normalize against this run's successful requests separately");
        result.put("cpu", cpuResult);
        result.put("non_cpu_samples", Map.of("jdk_native_method_samples", seen.getOrDefault("jdk.NativeMethodSample", 0L),
            "jdk_execution_samples", cpu ? 0L : seen.getOrDefault("jdk.ExecutionSample", 0L),
            "wall_clock_records", seen.getOrDefault("profiler.WallClockSample", 0L)));
        result.put("allocations", Map.of("samples", allocationSamples, "by_class", allocationClasses,
            "by_class_and_stack", allocationStacks, "by_event_type", allocationTypes,
            "semantics", "Byte sampling estimates: weight/tlabSize is sample weight; allocationSize is the recorded object size. AP 4.5 sampled allocations use ObjectAllocationInNewTLAB, with tlabSize=max(object size, interval), not an actual TLAB size."));
        result.put("monitor_waits", Map.of("by_class", monitorClasses, "by_class_and_stack", monitorStacks));
        result.put("thread_parks", Map.of("by_class", parkClasses, "by_class_and_stack", parkStacks));
        result.put("monitor_inflations", Map.of("by_class_and_cause", inflationClasses,
            "by_class_cause_and_stack", inflationStacks));
        result.put("gc_and_safepoints", pauses);
        result.put("duration_semantics", "Wait/park durations are not CPU time; sampled AP waits are not a full census. GC/safepoint event types can overlap: do not sum types or recordings together.");
        result.put("errors", errors);
        return result;
    }

    // ponytail: JDK-only JSON keeps this CLI on the benchmark's existing classpath.
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var join = new StringJoiner(",\n", "{\n", "\n}");
            map.forEach((key, item) -> join.add(json(key.toString()) + ":" + json(item)));
            return join.toString();
        }
        if (value instanceof Collection<?> collection) {
            var join = new StringJoiner(",\n", "[\n", "\n]");
            collection.forEach(item -> join.add(json(item)));
            return join.toString();
        }
        var text = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> { if (c < 32) text.append(String.format("\\u%04x", (int)c)); else text.append(c); }
            }
        }
        return text.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: ProfileSummary input.jfr [input.jfr ...] output.json");
        var summaries = new ArrayList<Map<String, Object>>();
        boolean failed = false;
        for (int i = 0; i < args.length - 1; i++) {
            var summary = summarize(Path.of(args[i]));
            summaries.add(summary);
            failed |= !((Collection<?>) summary.get("errors")).isEmpty();
        }
        Object output = summaries.size() == 1 ? summaries.getFirst() : Map.of("recordings", summaries);
        Files.writeString(Path.of(args[args.length - 1]), json(output) + "\n");
        if (failed) throw new IllegalStateException("Invalid profile data; see errors and seen_event_types in " + args[args.length - 1]);
    }
}
