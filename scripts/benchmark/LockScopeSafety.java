package io.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** One JVM per public-API scenario, including deliberate lock-cycle probes. */
public final class LockScopeSafety {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--list")) {
            System.out.println(ProfileSummary.json(Map.of("lifecycle", ScopeLifecycleCases.names(),
                    "transactions", ScopeTransactionCases.names(), "extension", ScopeExtensionCases.names())));
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("usage: LockScopeSafety group scenario output-dir");
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        Path data = output.resolve("data");
        Files.createDirectory(data);
        var result = new LinkedHashMap<String, Object>();
        result.put("group", args[0]); result.put("scenario", args[1]);
        result.put("observation_valid", false);
        try {
            Class.forName("org.sqlite.JDBC");
            result.put("driver_location", org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation().toString());
            result.put("java", System.getProperty("java.runtime.version"));
            Map<String, Object> observation = switch (args[0]) {
                case "lifecycle" -> ScopeLifecycleCases.run(args[1], data);
                case "transactions" -> ScopeTransactionCases.run(args[1], data);
                case "extension" -> ScopeExtensionCases.run(args[1], data);
                default -> throw new IllegalArgumentException("unknown scenario group " + args[0]);
            };
            result.put("observation", observation);
            result.put("observation_valid", true);
            Files.writeString(output.resolve("result.json"), ProfileSummary.json(result) + "\n");
            System.out.println("OBSERVED " + args[0] + "/" + args[1]);
            // A demonstrated cycle intentionally leaves daemon actors blocked; this JVM owns no user data.
            System.exit(0);
        } catch (Throwable failure) {
            result.put("error_class", failure.getClass().getName());
            result.put("error", failure.toString());
            Files.writeString(output.resolve("result.json"), ProfileSummary.json(result) + "\n");
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
