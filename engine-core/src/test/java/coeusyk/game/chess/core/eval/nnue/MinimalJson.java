package coeusyk.game.chess.core.eval.nnue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, purpose-built JSON reader for the two small, fixed-shape fixture files
 * under {@code docs/architecture/feature-spec/} ({@code v1.json},
 * {@code parity-corpus-v1.json}) -- deliberately not a general JSON library
 * ({@code docs/architecture/NNUE_TRAINER_ARCHITECTURE.md} Section 4.1 "No new Java
 * dependency": {@code engine-core} has zero JSON libraries in any scope today, and
 * adding one for two small test fixtures would be a disproportionate dependency).
 * Supports objects, arrays, strings, integers, booleans, and null -- enough for these
 * files, nothing more.
 */
final class MinimalJson {

    private final String text;
    private int pos;

    private MinimalJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MinimalJson parser = new MinimalJson(text);
        Object value = parser.parseValue();
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        char c = text.charAt(pos);
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return parseString();
        }
        if (c == 't' || c == 'f') {
            return parseBoolean();
        }
        if (c == 'n') {
            pos += 4;
            return null;
        }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++; // consume '{'
        skipWhitespace();
        if (text.charAt(pos) == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            pos++; // consume ':'
            Object value = parseValue();
            result.put(key, value);
            skipWhitespace();
            char c = text.charAt(pos);
            pos++; // consume ',' or '}'
            if (c == '}') {
                break;
            }
        }
        return result;
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        pos++; // consume '['
        skipWhitespace();
        if (text.charAt(pos) == ']') {
            pos++;
            return result;
        }
        while (true) {
            result.add(parseValue());
            skipWhitespace();
            char c = text.charAt(pos);
            pos++; // consume ',' or ']'
            if (c == ']') {
                break;
            }
        }
        return result;
    }

    private String parseString() {
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (text.charAt(pos) != '"') {
            char c = text.charAt(pos);
            if (c == '\\') {
                pos++;
                char escaped = text.charAt(pos);
                switch (escaped) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    default:
                        sb.append(escaped);
                }
            } else {
                sb.append(c);
            }
            pos++;
        }
        pos++; // consume closing quote
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        pos += 5;
        return Boolean.FALSE;
    }

    private Long parseNumber() {
        int start = pos;
        while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '-')) {
            pos++;
        }
        return Long.parseLong(text.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
