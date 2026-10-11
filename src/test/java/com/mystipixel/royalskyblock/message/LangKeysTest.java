package com.mystipixel.royalskyblock.message;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every lang.yml key the code reads ships in the bundled lang.yml, as a list where the code reads a list.
// eco adds missing keys from the jar's copy to a server's lang.yml, so the bundled text is what an older
// server file ends up showing too.
class LangKeysTest {

    private static final List<Path> SOURCES = List.of(Path.of("src", "main", "java"), Path.of("src", "main", "kotlin"));

    // a MessageManager read, IslandCommand's say helper, or MessageManager calling itself; the arguments
    // are scanned for key literals
    private static final Pattern CALL = Pattern.compile(
            "(?:messages\\(\\)\\s*\\.\\s*(send|sendPlain|sendLines|raw|lines)|\\b(say))\\s*\\(");
    private static final Pattern SELF_CALL = Pattern.compile("(?<![.\\w])(send|sendPlain|sendLines|raw|lines)\\s*\\(");
    private static final Pattern REASON = Pattern.compile("new Reason\\(\\s*\"([a-z0-9.-]+)\"");
    private static final Pattern BANK_RESULT = Pattern.compile("\\bresult\\(\\s*\"([a-z0-9.-]+)\"");
    private static final Pattern BANK_NOTE = Pattern.compile("\\bnote\\(\\s*\"([a-z0-9-]+)\"");
    private static final Pattern STATUS_DEP = Pattern.compile("\\bdep\\(sender,\\s*\"([a-z0-9-]+)\"");
    private static final Pattern STATUS_CHECK = Pattern.compile("\\bcheck\\(sender,[^\"]*\"([a-z0-9-]+)\",\\s*\"([a-z0-9-]+)\"");
    private static final Pattern LITERAL = Pattern.compile("\"([a-z0-9-]+(?:\\.[a-z0-9-]+)+)\"");

    static String read(String resource) throws IOException {
        try (InputStream in = LangKeysTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(in, resource + " is not bundled");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static YamlConfiguration lang() throws IOException, InvalidConfigurationException {
        YamlConfiguration lang = new YamlConfiguration();
        lang.loadFromString(read("lang.yml"));
        return lang;
    }

    private record Read(String where, String key, boolean list) {
    }

    private static List<Read> scan() throws IOException {
        List<Read> reads = new ArrayList<>();
        for (Path root : SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt")).toList()) {
                    String source = Files.readString(file);
                    String name = file.getFileName().toString();
                    scanCalls(source, CALL.matcher(source), name, reads);
                    if (name.equals("MessageManager.java")) {
                        scanCalls(source, SELF_CALL.matcher(source), name, reads);
                    }
                    add(reads, name, REASON.matcher(source), "", false);
                    if (name.equals("BankService.java")) {
                        add(reads, name, BANK_RESULT.matcher(source), "bank.result.", false);
                        add(reads, name, BANK_NOTE.matcher(source), "bank.notes.", false);
                    }
                    Matcher dep = STATUS_DEP.matcher(source);
                    while (dep.find()) {
                        for (String suffix : List.of("", "-ready", "-missing")) {
                            reads.add(new Read(name, "admin.status." + dep.group(1) + suffix, false));
                        }
                    }
                    Matcher check = STATUS_CHECK.matcher(source);
                    while (check.find()) {
                        reads.add(new Read(name, "admin.status." + check.group(1), false));
                        reads.add(new Read(name, "admin.status." + check.group(2), false));
                    }
                }
            }
        }
        return reads;
    }

    // the dotted string literals in each call's argument list (nested calls included)
    private static void scanCalls(String source, Matcher call, String name, List<Read> reads) {
        while (call.find()) {
            int depth = 1;
            int i = call.end();
            boolean inString = false;
            for (; i < source.length() && depth > 0; i++) {
                char c = source.charAt(i);
                if (c == '\\' && inString) {
                    i++;
                } else if (c == '"') {
                    inString = !inString;
                } else if (!inString && c == '(') {
                    depth++;
                } else if (!inString && c == ')') {
                    depth--;
                }
            }
            String method = call.group(1) != null ? call.group(1) : "say";
            boolean list = method.equals("sendLines") || method.equals("lines");
            Matcher literal = LITERAL.matcher(source.substring(call.end(), i));
            while (literal.find()) {
                reads.add(new Read(name, literal.group(1), list));
            }
        }
    }

    private static void add(List<Read> reads, String where, Matcher matcher, String prefix, boolean list) {
        while (matcher.find()) {
            reads.add(new Read(where, prefix + matcher.group(1), list));
        }
    }

    @Test
    void everyKeyTheCodeReadsIsBundled() throws Exception {
        YamlConfiguration lang = lang();
        List<Read> reads = scan();
        assertTrue(reads.size() > 250, "found only " + reads.size() + " reads; is the scan still matching?");
        List<String> problems = new ArrayList<>();
        for (Read read : reads) {
            String path = "messages." + read.key();
            if (!lang.isSet(path)) {
                problems.add(read.where() + ": " + read.key() + " is not in lang.yml");
            } else if (read.list() != lang.isList(path)) {
                problems.add(read.where() + ": " + read.key() + (read.list() ? " should be a list" : " should be a string"));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void thePrefixFallbackMatchesTheBundledPrefix() throws Exception {
        String source = Files.readString(SOURCES.get(0).resolve("com/mystipixel/royalskyblock/message/MessageManager.java"));
        Matcher fallback = Pattern.compile("prefix != null \\? prefix : \"([^\"]*)\"").matcher(source);
        assertTrue(fallback.find(), "prefix fallback not found");
        assertEquals(lang().getString("messages.prefix"), fallback.group(1));
    }

    @Test
    void langHasNoDuplicateKeys() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        assertDoesNotThrow(() -> new Yaml(options).load(read("lang.yml")));
    }
}
