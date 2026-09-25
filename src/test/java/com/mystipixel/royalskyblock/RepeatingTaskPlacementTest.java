package com.mystipixel.royalskyblock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every repeating task starts in {@code RoyalSkyblockPlugin.createTasks()}.
 *
 * <p>{@code /is reload} goes through {@code EcoPlugin.reload()}, which cancels every task the plugin
 * owns — including ones scheduled straight through Bukkit — and then calls {@code createTasks()} again.
 * A timer started anywhere else dies on the first reload and never comes back, and nothing reports
 * it: islands simply stop unloading, upgrades stop finishing, perks stop ticking.
 *
 * <p>The exceptions below are deliberate and each explains why it survives a reload.
 */
class RepeatingTaskPlacementTest {

    private static final Pattern REPEATING = Pattern.compile(
            "\\b(runTaskTimer(Asynchronously)?|schedule(Sync|Async)RepeatingTask|runAtFixedRate|runTimer|runAsyncTimer)\\s*\\(");

    private static final String PLUGIN = "RoyalSkyblockPlugin.kt";

    /** Files allowed to start a timer outside createTasks, and why a reload does not break them. */
    private static final Map<String, String> EXEMPT = Map.of(
            "island/IslandMobSpawnService.java",
            "start() is what createTasks() calls to bring the spawn timer back after a reload",
            "level/LevelService.java",
            "a one-off per level scan that cancels itself; the scan's timeout releases it if a reload kills it");

    @Test
    @DisplayName("repeating tasks are started from createTasks()")
    void repeatingTasksStartInCreateTasks() throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String lang : new String[]{"java", "kotlin"}) {
            Path root = Path.of("src/main", lang, "com/mystipixel/royalskyblock");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    String source = Files.readString(file);
                    Matcher m = REPEATING.matcher(source);
                    if (!m.find()) {
                        continue;
                    }
                    seen.add(name);
                    if (EXEMPT.containsKey(name)) {
                        continue;
                    }
                    if (!name.equals(PLUGIN)) {
                        problems.add(name + " line " + line(source, m.start()) + ": " + m.group(1));
                        continue;
                    }
                    int[] body = functionBody(source, "fun createTasks(");
                    do {
                        if (m.start() < body[0] || m.start() > body[1]) {
                            problems.add(name + " line " + line(source, m.start()) + ": " + m.group(1)
                                    + " outside createTasks()");
                        }
                    } while (m.find());
                }
            }
        }
        assertTrue(seen.contains(PLUGIN), "found no timers in " + PLUGIN + " — is the test reading the sources?");
        for (String exempt : EXEMPT.keySet()) {
            assertTrue(seen.contains(exempt), exempt + " no longer starts a timer; drop its exemption");
        }
        assertTrue(problems.isEmpty(), "repeating tasks started outside createTasks(), so /is reload would "
                + "cancel them for good. Start them from createTasks():\n" + String.join("\n", problems));
    }

    /** Start and end offsets of the brace block following {@code signature}. */
    private static int[] functionBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "could not find " + signature + " in " + PLUGIN);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return new int[]{open, i};
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    private static int line(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
