import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared UCI/SMP diagnostic parsing and corrected helper-exit accounting. */
final class Phase20UciEvents {
    @FunctionalInterface
    interface LineReader {
        String nextLine() throws Exception;
    }

    private Phase20UciEvents() {}

    static Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>();
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals > 0 && equals < part.length() - 1) {
                fields.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        for (int i = 0; i + 1 < parts.length; i++) {
            String key = parts[i];
            if (List.of("depth", "seldepth", "multipv", "nodes", "nps", "time", "hashfull").contains(key)) {
                fields.put(key, parts[i + 1]);
            } else if (key.equals("score") && i + 2 < parts.length) {
                fields.put(key, parts[i + 1] + " " + parts[i + 2]);
            } else if (key.equals("pv")) {
                fields.put(key, String.join(" ", java.util.Arrays.copyOfRange(parts, i + 1, parts.length)));
                break;
            }
        }
        return fields;
    }

    static Map<String, String> event(List<String> lines, long searchId, String name) {
        for (String line : lines) {
            Map<String, String> event = parseEvent(line, searchId, name);
            if (event != null) return event;
        }
        return null;
    }

    static Map<String, String> parseEvent(String line, long searchId, String name) {
        if (!line.startsWith("SMPDIAG ")) return null;
        Map<String, String> fields = fields(line);
        if (!name.equals(fields.get("event"))) return null;
        if (searchId >= 0 && number(fields, "search") != searchId) return null;
        return fields;
    }

    static Map<String, String> awaitEvent(List<String> lines, long searchId, String name,
                                          LineReader reader) throws Exception {
        Map<String, String> found = event(lines, searchId, name);
        while (found == null) {
            String line = reader.nextLine();
            lines.add(line);
            failOnMainException(line);
            if (line.startsWith("bestmove ")) {
                throw new IllegalStateException("Duplicate bestmove for search " + searchId);
            }
            found = parseEvent(line, searchId, name);
        }
        return found;
    }

    static List<Map<String, String>> collectHelperExits(List<String> lines, long searchId,
                                                         int expectedHelpers, LineReader reader) throws Exception {
        Map<Integer, Map<String, String>> exits = new LinkedHashMap<>();
        for (String line : lines) {
            Map<String, String> diag = parseEvent(line, searchId, "exit");
            if (diag != null) addHelperExit(exits, diag, searchId);
        }
        while (exits.size() < expectedHelpers) {
            String line = reader.nextLine();
            lines.add(line);
            failOnMainException(line);
            if (line.startsWith("bestmove ")) {
                throw new IllegalStateException("Duplicate bestmove for search " + searchId);
            }
            Map<String, String> diag = parseEvent(line, searchId, "exit");
            if (diag != null) addHelperExit(exits, diag, searchId);
        }
        return new ArrayList<>(exits.values());
    }

    static void validateObservedHelperExits(List<String> lines, long searchId, int expectedHelpers) {
        Map<Integer, Map<String, String>> exits = new LinkedHashMap<>();
        for (String line : lines) {
            Map<String, String> diag = parseEvent(line, searchId, "exit");
            if (diag != null) addHelperExit(exits, diag, searchId);
        }
        require(exits.size() == expectedHelpers,
                "Helper exit transcript count mismatch search=" + searchId + " expected="
                        + expectedHelpers + " got=" + exits.size());
    }

    static int countEvents(List<String> lines, long searchId, String name) {
        return (int) lines.stream().filter(line -> parseEvent(line, searchId, name) != null).count();
    }

    static void failOnMainException(String line) {
        require(!line.contains("Exception in thread \"uci-search-thread\""),
                "Uncaught main-search exception: " + line);
        require(!line.contains("SMP helper exception"), "Logged SMP helper exception: " + line);
        require(!line.contains("SMP helper execution failure"), "Uncaught helper failure: " + line);
    }

    static long number(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) throw new IllegalStateException("Missing " + key + " in " + fields);
        return Long.parseLong(value);
    }

    private static void addHelperExit(Map<Integer, Map<String, String>> exits,
                                      Map<String, String> diag, long searchId) {
        int helper = Math.toIntExact(number(diag, "helper"));
        require(exits.putIfAbsent(helper, diag) == null,
                "Duplicate helper exit search=" + searchId + " helper=" + helper);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
