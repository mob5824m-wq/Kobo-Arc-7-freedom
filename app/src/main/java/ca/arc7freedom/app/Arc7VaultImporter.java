package ca.arc7freedom.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline reader for Proton Pass / Bitwarden CSV and JSON exports.
 * It deliberately does not decrypt provider-encrypted exports or make network requests.
 */
public final class Arc7VaultImporter {
    public static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RECORDS = 5000;
    private static final String[] TITLE_KEYS = {"name", "title", "itemname", "accountname", "label", "displayname"};
    private static final String[] USER_KEYS = {"username", "loginusername", "user", "email", "loginemail"};
    private static final String[] PASSWORD_KEYS = {"password", "loginpassword", "passphrase"};
    private static final String[] URL_KEYS = {"url", "urls", "uri", "uris", "website", "loginuri", "loginurl", "websites"};
    private static final String[] NOTES_KEYS = {"notes", "note", "description"};
    private static final String[] TOTP_KEYS = {"totp", "logintotp", "otp", "otpsecret", "totpsecret", "totpuri", "otpauth",
            "twofactorsecret", "twofactoruri", "2fasecret", "2fa", "authenticatorsecret", "authenticatorkey", "onetimepassword"};
    private static final String[] ISSUER_KEYS = {"issuer", "service", "provider"};

    private Arc7VaultImporter() {}

    public static final class Batch {
        public final JSONArray passwords = new JSONArray();
        public final JSONArray authenticators = new JSONArray();
        public String source = "Export file";
        public int invalidTotp;
        private final Set<String> passwordKeys = new HashSet<String>();
        private final Set<String> totpKeys = new HashSet<String>();
        private int generatedTitle;
    }

    public static final class Merge {
        public final JSONArray entries;
        public final int added;
        public final int duplicates;
        Merge(JSONArray entries, int added, int duplicates) {
            this.entries = entries; this.added = added; this.duplicates = duplicates;
        }
    }

    /** Reads a bounded export, closing the supplied stream on success or failure. */
    public static byte[] read(InputStream input) throws IOException {
        if (input == null) throw new IOException("No export file was selected");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        try {
            int count;
            while ((count = input.read(block)) != -1) {
                if (output.size() + count > MAX_IMPORT_BYTES)
                    throw new IOException("Export exceeds the 4 MiB import limit");
                output.write(block, 0, count);
            }
            return output.toByteArray();
        } finally {
            wipe(block);
            try { input.close(); } catch (Exception ignored) {}
        }
    }

    /** Parses common unencrypted provider CSV/JSON exports entirely on-device. */
    public static Batch parse(byte[] source) throws Exception {
        if (source == null || source.length == 0 || source.length > MAX_IMPORT_BYTES)
            throw new IOException("Empty export or export exceeds the 4 MiB import limit");
        String text = new String(source, "UTF-8");
        if (text.length() > 0 && text.charAt(0) == '\ufeff') text = text.substring(1);
        String trimmed = text.trim();
        if (trimmed.length() == 0) throw new IOException("The export file is empty");
        Batch batch = new Batch();
        if (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[') {
            Object root;
            try { root = trimmed.charAt(0) == '{' ? (Object) new JSONObject(trimmed) : (Object) new JSONArray(trimmed); }
            catch (Exception malformed) { throw new IOException("The JSON export is malformed"); }
            if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                if (isEncryptedExport(object.opt("encrypted")))
                    throw new IOException("This is an encrypted provider export. Export unencrypted JSON or CSV in Proton Pass or Bitwarden, then import that file here.");
                String hint = normalize(firstDirect(object, "source", "format", "provider", "app"));
                if (hint.contains("proton") || hasKey(object, "vaults")) batch.source = "Proton Pass JSON export";
                else if (hint.contains("bitwarden") || hasKey(object, "folders") || hasKey(object, "encrypted")) batch.source = "Bitwarden JSON export";
                else batch.source = "JSON export";
            } else batch.source = "JSON export";
            collectJson(root, batch, 0);
        } else {
            List<List<String>> rows = parseCsv(text);
            if (rows.size() < 2) throw new IOException("The CSV export has no data rows");
            List<String> header = rows.get(0);
            if (header.isEmpty()) throw new IOException("The CSV export is missing column names");
            boolean bitwarden = false;
            for (String name : header) {
                String key = normalize(name);
                if (key.startsWith("login") || key.equals("reprompt") || key.equals("favorite")) bitwarden = true;
            }
            batch.source = bitwarden ? "Bitwarden-compatible CSV export" : "CSV export";
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (emptyRow(row)) continue;
                String type = csvValue(header, row, "type", "itemtype", "kind");
                String title = csvValue(header, row, TITLE_KEYS);
                String username = csvValue(header, row, USER_KEYS);
                String password = csvValue(header, row, PASSWORD_KEYS);
                String url = csvValue(header, row, URL_KEYS);
                String notes = csvValue(header, row, NOTES_KEYS);
                String totp = csvValue(header, row, TOTP_KEYS);
                String issuer = csvValue(header, row, ISSUER_KEYS);
                addRecord(batch, title, username, password, url, notes, issuer, totp, type);
                if (batch.passwords.length() + batch.authenticators.length() > MAX_RECORDS)
                    throw new IOException("Export contains too many entries");
            }
        }
        return batch;
    }

    /** Append new entries without replacing existing data; duplicates are omitted. */
    public static Merge merge(JSONArray existing, JSONArray imported, String purpose) throws Exception {
        if (!"passwords".equals(purpose) && !"authenticator".equals(purpose))
            throw new IOException("Unsupported vault type");
        JSONArray result = new JSONArray();
        Set<String> keys = new HashSet<String>();
        int added = 0, duplicates = 0;
        if (existing != null) {
            for (int i = 0; i < existing.length(); i++) {
                JSONObject item = existing.optJSONObject(i);
                if (item == null) continue;
                result.put(item);
                keys.add(entryKey(item, purpose));
            }
        }
        if (imported != null) {
            for (int i = 0; i < imported.length(); i++) {
                JSONObject item = imported.optJSONObject(i);
                if (item == null) continue;
                String key = entryKey(item, purpose);
                if (!keys.add(key)) { duplicates++; continue; }
                result.put(item); added++;
            }
        }
        if (result.length() > 200) throw new IOException("A vault can contain at most 200 entries. Nothing was imported; create a separate vault or remove entries first.");
        return new Merge(result, added, duplicates);
    }

    private static String entryKey(JSONObject item, String purpose) {
        if ("authenticator".equals(purpose))
            return lower(item.optString("secret").replace(" ", "").replace("-", ""));
        return lower(item.optString("title")) + "\u0000" + lower(item.optString("username"))
                + "\u0000" + lower(item.optString("url"));
    }

    private static void collectJson(Object node, Batch batch, int depth) throws Exception {
        if (node == null || depth > 24) return;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectJson(array.opt(i), batch, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;
        if (looksLikeItem(object)) {
            addRecord(batch,
                    findString(object, TITLE_KEYS, 0),
                    findString(object, USER_KEYS, 0),
                    findString(object, PASSWORD_KEYS, 0),
                    findString(object, URL_KEYS, 0),
                    findString(object, NOTES_KEYS, 0),
                    findString(object, ISSUER_KEYS, 0),
                    findTotp(object),
                    directType(object));
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object child = object.opt(keys.next());
            if (child instanceof JSONObject || child instanceof JSONArray)
                collectJson(child, batch, depth + 1);
        }
        if (batch.passwords.length() + batch.authenticators.length() > MAX_RECORDS)
            throw new IOException("Export contains too many entries");
    }

    private static boolean looksLikeItem(JSONObject object) {
        String type = directType(object);
        boolean loginType = isLoginType(type), totpType = isTotpType(type);
        String title = findString(object, TITLE_KEYS, 0);
        String username = findString(object, USER_KEYS, 0);
        String password = findString(object, PASSWORD_KEYS, 0);
        String url = findString(object, URL_KEYS, 0);
        String totp = findTotp(object);
        boolean loginData = username.length() > 0 || password.length() > 0 || url.length() > 0;
        return (loginType && (loginData || totp.length() > 0))
                || (totpType && (totp.length() > 0 || findString(object, new String[]{"secret", "key"}, 0).length() > 0))
                || (title.length() > 0 && (loginData || totp.length() > 0));
    }

    private static String directType(JSONObject object) {
        return directString(object, "type", "itemtype", "kind", "entrytype");
    }

    private static String findTotp(JSONObject object) {
        String value = findString(object, TOTP_KEYS, 0);
        if (value.length() == 0 && isTotpType(directType(object)))
            value = findString(object, new String[]{"secret", "key", "seed"}, 0);
        return value;
    }

    private static void addRecord(Batch batch, String title, String username, String password,
                                  String url, String notes, String issuer, String totp, String type) throws Exception {
        title = clean(title); username = clean(username); password = clean(password);
        url = clean(url); notes = clean(notes); issuer = clean(issuer); totp = clean(totp);
        if (!supportedType(type)) return;
        boolean loginData = username.length() > 0 || password.length() > 0 || url.length() > 0;
        boolean loginType = isLoginType(type);
        if (title.length() == 0) {
            if (username.length() > 0) title = username;
            else if (url.length() > 0) title = url;
            else if (issuer.length() > 0) title = issuer;
            else title = "Imported entry " + (++batch.generatedTitle);
        }
        if (title.length() > 200) throw new IOException("An entry title exceeds 200 characters");
        checkLength(username, 8192, "username"); checkLength(password, 8192, "password");
        checkLength(url, 8192, "website"); checkLength(notes, 20000, "notes");
        if (totp.length() > 4096) throw new IOException("A 2FA value is too long");

        Arc7Totp.Account totpAccount = null;
        if (totp.length() > 0) {
            try { totpAccount = Arc7Totp.parse(totp); }
            catch (Exception invalid) { batch.invalidTotp++; }
        }
        if (loginData && (loginType || type.length() == 0 || isLoginType(type))) {
            JSONObject item = new JSONObject();
            item.put("title", title).put("username", username).put("password", password)
                    .put("url", url).put("notes", notes);
            if (totpAccount != null) {
                item.put("totp", totpAccount.secret).put("totp_algorithm", totpAccount.algorithm)
                        .put("totp_digits", totpAccount.digits).put("totp_period", totpAccount.period);
            }
            String key = entryKey(item, "passwords");
            if (batch.passwordKeys.add(key)) batch.passwords.put(item);
        }
        if (totpAccount != null) {
            JSONObject item = new JSONObject();
            String label = totpAccount.label.length() > 0 ? totpAccount.label : title;
            item.put("title", label).put("issuer", totpAccount.issuer.length() > 0 ? totpAccount.issuer : issuer)
                    .put("secret", totpAccount.secret).put("algorithm", totpAccount.algorithm)
                    .put("digits", totpAccount.digits).put("period", totpAccount.period);
            String key = entryKey(item, "authenticator");
            if (batch.totpKeys.add(key)) batch.authenticators.put(item);
        }
    }

    private static boolean supportedType(String type) {
        String t = normalize(type);
        return t.length() == 0 || isLoginType(t) || isTotpType(t);
    }

    private static boolean isLoginType(String type) {
        String t = normalize(type);
        return "1".equals(t) || t.equals("login") || t.equals("password") || t.equals("credential")
                || t.equals("website") || t.equals("account") || t.equals("itemlogin");
    }

    private static boolean isTotpType(String type) {
        String t = normalize(type);
        return t.equals("totp") || t.equals("otp") || t.equals("twofactor") || t.equals("2fa")
                || t.equals("authenticator") || t.equals("authenticatoraccount");
    }

    private static List<List<String>> parseCsv(String text) throws IOException {
        char delimiter = detectDelimiter(text);
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(c);
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString()); cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (!emptyRow(row)) rows.add(row);
                row = new ArrayList<String>();
            } else cell.append(c);
        }
        if (quoted) throw new IOException("The CSV export has an unterminated quoted field");
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            if (!emptyRow(row)) rows.add(row);
        }
        if (rows.isEmpty()) throw new IOException("The CSV export is empty");
        return rows;
    }

    private static char detectDelimiter(String text) {
        boolean quoted = false;
        int comma = 0, semicolon = 0, tab = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (c == ',') comma++;
                else if (c == ';') semicolon++;
                else if (c == '\t') tab++;
                else if (c == '\r' || c == '\n') break;
            }
        }
        return tab > comma && tab > semicolon ? '\t' : semicolon > comma ? ';' : ',';
    }

    private static String csvValue(List<String> headers, List<String> row, String... aliases) {
        Set<String> wanted = new HashSet<String>();
        for (String alias : aliases) wanted.add(normalize(alias));
        for (int i = 0; i < headers.size(); i++) {
            if (wanted.contains(normalize(headers.get(i)))) return i < row.size() ? row.get(i).trim() : "";
        }
        // Handles Bitwarden's login_totp/login_username columns and Proton aliases whose labels
        // include punctuation or spaces; compare normalized spellings only, never substring-match.
        for (int i = 0; i < headers.size(); i++) {
            String key = normalize(headers.get(i));
            if (wanted.contains(key)) return i < row.size() ? row.get(i).trim() : "";
        }
        return "";
    }

    private static String findString(JSONObject object, String[] aliases, int depth) {
        if (object == null || depth > 8) return "";
        String direct = directString(object, aliases);
        if (direct.length() > 0) return direct;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object element = array.opt(i);
                    if (element instanceof JSONObject) {
                        JSONObject entry = (JSONObject) element;
                        String customName = directString(entry, "name", "key", "label", "fieldname");
                        if (matches(customName, aliases)) {
                            String customValue = directString(entry, "value", "text", "secret", "data");
                            if (customValue.length() > 0) return customValue;
                        }
                    }
                }
            }
        }
        // Provider exports wrap login properties in login/data/content/item/fields objects.
        String[] containers = {"login", "data", "content", "item", "fields", "customfields",
                "extrafields", "details", "properties", "secret", "urls", "uris", "websites"};
        for (String container : containers) {
            Object value = getIgnoreCase(object, normalize(container));
            if (value instanceof JSONObject) {
                String found = findString((JSONObject) value, aliases, depth + 1);
                if (found.length() > 0) return found;
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object element = array.opt(i);
                    if (element instanceof JSONObject) {
                        String found = findString((JSONObject) element, aliases, depth + 1);
                        if (found.length() > 0) return found;
                    } else if (element instanceof String && matches(container, aliases)) {
                        return (String) element;
                    }
                }
            }
        }
        return "";
    }

    private static String directString(JSONObject object, String... aliases) {
        if (object == null) return "";
        Set<String> wanted = new HashSet<String>();
        for (String alias : aliases) wanted.add(normalize(alias));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (wanted.contains(normalize(key))) {
                String value = stringValue(object.opt(key), 0);
                if (value.length() > 0) return value;
            }
        }
        return "";
    }

    private static String stringValue(Object value, int depth) {
        if (value == null || depth > 5) return "";
        if (value instanceof String || value instanceof Number) return String.valueOf(value).trim();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String result = stringValue(array.opt(i), depth + 1);
                if (result.length() > 0) return result;
            }
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String result = directString(object, "value", "text", "uri", "url", "secret", "key", "username", "password");
            if (result.length() > 0) return result;
        }
        return "";
    }

    private static Object getIgnoreCase(JSONObject object, String normalizedKey) {
        if (object == null) return null;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (normalize(key).equals(normalizedKey)) return object.opt(key);
        }
        return null;
    }

    private static String firstDirect(JSONObject object, String... keys) {
        return directString(object, keys);
    }

    private static boolean hasKey(JSONObject object, String key) { return getIgnoreCase(object, normalize(key)) != null; }
    private static boolean isEncryptedExport(Object value) {
        if (value == null) return false;
        String text = String.valueOf(value).trim();
        return !"false".equalsIgnoreCase(text) && !"0".equals(text) && text.length() > 0;
    }
    private static boolean matches(String value, String[] aliases) {
        String key = normalize(value);
        for (String alias : aliases) if (key.equals(normalize(alias))) return true;
        return false;
    }
    private static boolean emptyRow(List<String> row) {
        for (String value : row) if (value != null && value.trim().length() > 0) return false;
        return true;
    }
    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
        }
        return out.toString();
    }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.US); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static void checkLength(String value, int maximum, String field) throws IOException {
        if (value.length() > maximum) throw new IOException("An imported " + field + " exceeds the supported size");
    }
    private static void wipe(byte[] bytes) { if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0); }
}
