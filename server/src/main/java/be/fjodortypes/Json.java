package be.fjodortypes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader/writer for the website, so the game needs no extra library.
 * Reading gives {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Long},
 * {@code Double}, {@code Boolean} or {@code null}. Writing accepts the same, plus records of those
 * are not supported: build maps with {@link #obj(Object...)}.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- writing

    /** {@code obj("a", 1, "b", "x")} -> an ordered map; null values are left out. */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                m.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return m;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            sb.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(',');
                }
                write(sb, o);
                first = false;
            }
            sb.append(']');
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // Also escape < > & so JSON can never be read as HTML.
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- reading

    /** Throws IllegalArgumentException on anything that isn't valid JSON. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("Unexpected text after JSON at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 32;
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nested too deep");
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw new IllegalArgumentException("Expected a key at " + i);
                }
                String key = string();
                ws();
                expect(':');
                ws();
                m.put(key, value(depth + 1));
                ws();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or } at " + (i - 1));
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                ws();
                list.add(value(depth + 1));
                ws();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or ] at " + (i - 1));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("Bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("Control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (n.isEmpty() || n.equals("-")) {
                throw new IllegalArgumentException("Unexpected character at " + start);
            }
            try {
                if (n.matches("-?\\d{1,18}")) {
                    return Long.parseLong(n);
                }
                return Double.parseDouble(n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number " + n);
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("Unexpected character at " + i);
            }
            i += word.length();
            return value;
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private char next() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("Expected " + c + " at " + (i - 1));
            }
        }
    }
}
