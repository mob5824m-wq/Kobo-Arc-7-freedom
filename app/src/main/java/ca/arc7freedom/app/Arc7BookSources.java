package ca.arc7freedom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Environment;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unified Multi-Source TLS 1.3 E-Book Search, Resolver, Downloader & Native `.FB2` Engine (`v2.2.0`):
 *
 *   1. **VK Books & Documents (`vk.com` — `SOURCE_VK`):**
 *      - Official **VK API `v5.199` (`https://api.vk.com/method/docs.search` & `wall.search`)**
 *        over `arc7tls` TLS 1.3 with persisted OAuth access token (`/sdcard/Arc7Freedom/vk_token.txt`
 *        + `SharedPreferences`), **1-Tap `vk.com` OAuth Implicit Sign-In WebView** routed through
 *        `127.0.0.1:8118` TLS 1.3 gateway, AND manual token paste support.
 *      - **Zero-Login Public `vk.com` Book Discovery & Direct `vk.com/doc...` Stream Extractor:**
 *        Queries public `site:vk.com` document/wall indexes over `arc7tls` TLS 1.3 even when not
 *        signed in, and automatically extracts real CDN download URLs (`docUrl`, `psv4.userapi.com`,
 *        `<iframe src="...">`, or direct file streams) from `https://vk.com/doc<owner>_<id>` pages!
 *      - **Interactive `m.vk.com` Live Book Browser Sheet** that intercepts any `.epub`, `.fb2`,
 *        `.fb2.zip`, `.pdf`, `.cbz`, `.txt`, or `vk.com/doc...` link and downloads it straight into
 *        `Books/` for instant offline reading!
 *   2. **Project Gutenberg (`SOURCE_GUTENBERG` — 70,000+ Public-Domain Classics via `gutendex.com`)**
 *   3. **Open Library / Internet Archive (`SOURCE_OPEN_LIBRARY` — `openlibrary.org` + `archive.org`)**
 *   4. **Standard Ebooks (`SOURCE_STANDARD_EBOOKS` — Curated Typography OPDS Catalog)**
 *   5. **Native FictionBook 2.0 (`.fb2` & `.fb2.zip`) Chapter & Embedded Cover/Image Parser**
 */
public final class Arc7BookSources {

    private Arc7BookSources() {}

    public static final int SOURCE_VK              = 0;
    public static final int SOURCE_GUTENBERG       = 1;
    public static final int SOURCE_OPEN_LIBRARY    = 2;
    public static final int SOURCE_STANDARD_EBOOKS = 3;

    public static final String[] SOURCE_NAMES = new String[]{
            "VK Books (vk.com — EPUB / FB2 / PDF / CBZ)",
            "Project Gutenberg (70,000+ Free Classics)",
            "Open Library & Internet Archive",
            "Standard Ebooks (Curated Typography)"
    };

    public static final String[] VK_FORMAT_FILTERS = new String[]{
            "All Book Formats (epub / fb2 / pdf)",
            "EPUB (.epub)",
            "FictionBook (.fb2 / .fb2.zip)",
            "PDF Documents (.pdf)",
            "Comics & Text (.cbz / .txt)"
    };

    private static final String PREFS = "arc7_vk_books";
    private static final String KEY_VK_TOKEN = "vk_access_token";
    public static final String VK_API_VERSION = "5.199";
    // Standard VK standalone mobile OAuth client ID supporting implicit redirect to oauth.vk.com/blank.html
    public static final String VK_OAUTH_CLIENT_ID = "6121396";

    public static final class BookHit {
        public String title = "";
        public String author = "";
        public String format = "epub";
        public long sizeBytes = 0L;
        public String downloadUrl = "";
        public String sourceBadge = "";
        public boolean needsVkPageResolve = false;

        public String displayLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(format.toUpperCase(Locale.US)).append("] ");
            sb.append(title);
            if (author != null && author.trim().length() > 0) {
                sb.append(" — ").append(author.trim());
            }
            if (sizeBytes > 0L) {
                if (sizeBytes >= 1048576L) {
                    sb.append(String.format(Locale.US, " (%.1f MB)", sizeBytes / 1048576.0));
                } else {
                    sb.append(" (").append(Math.max(1L, sizeBytes / 1024L)).append(" KB)");
                }
            }
            if (sourceBadge != null && sourceBadge.length() > 0) {
                sb.append(" • ").append(sourceBadge);
            }
            return sb.toString();
        }
    }

    public static final class Fb2ParsedBook {
        public String title = "FB2 Book";
        public String author = "";
        public final List<String> chapterTitles = new ArrayList<String>();
        public final List<String> chapterHtmls = new ArrayList<String>();
    }

    public interface TokenSavedCallback {
        void onTokenChanged(String newToken);
    }

    public interface DirectBookInterceptCallback {
        void onBookUrlIntercepted(String titleHint, String url);
    }

    /* ==================== VK Token Persistence & OAuth Login ==================== */

    private static File sharedVkTokenFile() {
        File ext = null;
        try { ext = Environment.getExternalStorageDirectory(); } catch (Throwable ignored) {}
        return ext != null
                ? new File(ext, "Arc7Freedom/vk_token.txt")
                : new File("/sdcard/Arc7Freedom/vk_token.txt");
    }

    public static String getVkToken(Context ctx) {
        try {
            File f = sharedVkTokenFile();
            if (f.isFile() && f.canRead()) {
                byte[] buf = readBytes(f, 8192);
                String t = new String(buf, "UTF-8").trim();
                if (t.length() > 12) return extractAccessTokenIfUrl(t);
            }
        } catch (Throwable ignored) {}
        if (ctx != null) {
            String t = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VK_TOKEN, "").trim();
            return extractAccessTokenIfUrl(t);
        }
        return "";
    }

    public static void saveVkToken(Context ctx, String rawTokenOrRedirectUrl) {
        String clean = extractAccessTokenIfUrl(rawTokenOrRedirectUrl == null ? "" : rawTokenOrRedirectUrl.trim());
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_VK_TOKEN, clean).commit();
        }
        try {
            File f = sharedVkTokenFile();
            if (clean.isEmpty()) {
                if (f.exists()) f.delete();
            } else {
                if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                try { fos.write(clean.getBytes("UTF-8")); } finally { try { fos.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {}
    }

    public static String extractAccessTokenIfUrl(String raw) {
        if (raw == null) return "";
        Matcher m = Pattern.compile("access_token=([A-Za-z0-9._\\-]+)").matcher(raw);
        if (m.find()) return m.group(1);
        return raw.trim();
    }

    public static String buildVkOAuthAuthorizeUrl() {
        return "https://oauth.vk.com/authorize?client_id=" + VK_OAUTH_CLIENT_ID
                + "&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html"
                + "&scope=docs,groups,wall,offline&response_type=token&v=" + VK_API_VERSION;
    }

    public static void showVkAccountDialog(final Activity host, final NetworkCertManager certMgr,
                                           final TokenSavedCallback cb) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Arc7Skin.dp(host, 14);
        box.setPadding(p, p, p, p);

        String cur = getVkToken(host);
        TextView info = new TextView(host);
        info.setText((cur.length() > 12
                ? "✓ Signed in to vk.com (Direct VK `docs.search` API active!)\n\n"
                : "Public vk.com book discovery already works right away! Sign in to vk.com (or paste a VK token / redirect URL) to unlock full `docs.search` across millions of EPUB, FB2 & PDF books:\n\n")
                + "Tip: Tap '1-Tap Sign In on vk.com' below, or paste an `access_token` / `blank.html#access_token=...` URL:");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        box.addView(info);

        final EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("Paste VK access_token or blank.html#access_token=... URL");
        input.setText(cur);
        box.addView(input);

        Button oauthBtn = Arc7Skin.actionButton(host, "1-Tap Sign In on vk.com (TLS 1.3)", 0xFF2563EB);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Arc7Skin.dp(host, 10);
        box.addView(oauthBtn, blp);

        final AlertDialog dlg = new AlertDialog.Builder(host, AlertDialog.THEME_HOLO_DARK)
                .setTitle("vk.com Book Source Account (`docs.search`)")
                .setView(box)
                .setPositiveButton("Save Token", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String tok = extractAccessTokenIfUrl(input.getText().toString());
                        saveVkToken(host, tok);
                        Toast.makeText(host, tok.isEmpty() ? "Cleared VK token." : "VK Book API token saved!",
                                Toast.LENGTH_SHORT).show();
                        if (cb != null) cb.onTokenChanged(tok);
                    }
                })
                .setNeutralButton("Sign Out", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveVkToken(host, "");
                        if (cb != null) cb.onTokenChanged("");
                    }
                })
                .setNegativeButton("Close", null)
                .create();

        oauthBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                openVkInteractiveWebSheet(host, certMgr, buildVkOAuthAuthorizeUrl(), cb, null);
            }
        });
        dlg.show();
    }

    /**
     * Opens an embedded TLS-1.3-bridged `vk.com` mobile sheet (`m.vk.com` or `oauth.vk.com`)
     * that BOTH auto-captures `#access_token=...` on login AND automatically intercepts any
     * `.epub`, `.fb2`, `.fb2.zip`, `.pdf`, `.cbz`, `.txt`, or `vk.com/doc...` link tapped by the user!
     */
    public static void openVkInteractiveWebSheet(final Activity host,
                                                 final NetworkCertManager certMgr,
                                                 final String initialHttpsUrl,
                                                 final TokenSavedCallback tokenCb,
                                                 final DirectBookInterceptCallback bookCb) {
        try { certMgr.ensureCaBundleFile(); } catch (Throwable ignored) {}
        certMgr.startBrowserGateway();
        final int gwPort = NetworkCertManager.BROWSER_GATEWAY_PORT;
        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0xFF0B111B);

        LinearLayout hdr = new LinearLayout(host);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Arc7Skin.dp(host, 8);
        hdr.setPadding(hp, hp, hp, hp);
        hdr.setBackgroundColor(0xFF172554);

        final TextView statusTv = new TextView(host);
        statusTv.setText("vk.com TLS 1.3 Book Browser — tap any EPUB / FB2 / PDF document to download & read!");
        statusTv.setTextColor(Color.WHITE);
        statusTv.setSingleLine(true);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hdr.addView(statusTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final WebView wv = new WebView(host);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/114.0.0.0 Mobile Safari/537.36");

        final AlertDialog sheet = new AlertDialog.Builder(host, AlertDialog.THEME_HOLO_DARK)
                .setView(wrap)
                .create();

        Button closeBtn = Arc7Skin.actionButton(host, "Close", 0xFF475569);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wv.stopLoading();
                sheet.dismiss();
            }
        });
        hdr.addView(closeBtn);
        wrap.addView(hdr);
        wrap.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Arc7Skin.dp(host, 470)));

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String realUrl = unwrapGatewayUrl(url);
                if (realUrl.contains("access_token=")) {
                    String tok = extractAccessTokenIfUrl(realUrl);
                    saveVkToken(host, tok);
                    Toast.makeText(host, "Signed in to vk.com! Full VK Book Search active!", Toast.LENGTH_LONG).show();
                    sheet.dismiss();
                    if (tokenCb != null) tokenCb.onTokenChanged(tok);
                    return true;
                }
                if (bookCb != null && looksLikeDirectBookOrVkDocUrl(realUrl)) {
                    sheet.dismiss();
                    bookCb.onBookUrlIntercepted(guessTitleFromUrl(realUrl), realUrl);
                    return true;
                }
                view.loadUrl(wrapThroughLocalGateway(realUrl, gwPort));
                return true;
            }
        });

        sheet.show();
        wv.loadUrl(wrapThroughLocalGateway(initialHttpsUrl, gwPort));
    }

    private static String wrapThroughLocalGateway(String url, int gwPort) {
        if (url == null) return "";
        if (url.startsWith("http://127.0.0.1:")) return url;
        if (url.startsWith("https://") || url.startsWith("http://")) {
            try {
                return "http://127.0.0.1:" + gwPort + "/__arc7_proxy?url=" + URLEncoder.encode(url, "UTF-8");
            } catch (Throwable ignored) {}
        }
        return url;
    }

    private static String unwrapGatewayUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf("url=");
        if (url.contains("/__arc7_") && q > 0) {
            try {
                String enc = url.substring(q + 4);
                int amp = enc.indexOf('&');
                if (amp > 0) enc = enc.substring(0, amp);
                return URLDecoder.decode(enc, "UTF-8");
            } catch (Throwable ignored) {}
        }
        return url;
    }

    public static boolean looksLikeDirectBookOrVkDocUrl(String url) {
        if (url == null) return false;
        String clean = url.split("\\?")[0].toLowerCase(Locale.US);
        if (clean.endsWith(".epub") || clean.endsWith(".fb2") || clean.endsWith(".fb2.zip")
                || clean.endsWith(".pdf") || clean.endsWith(".cbz") || clean.endsWith(".txt")) {
            return true;
        }
        return Pattern.compile("^https?://(m\\.|www\\.)?vk\\.com/doc-?\\d+_\\d+").matcher(url).find()
                || url.contains("userapi.com/c") || url.contains("psv4.userapi.com/");
    }

    /* ==================== Live TLS 1.3 Fetch Helper ==================== */

    public static String fetchStringViaTls13(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                             String url) throws Exception {
        File out = File.createTempFile("arc7_bk_", ".tmp", ctx.getCacheDir());
        try {
            fetchFileViaTls13(shell, certMgr, url, out);
            byte[] raw = readBytes(out, 2 * 1024 * 1024);
            return new String(raw, "UTF-8");
        } finally {
            try { out.delete(); } catch (Throwable ignored) {}
        }
    }

    public static void fetchFileViaTls13(RootShell shell, NetworkCertManager certMgr,
                                         String url, File dest) throws Exception {
        File bundle = certMgr.ensureCaBundleFile();
        RootShell.ExecResult r = shell.execCommandArray(new String[]{
                shell.getTlsEngineBinary().getAbsolutePath(),
                "fetch", bundle.getAbsolutePath(), url, dest.getAbsolutePath()
        }, null);
        if (r.exitCode != 0 || !dest.exists() || dest.length() == 0) {
            throw new RuntimeException("TLS 1.3 fetch failed (" + r.stdout.trim() + ")");
        }
    }

    /* ==================== Multi-Source Book Search ==================== */

    public static List<BookHit> searchBooks(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                            int sourceId, String query, int vkFormatFilter) throws Exception {
        String q = query == null ? "" : query.trim();
        switch (sourceId) {
            case SOURCE_VK:
                return searchVkBooks(ctx, shell, certMgr, q, vkFormatFilter);
            case SOURCE_OPEN_LIBRARY:
                return searchOpenLibrary(ctx, shell, certMgr, q.isEmpty() ? "dune" : q);
            case SOURCE_STANDARD_EBOOKS:
                return searchStandardEbooks(ctx, shell, certMgr, q);
            case SOURCE_GUTENBERG:
            default:
                return searchGutendex(ctx, shell, certMgr, q);
        }
    }

    /**
     * Searches `vk.com` for books (`EPUB`, `FB2`, `PDF`, `CBZ`, `TXT`):
     *   - Accepts pasted direct `https://vk.com/doc...` URLs instantly,
     *   - Queries official `https://api.vk.com/method/docs.search` over TLS 1.3 when a VK token is set,
     *   - Automatically falls back / merges zero-login public `site:vk.com` search so users immediately
     *     find real `vk.com` e-book documents even with zero VK account!
     */
    public static List<BookHit> searchVkBooks(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                              String query, int vkFormatFilter) throws Exception {
        List<BookHit> results = new ArrayList<BookHit>();
        String effectiveQuery = query == null || query.trim().isEmpty() ? "Sherlock Holmes" : query.trim();

        // Direct pasted `vk.com/doc...` or `psv4.userapi.com/...` URL!
        if (effectiveQuery.startsWith("http://") || effectiveQuery.startsWith("https://")) {
            BookHit direct = new BookHit();
            direct.title = guessTitleFromUrl(effectiveQuery);
            direct.format = detectExtensionFromText(effectiveQuery);
            direct.downloadUrl = effectiveQuery;
            direct.sourceBadge = "vk.com Direct Link";
            direct.needsVkPageResolve = effectiveQuery.contains("vk.com/doc");
            results.add(direct);
            return results;
        }

        String extHint = "";
        if (vkFormatFilter == 1) extHint = " epub";
        else if (vkFormatFilter == 2) extHint = " fb2";
        else if (vkFormatFilter == 3) extHint = " pdf";
        else if (vkFormatFilter == 4) extHint = " cbz";

        // 1. Official `api.vk.com/method/docs.search` when user signed into VK
        String token = getVkToken(ctx);
        if (!token.isEmpty()) {
            try {
                String apiUrl = "https://api.vk.com/method/docs.search?q="
                        + URLEncoder.encode(effectiveQuery + extHint, "UTF-8")
                        + "&count=40&v=" + VK_API_VERSION
                        + "&access_token=" + URLEncoder.encode(token, "UTF-8");
                String json = fetchStringViaTls13(ctx, shell, certMgr, apiUrl);
                results.addAll(parseVkDocsSearchJson(json, vkFormatFilter));
            } catch (Throwable ignored) {}
        }

        // 2. Zero-Login Public `site:vk.com` Book Discovery (works immediately without any VK login!)
        if (results.isEmpty()) {
            String fmtExpr = vkFormatFilter == 1 ? "epub"
                    : (vkFormatFilter == 2 ? "fb2"
                    : (vkFormatFilter == 3 ? "pdf"
                    : (vkFormatFilter == 4 ? "cbz" : "(epub OR fb2 OR pdf)")));
            String ddgUrl = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode("site:vk.com " + effectiveQuery + " " + fmtExpr, "UTF-8");
            try {
                String html = fetchStringViaTls13(ctx, shell, certMgr, ddgUrl);
                results.addAll(parsePublicVkSearchHtml(html));
            } catch (Throwable ignored) {}
        }
        return results;
    }

    public static List<BookHit> parseVkDocsSearchJson(String json, int vkFormatFilter) {
        List<BookHit> out = new ArrayList<BookHit>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("response");
            if (resp == null) return out;
            JSONArray items = resp.optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String ext = it.optString("ext", "").toLowerCase(Locale.US).trim();
                String url = it.optString("url", "").trim();
                String title = it.optString("title", "VK Book").trim();
                if (url.isEmpty()) continue;
                if (!matchesBookExtension(ext, vkFormatFilter)) continue;
                BookHit hit = new BookHit();
                hit.title = stripBookExt(title);
                hit.format = title.toLowerCase(Locale.US).endsWith(".fb2.zip") ? "fb2.zip" : ext;
                hit.sizeBytes = it.optLong("size", 0L);
                hit.downloadUrl = url;
                hit.sourceBadge = "vk.com API";
                hit.needsVkPageResolve = false;
                out.add(hit);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static List<BookHit> parsePublicVkSearchHtml(String html) {
        List<BookHit> out = new ArrayList<BookHit>();
        if (html == null || html.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile(
                "<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find() && out.size() < 30) {
            String rawHref = m.group(1);
            String titleHtml = stripHtmlTags(m.group(2)).trim();
            String decoded = decodeDuckDuckGoUddg(rawHref);
            if (!decoded.contains("vk.com/")) continue;
            if (!seen.add(decoded)) continue;
            BookHit hit = new BookHit();
            hit.title = titleHtml.isEmpty() ? guessTitleFromUrl(decoded) : titleHtml;
            hit.format = detectExtensionFromText(titleHtml + " " + decoded);
            hit.downloadUrl = decoded;
            hit.sourceBadge = decoded.contains("/doc") ? "vk.com Document" : "vk.com Book Post";
            hit.needsVkPageResolve = true;
            out.add(hit);
        }
        return out;
    }

    public static String decodeDuckDuckGoUddg(String rawHref) {
        if (rawHref == null) return "";
        String clean = rawHref.replace("&amp;", "&");
        int idx = clean.indexOf("uddg=");
        if (idx >= 0) {
            String sub = clean.substring(idx + 5);
            int amp = sub.indexOf('&');
            if (amp >= 0) sub = sub.substring(0, amp);
            try { return URLDecoder.decode(sub, "UTF-8"); } catch (Throwable ignored) {}
        }
        return clean;
    }

    /**
     * Resolves a public `https://vk.com/doc...` or `https://vk.com/wall...` HTML response into the
     * actual direct `https://psv4.userapi.com/...` / CDN binary file stream URL!
     */
    public static String extractDirectVkDocFileUrl(String pageHtml) {
        if (pageHtml == null || pageHtml.isEmpty()) return null;
        // 1. JSON embedded `"docUrl":"https:\/\/psv4.userapi.com\/..."` inside VK's modern doc viewer page
        Matcher m1 = Pattern.compile("\"docUrl\"\\s*:\\s*\"([^\"]+)\"").matcher(pageHtml);
        if (m1.find()) {
            return unescapeJsonSlashes(m1.group(1));
        }
        // 2. Direct CDN stream on `psv4.userapi.com` / `userapi.com/c...`
        Matcher m2 = Pattern.compile("(https?:\\\\?/\\\\?/[a-z0-9.\\-]*userapi\\.com[^\"'<>\\s]+)",
                Pattern.CASE_INSENSITIVE).matcher(pageHtml);
        while (m2.find()) {
            String cand = unescapeJsonSlashes(m2.group(1)).replace("&amp;", "&");
            if (!cand.endsWith(".js") && !cand.endsWith(".css") && !cand.endsWith(".png")) {
                return cand;
            }
        }
        // 3. Direct `/doc<owner>_<id>?hash=...&dl=...` link inside wall post HTML
        Matcher m3 = Pattern.compile("(https?:\\\\?/\\\\?/(?:m\\.)?vk\\.com\\\\?/doc-?\\d+_\\d+\\?[^\"'<>\\s]+)",
                Pattern.CASE_INSENSITIVE).matcher(pageHtml);
        if (m3.find()) {
            return unescapeJsonSlashes(m3.group(1)).replace("&amp;", "&");
        }
        Matcher m4 = Pattern.compile("href=\"(/doc-?\\d+_\\d+[^\"]*)\"").matcher(pageHtml);
        if (m4.find()) {
            return "https://vk.com" + m4.group(1).replace("&amp;", "&");
        }
        return null;
    }

    private static String unescapeJsonSlashes(String s) {
        return s == null ? "" : s.replace("\\/", "/").replace("\\u0026", "&");
    }

    public static File downloadBookHitToLibrary(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                                BookHit hit, File booksDir) throws Exception {
        booksDir.mkdirs();
        File tmp = new File(ctx.getCacheDir(), "vk_or_store_dl.bin");
        if (tmp.exists()) tmp.delete();

        String targetUrl = hit.downloadUrl;
        fetchFileViaTls13(shell, certMgr, targetUrl, tmp);

        // If `vk.com` returned an HTML wrapper/post page instead of raw EPUB/FB2/PDF/ZIP bytes, resolve `docUrl` up to 2 hops!
        for (int hop = 0; hop < 2 && isHtmlWrapper(tmp); hop++) {
            String html = new String(readBytes(tmp, 1024 * 1024), "UTF-8");
            String direct = extractDirectVkDocFileUrl(html);
            if (direct == null || direct.equals(targetUrl)) {
                throw new RuntimeException("This vk.com post requires signing in (`VK Login` button) or opening via `Browse m.vk.com`.");
            }
            targetUrl = direct;
            tmp.delete();
            fetchFileViaTls13(shell, certMgr, targetUrl, tmp);
        }

        String ext = sniffRealBookExtension(tmp, hit.format);
        String safe = hit.title.replaceAll("[^A-Za-z0-9._ \\-\\u00C0-\\u04FF]", "_").trim();
        if (safe.length() > 64) safe = safe.substring(0, 64).trim();
        if (safe.isEmpty()) safe = "Downloaded_Book";
        File finalFile = new File(booksDir, safe + "." + ext);
        copyFile(tmp, finalFile);
        tmp.delete();
        return finalFile;
    }

    private static boolean isHtmlWrapper(File f) {
        try {
            byte[] head = readBytes(f, 512);
            if (head.length < 4) return false;
            // ZIP/EPUB/CBZ (`PK`), PDF (`%PDF`), XML/FB2 (`<?xml` or `<FictionBook`)
            if (head[0] == 'P' && head[1] == 'K') return false;
            if (head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') return false;
            String s = new String(head, "UTF-8").trim().toLowerCase(Locale.US);
            if (s.contains("<fictionbook")) return false;
            return s.startsWith("<!doctype html") || s.startsWith("<html") || s.contains("<head>");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String sniffRealBookExtension(File f, String fallbackExt) {
        try {
            byte[] head = readBytes(f, 512);
            if (head.length >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') {
                return "pdf";
            }
            if (head.length >= 4 && head[0] == 'P' && head[1] == 'K') {
                // Distinguish `.epub` vs `.fb2.zip` vs `.cbz`!
                ZipFile zf = new ZipFile(f);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName().toLowerCase(Locale.US);
                        if (n.endsWith("content.opf") || n.equals("mimetype")) return "epub";
                        if (n.endsWith(".fb2")) return "fb2.zip";
                        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                            if ("cbz".equalsIgnoreCase(fallbackExt)) return "cbz";
                        }
                    }
                } finally {
                    try { zf.close(); } catch (Throwable ignored) {}
                }
                return "cbz".equalsIgnoreCase(fallbackExt) ? "cbz" : "epub";
            }
            String s = new String(head, "UTF-8");
            if (s.contains("<FictionBook") || s.contains("<fictionbook")) return "fb2";
        } catch (Throwable ignored) {}
        return (fallbackExt == null || fallbackExt.isEmpty()) ? "epub" : fallbackExt.toLowerCase(Locale.US);
    }

    /* ==================== Other 3 Free Storefronts (TLS 1.3) ==================== */

    public static List<BookHit> searchGutendex(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                               String query) throws Exception {
        String apiUrl = query.isEmpty()
                ? "https://gutendex.com/books/?mime_type=application%2Fepub%2Bzip"
                : ("https://gutendex.com/books/?mime_type=application%2Fepub%2Bzip&search="
                   + URLEncoder.encode(query, "UTF-8"));
        String raw = fetchStringViaTls13(ctx, shell, certMgr, apiUrl);
        JSONObject obj = new JSONObject(raw);
        JSONArray arr = obj.getJSONArray("results");
        List<BookHit> out = new ArrayList<BookHit>();
        for (int i = 0; i < Math.min(30, arr.length()); i++) {
            JSONObject b = arr.getJSONObject(i);
            JSONObject formats = b.optJSONObject("formats");
            if (formats == null) continue;
            String epubUrl = formats.optString("application/epub+zip", "");
            if (epubUrl.isEmpty()) continue;
            BookHit hit = new BookHit();
            hit.title = b.optString("title", "Untitled");
            JSONArray authors = b.optJSONArray("authors");
            if (authors != null && authors.length() > 0) {
                hit.author = authors.getJSONObject(0).optString("name", "");
            }
            hit.format = "epub";
            hit.downloadUrl = epubUrl;
            hit.sourceBadge = "Project Gutenberg";
            out.add(hit);
        }
        return out;
    }

    public static List<BookHit> searchOpenLibrary(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                                  String query) throws Exception {
        String apiUrl = "https://openlibrary.org/search.json?has_fulltext=true&limit=25&q="
                + URLEncoder.encode(query, "UTF-8");
        String raw = fetchStringViaTls13(ctx, shell, certMgr, apiUrl);
        JSONObject obj = new JSONObject(raw);
        JSONArray docs = obj.optJSONArray("docs");
        List<BookHit> out = new ArrayList<BookHit>();
        if (docs == null) return out;
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.optJSONObject(i);
            if (d == null || !d.optBoolean("public_scan_b", false)) continue;
            JSONArray iaArr = d.optJSONArray("ia");
            if (iaArr == null || iaArr.length() == 0) continue;
            String iaId = iaArr.optString(0, "");
            if (iaId.isEmpty()) continue;
            BookHit hit = new BookHit();
            hit.title = d.optString("title", iaId);
            JSONArray auth = d.optJSONArray("author_name");
            if (auth != null && auth.length() > 0) hit.author = auth.optString(0, "");
            hit.format = "epub";
            hit.downloadUrl = "https://archive.org/download/" + iaId + "/" + iaId + ".epub";
            hit.sourceBadge = "Open Library / IA";
            out.add(hit);
        }
        return out;
    }

    public static List<BookHit> searchStandardEbooks(Context ctx, RootShell shell, NetworkCertManager certMgr,
                                                     String query) throws Exception {
        String url = "https://standardebooks.org/ebooks"
                + (query.isEmpty() ? "" : ("?query=" + URLEncoder.encode(query, "UTF-8")));
        String html = fetchStringViaTls13(ctx, shell, certMgr, url);
        List<BookHit> out = new ArrayList<BookHit>();
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("href=\"(/ebooks/[a-z0-9\\-]+/[a-z0-9\\-]+(?:/[a-z0-9\\-]+)?)\"")
                .matcher(html);
        while (m.find() && out.size() < 25) {
            String path = m.group(1);
            if (path.endsWith("/downloads") || !seen.add(path)) continue;
            String[] segs = path.substring("/ebooks/".length()).split("/");
            if (segs.length < 2) continue;
            String slug = path.substring("/ebooks/".length()).replace("/", "_");
            BookHit hit = new BookHit();
            hit.author = prettifySlug(segs[0]);
            hit.title = prettifySlug(segs[segs.length - 1]);
            hit.format = "epub";
            hit.downloadUrl = "https://standardebooks.org" + path + "/downloads/" + slug + ".epub?source=download";
            hit.sourceBadge = "Standard Ebooks";
            out.add(hit);
        }
        return out;
    }

    /* ==================== Native FictionBook 2.0 (`.fb2` / `.fb2.zip`) Parser ==================== */

    public static Fb2ParsedBook parseFb2File(File file) throws Exception {
        byte[] xmlBytes = null;
        if (file.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            ZipFile zf = new ZipFile(file);
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (!ze.isDirectory() && ze.getName().toLowerCase(Locale.US).endsWith(".fb2")) {
                        xmlBytes = readStream(zf.getInputStream(ze), 16 * 1024 * 1024);
                        break;
                    }
                }
            } finally {
                try { zf.close(); } catch (Throwable ignored) {}
            }
        } else {
            xmlBytes = readBytes(file, 16 * 1024 * 1024);
        }
        if (xmlBytes == null || xmlBytes.length == 0) {
            throw new IllegalArgumentException("Empty .fb2 file");
        }
        return parseFb2XmlBytes(xmlBytes);
    }

    public static Fb2ParsedBook parseFb2XmlBytes(byte[] xmlBytes) throws Exception {
        Fb2ParsedBook book = new Fb2ParsedBook();
        // First fast pass for `<binary id="..." content-type="...">base64</binary>` covers/illustrations
        Map<String, String> binaryDataUris = new HashMap<String, String>();
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser xp = f.newPullParser();
        xp.setInput(new ByteArrayInputStream(xmlBytes), detectXmlEncoding(xmlBytes));

        int ev;
        while ((ev = xp.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && localTag(xp.getName()).equals("binary")) {
                String id = xp.getAttributeValue(null, "id");
                String ct = xp.getAttributeValue(null, "content-type");
                String b64 = xp.nextText();
                if (id != null && b64 != null && b64.length() < 1500000) {
                    binaryDataUris.put(id, "data:" + (ct == null ? "image/jpeg" : ct) + ";base64,"
                            + b64.replaceAll("\\s+", ""));
                }
            }
        }

        // Second pass for metadata + `<section>` chapters
        xp = f.newPullParser();
        xp.setInput(new ByteArrayInputStream(xmlBytes), detectXmlEncoding(xmlBytes));
        boolean inTitleInfo = false;
        boolean inBody = false;
        int sectionDepth = 0;
        String firstName = "", lastName = "";
        String currentSecTitle = "";
        StringBuilder secHtml = new StringBuilder();
        boolean inTitleTag = false;
        StringBuilder titleBuf = new StringBuilder();

        while ((ev = xp.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = localTag(xp.getName());
                if ("title-info".equals(tag)) inTitleInfo = true;
                else if (inTitleInfo && "book-title".equals(tag)) book.title = xp.nextText().trim();
                else if (inTitleInfo && "first-name".equals(tag)) firstName = xp.nextText().trim();
                else if (inTitleInfo && "last-name".equals(tag)) lastName = xp.nextText().trim();
                else if ("body".equals(tag)) inBody = true;
                else if (inBody && "section".equals(tag)) {
                    if (sectionDepth > 0 && secHtml.length() > 0) {
                        book.chapterTitles.add(currentSecTitle.isEmpty()
                                ? ("Section " + (book.chapterHtmls.size() + 1)) : currentSecTitle);
                        book.chapterHtmls.add(secHtml.toString());
                        secHtml.setLength(0);
                        currentSecTitle = "";
                    }
                    sectionDepth++;
                } else if (inBody && "title".equals(tag)) {
                    inTitleTag = true;
                    titleBuf.setLength(0);
                    secHtml.append("<h2>");
                } else if (inBody && "p".equals(tag)) {
                    secHtml.append("<p>");
                } else if (inBody && "emphasis".equals(tag)) {
                    secHtml.append("<em>");
                } else if (inBody && "strong".equals(tag)) {
                    secHtml.append("<strong>");
                } else if (inBody && "empty-line".equals(tag)) {
                    secHtml.append("<br/>");
                } else if (inBody && "image".equals(tag)) {
                    for (int a = 0; a < xp.getAttributeCount(); a++) {
                        String an = xp.getAttributeName(a);
                        if (an != null && an.endsWith("href")) {
                            String ref = xp.getAttributeValue(a).replace("#", "");
                            String uri = binaryDataUris.get(ref);
                            if (uri != null) {
                                secHtml.append("<div style='text-align:center;margin:12px 0;'><img src='")
                                       .append(uri).append("' style='max-width:95%;height:auto;'/></div>");
                            }
                        }
                    }
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inBody && sectionDepth > 0) {
                    String txt = xp.getText();
                    if (inTitleTag) titleBuf.append(txt).append(' ');
                    secHtml.append(escapeHtml(txt));
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = localTag(xp.getName());
                if ("title-info".equals(tag)) inTitleInfo = false;
                else if ("body".equals(tag)) inBody = false;
                else if (inBody && "title".equals(tag)) {
                    inTitleTag = false;
                    currentSecTitle = titleBuf.toString().replaceAll("\\s+", " ").trim();
                    secHtml.append("</h2>");
                } else if (inBody && "p".equals(tag)) {
                    secHtml.append("</p>");
                } else if (inBody && "emphasis".equals(tag)) {
                    secHtml.append("</em>");
                } else if (inBody && "strong".equals(tag)) {
                    secHtml.append("</strong>");
                } else if (inBody && "section".equals(tag)) {
                    if (secHtml.length() > 0) {
                        book.chapterTitles.add(currentSecTitle.isEmpty()
                                ? ("Chapter " + (book.chapterHtmls.size() + 1)) : currentSecTitle);
                        book.chapterHtmls.add(secHtml.toString());
                        secHtml.setLength(0);
                        currentSecTitle = "";
                    }
                    sectionDepth = Math.max(0, sectionDepth - 1);
                }
            }
        }
        book.author = (firstName + " " + lastName).trim();
        if (book.chapterHtmls.isEmpty()) {
            book.chapterTitles.add(book.title);
            book.chapterHtmls.add("<p>(Empty FB2 body)</p>");
        }
        return book;
    }

    private static String detectXmlEncoding(byte[] raw) {
        try {
            String head = new String(raw, 0, Math.min(200, raw.length), "ISO-8859-1").toLowerCase(Locale.US);
            if (head.contains("windows-1251")) return "windows-1251";
            if (head.contains("koi8-r")) return "KOI8-R";
        } catch (Throwable ignored) {}
        return "UTF-8";
    }

    private static String localTag(String name) {
        if (name == null) return "";
        int c = name.indexOf(':');
        return (c >= 0 ? name.substring(c + 1) : name).toLowerCase(Locale.US);
    }

    private static boolean matchesBookExtension(String ext, int vkFormatFilter) {
        if (ext == null) return false;
        boolean isBook = "epub".equals(ext) || "fb2".equals(ext) || "pdf".equals(ext)
                || "cbz".equals(ext) || "txt".equals(ext) || "zip".equals(ext) || "djvu".equals(ext);
        if (!isBook) return false;
        if (vkFormatFilter == 1) return "epub".equals(ext);
        if (vkFormatFilter == 2) return "fb2".equals(ext) || "zip".equals(ext);
        if (vkFormatFilter == 3) return "pdf".equals(ext);
        if (vkFormatFilter == 4) return "cbz".equals(ext) || "txt".equals(ext);
        return true;
    }

    private static String detectExtensionFromText(String s) {
        String l = s == null ? "" : s.toLowerCase(Locale.US);
        if (l.contains("fb2.zip")) return "fb2.zip";
        if (l.contains("fb2")) return "fb2";
        if (l.contains("pdf")) return "pdf";
        if (l.contains("cbz")) return "cbz";
        if (l.contains("txt")) return "txt";
        return "epub";
    }

    private static String stripBookExt(String t) {
        return t == null ? "" : t.replaceAll("(?i)\\.(epub|fb2\\.zip|fb2|pdf|cbz|txt)$", "").trim();
    }

    private static String stripHtmlTags(String h) {
        return h == null ? "" : h.replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"").replace("&#x27;", "'").replace("&amp;", "&");
    }

    private static String prettifySlug(String s) {
        String[] parts = s.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private static String guessTitleFromUrl(String url) {
        try {
            String p = url.split("\\?")[0];
            int s = p.lastIndexOf('/');
            String last = URLDecoder.decode(s >= 0 ? p.substring(s + 1) : p, "UTF-8");
            return last.isEmpty() ? "VK Book" : stripBookExt(last);
        } catch (Throwable ignored) {
            return "VK Book";
        }
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static byte[] readBytes(File f, int maxBytes) throws Exception {
        InputStream in = new FileInputStream(f);
        try { return readStream(in, maxBytes); } finally { try { in.close(); } catch (Throwable ignored) {} }
    }

    private static byte[] readStream(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0, n;
        while ((n = in.read(buf)) > 0) {
            int take = Math.min(n, maxBytes - total);
            out.write(buf, 0, take);
            total += take;
            if (total >= maxBytes) break;
        }
        return out.toByteArray();
    }

    private static void copyFile(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
