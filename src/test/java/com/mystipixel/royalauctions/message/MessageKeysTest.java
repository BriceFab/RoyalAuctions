package com.mystipixel.royalauctions.message;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The code reads its text from messages.yml only: every key it reads ships in the jar's copy, and no
// colour-coded text is left in the Java sources.
class MessageKeysTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "\\bmessages(?:\\(\\))?\\s*\\.\\s*(send|sendLines|sendStyled|text|component|lines)\\s*\\(");
    // GuiManager.t and the service's tell helper
    private static final Pattern GUI_CALL = Pattern.compile("(?<![\\w.])t\\s*\\(|\\bmanager\\.t\\s*\\(");
    private static final Pattern TELL_CALL = Pattern.compile("\\btell\\s*\\(");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern REJECTED = Pattern.compile("new Rejected\\(\"([a-z-]+)\"");
    private static final Pattern TYPE_FILTER = Pattern.compile("\\b[A-Z]+\\(\"(filter\\.[a-z-]+)\"\\)");
    private static final Pattern COLOUR = Pattern.compile("&[0-9a-fk-or]");

    // reads whose key is built at run time: refusal reasons and type filters are checked below,
    // tell, t and the dialog's label pass on a key their callers give them
    private static final Set<String> DYNAMIC = Set.of(
            "AuctionService.java: \"exchange.reasons.\" + r.key",
            "AuctionService.java: key",
            "TextInput.java: key",
            "BrowseGui.java: f.key",
            "BrowseGui.java: typeFilter.key",
            "GuiManager.java: \"gui.\" + key");

    private record Read(String where, String expression, List<String> keys) {
    }

    @Test
    void everyKeyReadInCodeShipsInMessagesYml() throws IOException {
        YamlConfiguration shipped = yaml("/messages.yml");
        List<Read> reads = scanReads();
        assertTrue(reads.size() > 150, "found only " + reads.size() + " message reads; is the scan still matching?");

        List<String> problems = new ArrayList<>();
        Set<String> dynamic = new TreeSet<>();
        for (Read read : reads) {
            if (read.keys().isEmpty() || read.keys().stream().anyMatch(k -> k.endsWith("."))) {
                dynamic.add(read.where().replaceAll(":\\d+$", "") + ": " + read.expression());
                continue;
            }
            for (String key : read.keys()) {
                if (!shipped.contains(key)) {
                    problems.add(read.where() + ": " + key + " is not in messages.yml");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        dynamic.removeAll(DYNAMIC);
        assertTrue(dynamic.isEmpty(), "message keys built at run time: " + dynamic);
    }

    @Test
    void runtimeKeysShipInMessagesYml() throws IOException {
        YamlConfiguration shipped = yaml("/messages.yml");
        Set<String> rejected = matches(REJECTED, "com/mystipixel/royalauctions/data/AuctionTransactions.java");
        assertTrue(rejected.size() >= 15, "refusal reasons found: " + rejected);
        for (String key : rejected) {
            assertTrue(shipped.isString("exchange.reasons." + key), "exchange.reasons." + key);
        }
        Set<String> filters = matches(TYPE_FILTER, "com/mystipixel/royalauctions/gui/BrowseGui.java");
        assertEquals(3, filters.size(), "type filters: " + filters);
        for (String key : filters) {
            assertTrue(shipped.isString("gui." + key), "gui." + key);
        }
    }

    // the only code fallbacks allowed: the dialog's button labels, equal to the shipped text
    private static final Pattern LABEL_FALLBACK = Pattern.compile("label\\(\"([a-z.-]+)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\)");

    @Test
    void noColourCodedTextLeftInJava() throws IOException {
        YamlConfiguration shipped = yaml("/messages.yml");
        List<String> found = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = withoutComments(Files.readString(file));
            Set<Integer> fallbacks = new HashSet<>();
            Matcher label = LABEL_FALLBACK.matcher(source);
            while (label.find()) {
                assertEquals(shipped.getString(label.group(1)), label.group(2), label.group(1) + " fallback");
                fallbacks.add(label.start(2) - 1);
            }
            Matcher m = STRING.matcher(source);
            while (m.find()) {
                String literal = m.group(1);
                // the colour-stripping regex itself
                if (COLOUR.matcher(literal).find() && !literal.startsWith("&[") && !fallbacks.contains(m.start())) {
                    found.add(file.getFileName() + ":" + lineOf(source, m.start()) + " \"" + literal + "\"");
                }
            }
        }
        assertTrue(found.isEmpty(), "colour-coded text in code:\n" + String.join("\n", found));
    }

    @Test
    void bundledYamlHasNoDuplicateKeys() throws IOException {
        List<String> files = new ArrayList<>(List.of("/messages.yml", "/config.yml"));
        try (Stream<Path> menus = Files.list(Path.of("src", "main", "resources", "gui"))) {
            menus.map(p -> "/gui/" + p.getFileName()).sorted().forEach(files::add);
        }
        List<String> duplicates = new ArrayList<>();
        for (String file : files) {
            duplicates.addAll(duplicateKeys(file, resource(file)));
        }
        assertTrue(duplicates.isEmpty(), "duplicate keys: " + duplicates);
    }

    @Test
    void duplicateKeyCheckFindsADuplicate() {
        assertEquals(List.of("x.yml: a.b"), duplicateKeys("x.yml", "a:\n  b: 1\n  c: 2\n  b: 3\n"));
        assertEquals(List.of(), duplicateKeys("x.yml", "slots:\n  - id: a\n    row: 1\n  - id: b\n    row: 2\n"));
    }

    // YAML keeps only the last of two equal keys, silently dropping the first
    private static List<String> duplicateKeys(String file, String text) {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int element = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                pop(path, indents, indent);
                path.add("[" + element++ + "]");
                indents.add(indent);
                trimmed = trimmed.substring(1).strip();
                indent += 2;
                if (trimmed.isEmpty() || trimmed.startsWith("\"")) {
                    continue;
                }
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0 || trimmed.startsWith("\"")) {
                continue;
            }
            pop(path, indents, indent);
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(file + ": " + key);
            }
        }
        return duplicates;
    }

    private static void pop(List<String> path, List<Integer> indents, int indent) {
        while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
            indents.remove(indents.size() - 1);
            path.remove(path.size() - 1);
        }
    }

    private static List<Read> scanReads() throws IOException {
        List<Read> reads = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (file.endsWith("MessageManager.java")) {
                continue;
            }
            String source = withoutComments(Files.readString(file));
            scan(reads, file, source, MESSAGE_CALL, "", call -> call.equals("send") || call.equals("sendLines")
                    || call.equals("sendStyled") ? 1 : 0);
            scan(reads, file, source, GUI_CALL, "gui.", call -> 0);
            if (file.endsWith("AuctionService.java")) {
                scan(reads, file, source, TELL_CALL, "", call -> 1);
            }
        }
        return reads;
    }

    private interface KeyIndex {
        int of(String call);
    }

    private static void scan(List<Read> reads, Path file, String source, Pattern call, String prefix, KeyIndex index) {
        Matcher m = call.matcher(source);
        while (m.find()) {
            String before = source.substring(Math.max(0, m.start() - 16), m.start());
            if (before.matches("(?s).*\\b(String|void)\\s*$")) {
                continue;                        // the method's own declaration
            }
            List<String> args = arguments(source, m.end());
            int keyIndex = index.of(m.groupCount() > 0 ? m.group(1) : "");
            if (args.size() <= keyIndex) {
                continue;
            }
            String expression = args.get(keyIndex);
            List<String> keys = new ArrayList<>();
            Matcher literal = STRING.matcher(expression);
            while (literal.find()) {
                keys.add(prefix + literal.group(1));
            }
            reads.add(new Read(file.getFileName() + ":" + lineOf(source, m.start()), expression, keys));
        }
    }

    // top-level arguments of the call whose '(' ends just before start
    private static List<String> arguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                current.append(source, i, end + 1);
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    if (!current.toString().isBlank()) {
                        args.add(current.toString().strip());
                    }
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        throw new IllegalStateException("unclosed call");
    }

    private static Set<String> matches(Pattern pattern, String file) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = pattern.matcher(Files.readString(SOURCES.resolve(file)));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // blanks comments but keeps line numbers
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != '"' && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                out.append(source, i, Math.min(end + 1, source.length()));
                i = end + 1;
            } else if (c == '\'' && i + 2 < source.length()) {
                int end = source.indexOf('\'', i + 1 + (source.charAt(i + 1) == '\\' ? 2 : 1));
                out.append(source, i, end + 1);
                i = end + 1;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i) + 2;
                source.substring(i, end).chars().filter(ch -> ch == '\n').forEach(ch -> out.append('\n'));
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = MessageKeysTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration yaml(String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource(name));
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
