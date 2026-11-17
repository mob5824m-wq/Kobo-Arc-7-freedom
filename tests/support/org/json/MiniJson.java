package org.json;
import java.util.*;

class MiniJson {
    // Test-only JSON serialization for recording REAL production request payloads.
    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject) return stringify(((JSONObject) value).m);
        if (value instanceof JSONArray) return stringify(((JSONArray) value).list);
        if (value instanceof String) {
            String text = (String) value;
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        StringBuilder out = new StringBuilder();
        if (value instanceof Map) {
            out.append('{');
            for (Object key : ((Map<?,?>) value).keySet()) {
                if (out.length() > 1) out.append(',');
                out.append(stringify(String.valueOf(key))).append(':').append(stringify(((Map<?,?>) value).get(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            out.append('[');
            for (Object item : (List<?>) value) {
                if (out.length() > 1) out.append(',');
                out.append(stringify(item));
            }
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    static void ws(int[] p, String s) { while (p[0] < s.length() && s.charAt(p[0]) <= ' ') p[0]++; }
    static Object val(int[] p, String s) {
        ws(p, s);
        char c = s.charAt(p[0]);
        if (c == '{') return new JSONObject(parseObj(p, s));
        if (c == '[') return new JSONArray(parseArr(p, s));
        if (c == '"') return str(p, s);
        int st = p[0];
        while (p[0] < s.length() && ",]}".indexOf(s.charAt(p[0])) < 0) p[0]++;
        String tok = s.substring(st, p[0]).trim();
        if ("true".equals(tok) || "false".equals(tok)) return Boolean.valueOf(tok);
        if ("null".equals(tok)) return null;
        return tok.contains(".") ? (Object) Double.valueOf(tok) : (Object) Long.valueOf(tok);
    }
    static String str(int[] p, String s) {
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]++);
            if (c == '"') break;
            if (c == '\\' && p[0] < s.length()) c = s.charAt(p[0]++);
            sb.append(c);
        }
        return sb.toString();
    }
    static Map<String, Object> parseObj(int[] p, String s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        ws(p, s); p[0]++; ws(p, s);
        if (s.charAt(p[0]) == '}') { p[0]++; return m; }
        while (p[0] < s.length()) {
            ws(p, s); String k = str(p, s); ws(p, s); p[0]++;
            m.put(k, val(p, s)); ws(p, s);
            if (s.charAt(p[0]) == '}') { p[0]++; break; }
            p[0]++;
        }
        return m;
    }
    static List<Object> parseArr(int[] p, String s) {
        List<Object> a = new ArrayList<Object>();
        ws(p, s); p[0]++; ws(p, s);
        if (s.charAt(p[0]) == ']') { p[0]++; return a; }
        while (p[0] < s.length()) {
            a.add(val(p, s)); ws(p, s);
            if (s.charAt(p[0]) == ']') { p[0]++; break; }
            p[0]++;
        }
        return a;
    }
}
