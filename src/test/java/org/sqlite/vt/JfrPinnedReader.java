// --------------------------------------
// sqlite-jdbc Project
//
// JfrPinnedReader.java
// --------------------------------------
package org.sqlite.vt;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Counts jdk.VirtualThreadPinned events in a JFR recording. Runs as a standalone JVM because the
 * Maven test JVM does not resolve the jdk.jfr module; reflection keeps this class Java-8 compatible
 * at compile time.
 *
 * <p>stdout: PINNED=&lt;count&gt; on success, READER-ERROR on failure; exit code 0 or 1.
 */
public class JfrPinnedReader {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("READER-ERROR usage: JfrPinnedReader <recording.jfr>");
            System.exit(1);
            return;
        }
        Path recording = Paths.get(args[0]);
        try {
            Class<?> recordingFileClass = Class.forName("jdk.jfr.consumer.RecordingFile");
            Object recordingFile =
                    recordingFileClass.getConstructor(Path.class).newInstance(recording);
            int count = 0;
            while ((Boolean) recordingFileClass.getMethod("hasMoreEvents").invoke(recordingFile)) {
                Object event = recordingFileClass.getMethod("readEvent").invoke(recordingFile);
                Object eventType =
                        Class.forName("jdk.jfr.consumer.RecordedEvent")
                                .getMethod("getEventType")
                                .invoke(event);
                if ("jdk.VirtualThreadPinned"
                        .equals(
                                Class.forName("jdk.jfr.EventType")
                                        .getMethod("getName")
                                        .invoke(eventType))) {
                    count++;
                }
            }
            recordingFileClass.getMethod("close").invoke(recordingFile);
            System.out.println("PINNED=" + count);
            System.exit(0);
        } catch (Throwable error) {
            System.out.println("READER-ERROR " + error);
            System.exit(1);
        }
    }

    private JfrPinnedReader() {}
}
