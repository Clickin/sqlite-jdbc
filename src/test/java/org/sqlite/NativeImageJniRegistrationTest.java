// --------------------------------------
// sqlite-jdbc Project
//
// NativeImageJniRegistrationTest.java
// --------------------------------------
package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

/**
 * Keeps the GraalVM native-image JNI registrations in {@code SqliteJdbcFeature} in sync with the
 * members NativeDB.c resolves in JNI_OnLoad. A member the feature does not register makes
 * JNI_OnLoad fail inside a native image, which only the (slow) native-image CI jobs would notice.
 */
@DisabledInNativeImage // reads the source tree
public class NativeImageJniRegistrationTest {

    private static final String NATIVE_DB_C = "src/main/java/org/sqlite/core/NativeDB.c";
    private static final String FEATURE =
            "src/main/java9/org/sqlite/nativeimage/SqliteJdbcFeature.java";

    private static final Pattern FIND_CLASS =
            Pattern.compile(
                    "(\\w+)\\s*=\\s*\\(\\*env\\)->FindClass\\(\\s*env\\s*,\\s*\"([^\"]+)\"");
    private static final Pattern GET_ID =
            Pattern.compile(
                    "Get(Static)?(Method|Field)ID\\(\\s*env\\s*,\\s*(\\w+)\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"");
    private static final Pattern FEATURE_METHOD =
            Pattern.compile("method\\(\\s*([\\w.]+)\\.class\\s*,\\s*\"(\\w+)\"");
    private static final Pattern FEATURE_FIELDS =
            Pattern.compile("fields\\(\\s*([\\w.]+)\\.class\\s*,([^)]*)\\)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\w+)\"");

    @Test
    void featureRegistersEveryMemberResolvedByJniOnLoad() throws Exception {
        String c = read(NATIVE_DB_C);
        Map<String, String> classByVariable = new HashMap<>();
        Matcher findClass = FIND_CLASS.matcher(c);
        while (findClass.find()) {
            classByVariable.put(findClass.group(1), findClass.group(2));
        }

        Set<String> registered = registeredMembers(read(FEATURE));
        List<String> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Matcher getId = GET_ID.matcher(c);
        while (getId.find()) {
            String jniClass = classByVariable.get(getId.group(3));
            assertThat(jniClass).as("FindClass for %s", getId.group(3)).isNotNull();
            if (!jniClass.startsWith("org/sqlite/")) {
                continue; // JDK classes are always reachable
            }
            Class<?> owner = Class.forName(jniClass.replace('/', '.'));
            boolean isMethod = "Method".equals(getId.group(2));
            String name = getId.group(4);
            String descriptor = getId.group(5);
            Class<?> declaring =
                    isMethod
                            ? declaringClassOfMethod(owner, name, descriptor)
                            : declaringClassOfField(owner, name, descriptor);
            assertThat(declaring)
                    .as("%s.%s%s resolved by NativeDB.c", jniClass, name, descriptor)
                    .isNotNull();
            String key = featureName(declaring) + "#" + name;
            resolved.add(key);
            if (!registered.contains(key)) {
                missing.add(key);
            }
        }

        assertThat(resolved).as("JNI lookups parsed from NativeDB.c").hasSizeGreaterThan(10);
        assertThat(missing).as("members missing from SqliteJdbcFeature.registerJNICalls").isEmpty();
    }

    private static Set<String> registeredMembers(String feature) {
        Set<String> registered = new HashSet<>();
        Matcher method = FEATURE_METHOD.matcher(feature);
        while (method.find()) {
            registered.add(method.group(1) + "#" + method.group(2));
        }
        Matcher fields = FEATURE_FIELDS.matcher(feature);
        while (fields.find()) {
            Matcher literal = STRING_LITERAL.matcher(fields.group(2));
            while (literal.find()) {
                registered.add(fields.group(1) + "#" + literal.group(1));
            }
        }
        return registered;
    }

    private static Class<?> declaringClassOfMethod(Class<?> owner, String name, String descriptor) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && descriptor(m).equals(descriptor)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static Class<?> declaringClassOfField(Class<?> owner, String name, String descriptor) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && descriptor(f.getType()).equals(descriptor)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** The class name as SqliteJdbcFeature spells it: simple, with nested classes dotted. */
    private static String featureName(Class<?> c) {
        String name = c.getName();
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(descriptor(p));
        }
        return sb.append(')').append(descriptor(m.getReturnType())).toString();
    }

    private static String descriptor(Class<?> c) {
        if (c.isArray()) return c.getName().replace('.', '/');
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
