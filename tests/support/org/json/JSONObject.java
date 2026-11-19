package org.json;
import java.util.*;

public class JSONObject {
    final Map<String, Object> m = new LinkedHashMap<String, Object>();
    public JSONObject() {}
    public JSONObject(Map<?,?> src) {
        for (Map.Entry<?,?> e : src.entrySet()) m.put(String.valueOf(e.getKey()), wrap(e.getValue()));
    }
    public JSONObject(String s) {
        Map<String, Object> parsed = MiniJson.parseObj(new int[]{0}, s.trim());
        m.putAll(parsed);
    }
    static Object wrap(Object v) {
        if (v instanceof Map) return new JSONObject((Map<?,?>) v);
        if (v instanceof List) return new JSONArray((List<?>) v);
        return v;
    }
    public JSONObject put(String k, Object v) { m.put(k, v); return this; }
    // Keep Android's primitive overload descriptors for already-compiled classes.
    public JSONObject put(String k, boolean v) { return put(k, Boolean.valueOf(v)); }
    public JSONObject put(String k, int v) { return put(k, Integer.valueOf(v)); }
    public JSONObject put(String k, long v) { return put(k, Long.valueOf(v)); }
    public JSONObject put(String k, double v) { return put(k, Double.valueOf(v)); }
    public boolean has(String k) { return m.containsKey(k); }
    public Object opt(String k) { return m.get(k); }
    public Object get(String k) { if(!m.containsKey(k)) throw new IllegalArgumentException("Missing JSON key: "+k); return m.get(k); }
    public String getString(String k) { Object v=get(k); if(!(v instanceof String)) throw new IllegalArgumentException("Not a JSON string"); return (String)v; }
    public JSONObject getJSONObject(String k) { return (JSONObject)get(k); }
    public JSONArray getJSONArray(String k) { return (JSONArray)get(k); }
    public JSONArray names() { return m.isEmpty() ? null : new JSONArray(new ArrayList<String>(m.keySet())); }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object v = m.get(k); return v == null ? d : String.valueOf(v); }
    public int optInt(String k, int d) { Object v=m.get(k); if(v instanceof Number) return ((Number)v).intValue(); try{return (int)Double.parseDouble((String)v);}catch(Exception ignored){return d;} }
    public long optLong(String k, long d) { Object v=m.get(k); if(v instanceof Number) return ((Number)v).longValue(); try{return (long)Double.parseDouble((String)v);}catch(Exception ignored){return d;} }
    public double optDouble(String k, double d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).doubleValue() : d; }
    public boolean optBoolean(String k) { return optBoolean(k, false); }
    public boolean optBoolean(String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? ((Boolean) v).booleanValue() : d; }
    public JSONObject optJSONObject(String k) { Object v = m.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = m.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public Iterator<String> keys() { return m.keySet().iterator(); }
    @Override public String toString() { return MiniJson.stringify(m); }
}
