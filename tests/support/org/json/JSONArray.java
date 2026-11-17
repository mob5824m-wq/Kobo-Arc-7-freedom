package org.json;
import java.util.*;

public class JSONArray {
    final List<Object> list = new ArrayList<Object>();
    public JSONArray() {}
    public JSONArray(List<?> src) { for (Object o : src) list.add(JSONObject.wrap(o)); }
    public JSONArray(String s) { list.addAll(MiniJson.parseArr(new int[]{0}, s.trim())); }
    public int length() { return list.size(); }
    public JSONArray put(Object v) { list.add(v); return this; }
    public Object get(int i) { return list.get(i); }
    public String getString(int i) { return String.valueOf(list.get(i)); }
    public String optString(int i) { return optString(i, ""); }
    public String optString(int i, String d) { return (i >= 0 && i < list.size() && list.get(i) != null) ? String.valueOf(list.get(i)) : d; }
    public double optDouble(int i) { return optDouble(i, 0.0); }
    public double optDouble(int i, double d) { Object v = (i >= 0 && i < list.size()) ? list.get(i) : null; return v instanceof Number ? ((Number) v).doubleValue() : d; }
    public int optInt(int i) { Object v = list.get(i); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public JSONObject getJSONObject(int i) { return (JSONObject) list.get(i); }
    public JSONObject optJSONObject(int i) { return (JSONObject) list.get(i); }
    public JSONArray getJSONArray(int i) { return (JSONArray) list.get(i); }
    @Override public String toString() { return MiniJson.stringify(list); }
}
