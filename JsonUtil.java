import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal JSON helpers (no external libraries): escape strings and parse a flat {"k":"v"} object. */
public final class JsonUtil {

    private JsonUtil() {
    }

    /** Returns s as a quoted, escaped JSON string. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Parses a flat JSON object whose values are all strings. Throws IllegalArgumentException if invalid. */
    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int[] pos = {0};
        skipWs(json, pos);
        expect(json, pos, '{');
        skipWs(json, pos);
        if (peek(json, pos) == '}') {
            pos[0]++;
        } else {
            while (true) {
                skipWs(json, pos);
                String key = readString(json, pos);
                skipWs(json, pos);
                expect(json, pos, ':');
                skipWs(json, pos);
                String value = readString(json, pos);
                result.put(key, value);
                skipWs(json, pos);
                char c = peek(json, pos);
                pos[0]++;
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' in JSON");
            }
        }
        skipWs(json, pos);
        if (pos[0] != json.length()) throw new IllegalArgumentException("Unexpected data after JSON object");
        return result;
    }

    private static char peek(String s, int[] pos) {
        if (pos[0] >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        return s.charAt(pos[0]);
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) pos[0]++;
    }

    private static void expect(String s, int[] pos, char expected) {
        if (peek(s, pos) != expected) throw new IllegalArgumentException("Expected '" + expected + "' in JSON");
        pos[0]++;
    }

    private static String readString(String s, int[] pos) {
        if (peek(s, pos) != '"') throw new IllegalArgumentException("JSON values must be strings");
        pos[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek(s, pos);
            pos[0]++;
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek(s, pos);
            pos[0]++;
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'u':
                    if (pos[0] + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Bad unicode escape");
                    }
                    pos[0] += 4;
                    break;
                default:
                    throw new IllegalArgumentException("Bad escape in JSON string");
            }
        }
    }
}
