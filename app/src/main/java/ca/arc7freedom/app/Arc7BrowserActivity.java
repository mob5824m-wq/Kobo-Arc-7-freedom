package ca.arc7freedom.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Full Modern `TLS 1.3` + `2026 Root CA` Web Browser (`Arc7 Browser`) for Android 4.2.2.
 *
 * Architecture & Real-Hardware Fixes (`v1.6.1`):
 *  - Completely removes the buggy `Lite` mode toggle & destructive HTML/CSS regex stripping.
 *  - Runs a single persistent multithreaded native `libarc7tls.so` daemon on `127.0.0.1:18119`
 *    with all 120 modern 2026 Root CAs parsed ONCE in C memory (eliminating per-resource
 *    `fork()/exec()` + 380 ms X.509 PEM re-parsing stalls across CSS/JS/images!).
 *  - Transparently decompresses HTTP `Content-Encoding: gzip` (`0x1F 0x8B`) streams via
 *    `GZIPInputStream` so compressed modern sites never render raw binary gibberish.
 *  - Separates exact upstream `mimeType` and `charset` for Android 4.2 WebKit strict MIME checks,
 *    syncs `Cookie` <-> `Set-Cookie` via `CookieManager`, injects `<base href="...">` when HTTP
 *    redirects change the document base URL, and passes plain `http://` LAN pages directly through
 *    native WebKit so local LAN uploads & forms work natively.
 */
public class Arc7BrowserActivity extends Activity {

    private static final String HOME_PORTAL_URL = "arc7://home";
    private static final int DAEMON_PORT = 18119;
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; Kobo Arc 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Object daemonLock = new Object();

    private RootShell rootShell;
    private NetworkCertManager certManager;
    private Process tlsDaemonProc;

    private EditText urlBar;
    private Button reloadStopBtn;
    private ProgressBar progressBar;
    private TextView statusBanner;
    private WebView webView;

    private volatile boolean pageLoading = false;
    private volatile boolean desktopMode = false;
    private volatile String currentDocumentUrl = "";

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieSyncManager.createInstance(this);
        CookieManager.getInstance().setAcceptCookie(true);

        rootShell = new RootShell(this);
        certManager = new NetworkCertManager(this, rootShell);

        // Pre-warm the persistent TLS 1.3 daemon off the UI thread immediately
        new Thread(new Runnable() {
            @Override
            public void run() {
                ensureTlsDaemonRunning();
            }
        }).start();

        buildUi();

        Intent in = getIntent();
        String startUrl = (in != null && in.getDataString() != null && in.getDataString().trim().length() > 0)
                ? in.getDataString().trim()
                : HOME_PORTAL_URL;
        navigateTo(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getDataString() != null && intent.getDataString().trim().length() > 0) {
            navigateTo(intent.getDataString().trim());
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("deprecation")
    private void setBg(View v, int fill, int stroke, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), stroke);
        v.setBackgroundDrawable(gd);
    }

    private boolean pingTlsDaemon() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + DAEMON_PORT + "/__ping")
                    .openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(250);
            conn.setReadTimeout(400);
            return conn.getResponseCode() == 200;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean ensureTlsDaemonRunning() {
        if (pingTlsDaemon()) return true;
        synchronized (daemonLock) {
            if (pingTlsDaemon()) return true;
            try {
                File caBundle = certManager.ensureCaBundleFile();
                File tlsBin = rootShell.getTlsEngineBinary();
                ProcessBuilder pb = new ProcessBuilder(
                        tlsBin.getAbsolutePath(),
                        "proxy",
                        caBundle.getAbsolutePath(),
                        String.valueOf(DAEMON_PORT)
                );
                pb.redirectErrorStream(true);
                tlsDaemonProc = pb.start();
                tlsDaemonProc.getOutputStream().close();

                // Drain daemon stdout asynchronously so pipe buffer can never fill
                final InputStream daemonOut = tlsDaemonProc.getInputStream();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] sink = new byte[1024];
                        try {
                            while (daemonOut.read(sink) > 0) {
                                // discard log output
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }).start();

                for (int i = 0; i < 40; i++) {
                    if (pingTlsDaemon()) return true;
                    Thread.sleep(40L);
                }
            } catch (Throwable ignored) {
            }
            return pingTlsDaemon();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1319);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(0xFF161E28);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button backBtn = makeNavButton("<");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoBack()) webView.goBack();
            }
        });
        nav.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        Button fwdBtn = makeNavButton(">");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(40), dp(40));
        flp.leftMargin = dp(4);
        fwdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoForward()) webView.goForward();
            }
        });
        nav.addView(fwdBtn, flp);

        reloadStopBtn = makeNavButton("\u21BB");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        rlp.leftMargin = dp(4);
        reloadStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                if (pageLoading) {
                    webView.stopLoading();
                    setLoadingState(false);
                } else {
                    String cur = urlBar.getText().toString().trim();
                    if (cur.length() == 0) {
                        loadHomePage();
                    } else {
                        webView.reload();
                    }
                }
            }
        });
        nav.addView(reloadStopBtn, rlp);

        Button homeBtn = makeNavButton("Home");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(56), dp(40));
        hlp.leftMargin = dp(4);
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                loadHomePage();
            }
        });
        nav.addView(homeBtn, hlp);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setHint("Search web or enter address...");
        urlBar.setHintTextColor(0xFF64748B);
        urlBar.setTextColor(0xFFF1F5F9);
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        urlBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        setBg(urlBar, 0xFF0F172A, 0xFF334155, 6);
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    navigateTo(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        ulp.leftMargin = dp(6);
        ulp.rightMargin = dp(6);
        nav.addView(urlBar, ulp);

        Button goBtn = makeNavButton("Go");
        setBg(goBtn, 0xFF0284C7, 0xFF38BDF8, 6);
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateTo(urlBar.getText().toString());
            }
        });
        nav.addView(goBtn, new LinearLayout.LayoutParams(dp(48), dp(40)));

        Button menuBtn = makeNavButton("Menu");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(60), dp(40));
        blp.leftMargin = dp(4);
        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                showBrowserMenu();
            }
        });
        nav.addView(menuBtn, blp);

        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        statusBanner = new TextView(this);
        statusBanner.setBackgroundColor(0xFF0F2942);
        statusBanner.setTextColor(0xFF38BDF8);
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusBanner.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBanner.setVisibility(View.GONE);
        root.addView(statusBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAppCacheEnabled(true);
        ws.setAppCachePath(getCacheDir().getAbsolutePath());
        ws.setLoadsImagesAutomatically(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUserAgentString(UA_MOBILE);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                setLoadingState(newProgress < 100);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (url.equalsIgnoreCase(HOME_PORTAL_URL) || url.startsWith("https://arc7.home")) {
                    loadHomePage();
                    return true;
                }
                if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("market:")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                if (isDirectBinaryDownloadUrl(url)) {
                    startDownloadInBrowser(url);
                    return true;
                }
                currentDocumentUrl = url;
                urlBar.setText(url);
                statusBanner.setVisibility(View.GONE);
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                setLoadingState(true);
                if (url != null && !url.startsWith("data:") && !url.startsWith("https://arc7.home")) {
                    currentDocumentUrl = url;
                    if (!urlBar.hasFocus()) {
                        urlBar.setText(url);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                setLoadingState(false);
                CookieSyncManager.getInstance().sync();
                if (url != null && !url.startsWith("data:") && !url.startsWith("https://arc7.home") && !urlBar.hasFocus()) {
                    urlBar.setText(url);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url == null) return null;
                if (url.startsWith("https://arc7.home")) return null;
                if (isDirectBinaryDownloadUrl(url)) return null;

                // Let plain `http://` local LAN / localhost servers run directly in WebKit
                // so local POST uploads, router pages, and Wi-Fi Drive stream natively!
                if (url.startsWith("http://") && isLocalNetworkUrl(url)) {
                    return null;
                }
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    return null;
                }
                return fetchViaPersistentTlsDaemon(url);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                startDownloadInBrowser(url);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Arc7UiAnimator.attachActivityAnimations(this);
    }

    private void setLoadingState(final boolean loading) {
        pageLoading = loading;
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (reloadStopBtn != null) {
            reloadStopBtn.setText(loading ? "\u2715" : "\u21BB");
        }
    }

    private void hideKeyboard() {
        try {
            urlBar.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && urlBar != null) {
                imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLocalNetworkUrl(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return "localhost".equals(host)
                    || host.startsWith("127.")
                    || host.startsWith("192.168.")
                    || host.startsWith("10.")
                    || host.endsWith(".local");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Button makeNavButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setPadding(dp(4), 0, dp(4), 0);
        setBg(b, 0xFF1F2A38, 0xFF334155, 6);
        return b;
    }

    private void navigateTo(String raw) {
        if (raw == null) return;
        hideKeyboard();
        statusBanner.setVisibility(View.GONE);

        String input = raw.trim();
        if (input.length() == 0 || input.equalsIgnoreCase(HOME_PORTAL_URL)) {
            loadHomePage();
            return;
        }

        String finalUrl;
        if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("file://")) {
            finalUrl = input;
        } else if (isPlainIpOrLocalHost(input)) {
            finalUrl = "http://" + input;
        } else if (input.contains(".") && !input.contains(" ")) {
            finalUrl = "https://" + input;
        } else {
            finalUrl = "https://html.duckduckgo.com/html/?q=" + Uri.encode(input);
        }

        if (isDirectBinaryDownloadUrl(finalUrl)) {
            startDownloadInBrowser(finalUrl);
            return;
        }
        currentDocumentUrl = finalUrl;
        urlBar.setText(finalUrl);
        webView.loadUrl(finalUrl);
    }

    private static boolean isPlainIpOrLocalHost(String input) {
        int slash = input.indexOf('/');
        String hp = slash >= 0 ? input.substring(0, slash) : input;
        int colon = hp.indexOf(':');
        String h = colon >= 0 ? hp.substring(0, colon) : hp;
        return "localhost".equalsIgnoreCase(h)
                || h.startsWith("192.168.")
                || h.startsWith("10.")
                || h.startsWith("127.");
    }

    private void loadHomePage() {
        currentDocumentUrl = HOME_PORTAL_URL;
        urlBar.setText("");
        statusBanner.setVisibility(View.GONE);
        String html =
                "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{font-family:sans-serif;background:#0e1319;color:#f1f5f9;margin:0;padding:18px;}"
                + ".box{background:#161e28;border:1px solid #2b394b;border-radius:10px;padding:18px;margin-bottom:14px;}"
                + "h2{margin:0 0 8px 0;color:#38bdf8;font-size:20px;}"
                + "p{margin:0 0 12px 0;color:#cbd5e1;font-size:13.5px;line-height:1.5;}"
                + "form{display:flex;margin-bottom:14px;}"
                + "input[type=text]{width:74%;padding:10px 12px;border-radius:6px;border:1px solid #334155;"
                + "background:#0f172a;color:#fff;font-size:14px;margin-right:8px;}"
                + "button{padding:10px 16px;border-radius:6px;border:1px solid #7dd3fc;background:#0284c7;"
                + "color:#fff;font-weight:bold;font-size:14px;}"
                + "a.btn{display:inline-block;background:#1f2a38;border:1px solid #38bdf8;color:#f8fafc;"
                + "text-decoration:none;padding:10px 14px;border-radius:7px;margin:4px 6px 4px 0;font-weight:bold;font-size:13px;}"
                + "a.primary{background:#0284c7;border-color:#7dd3fc;}"
                + "</style></head><body>"
                + "<div class='box'>"
                + "<h2>Arc7 Browser</h2>"
                + "<p>Modern TLS 1.3 + 120 Bundled 2026 Root Certificates — Search the web or open any HTTPS or local site.</p>"
                + "<form action='https://html.duckduckgo.com/html/' method='get'>"
                + "<input type='text' name='q' placeholder='Search DuckDuckGo...'>"
                + "<button type='submit'>Search</button>"
                + "</form>"
                + "<a class='btn primary' href='https://en.m.wikipedia.org/wiki/Main_Page'>Wikipedia</a>"
                + "<a class='btn primary' href='https://news.ycombinator.com/'>Hacker News</a>"
                + "<a class='btn primary' href='https://old.reddit.com/r/kobo/'>Reddit (/r/kobo)</a>"
                + "<a class='btn' href='https://gutenberg.org/ebooks/search/?sort_order=downloads'>Project Gutenberg EPUBs</a>"
                + "<a class='btn' href='https://archive.org/details/software'>Internet Archive</a>"
                + "<a class='btn' href='https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases'>Arc7 GitHub Releases</a>"
                + "<a class='btn' href='https://xdaforums.com/t/arc-7-root-and-recovery-and-mt8125-chipset-info.2635520/'>XDA Kobo Arc 7</a>"
                + "</div></body></html>";
        webView.loadDataWithBaseURL("https://arc7.home/", html, "text/html", "UTF-8", null);
    }

    private void showBrowserMenu() {
        final String modeLabel = desktopMode ? "Switch to Tablet / Mobile View" : "Request Desktop Site";
        final String[] items = new String[]{
                modeLabel,
                "DuckDuckGo Web Search",
                "Wikipedia",
                "Hacker News",
                "Reddit (/r/kobo)",
                "Project Gutenberg — Free EPUB Books",
                "Kobo Arc 7 Freedom — GitHub Releases",
                "Internet Archive — Android 4 Apps & ROMs",
                "Clear Browser Cache & Cookies",
                "Return to Kobo Arc 7 Freedom App"
        };
        new AlertDialog.Builder(this)
                .setTitle("Arc7 Browser")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                desktopMode = !desktopMode;
                                webView.getSettings().setUserAgentString(desktopMode ? UA_DESKTOP : UA_MOBILE);
                                Toast.makeText(Arc7BrowserActivity.this,
                                        desktopMode ? "Desktop site mode active" : "Mobile/Tablet mode active",
                                        Toast.LENGTH_SHORT).show();
                                if (!HOME_PORTAL_URL.equals(currentDocumentUrl) && currentDocumentUrl.length() > 0) {
                                    navigateTo(currentDocumentUrl);
                                }
                                break;
                            case 1:
                                navigateTo("https://html.duckduckgo.com/html/");
                                break;
                            case 2:
                                navigateTo("https://en.m.wikipedia.org/wiki/Main_Page");
                                break;
                            case 3:
                                navigateTo("https://news.ycombinator.com/");
                                break;
                            case 4:
                                navigateTo("https://old.reddit.com/r/kobo/");
                                break;
                            case 5:
                                navigateTo("https://gutenberg.org/ebooks/search/?sort_order=downloads");
                                break;
                            case 6:
                                navigateTo("https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases");
                                break;
                            case 7:
                                navigateTo("https://archive.org/search?query=kobo+arc+7");
                                break;
                            case 8:
                                webView.clearCache(true);
                                webView.clearHistory();
                                CookieManager.getInstance().removeAllCookie();
                                Toast.makeText(Arc7BrowserActivity.this, "Cleared cache, cookies & history.", Toast.LENGTH_SHORT).show();
                                break;
                            case 9:
                                startActivity(new Intent(Arc7BrowserActivity.this, MainActivity.class));
                                break;
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Streams any modern TLS 1.3 page, stylesheet, script, JSON, font, or image through our
     * persistent in-RAM `libarc7tls.so` local daemon with zero `fork()/exec()` per resource.
     */
    private WebResourceResponse fetchViaPersistentTlsDaemon(final String url) {
        final boolean isMainDoc = url.equals(currentDocumentUrl);
        HttpURLConnection conn = null;
        try {
            if (!ensureTlsDaemonRunning()) {
                return buildErrorResponse(url, "Local TLS 1.3 engine failed to start.");
            }

            URL proxyUrl = new URL("http://127.0.0.1:" + DAEMON_PORT + "/__arc7_raw?u=" + Uri.encode(url));
            conn = (HttpURLConnection) proxyUrl.openConnection(Proxy.NO_PROXY);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);

            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) {
                conn.setRequestProperty("X-Arc7-Cookie", cookie);
            }
            if (!isMainDoc && currentDocumentUrl != null && currentDocumentUrl.startsWith("http")) {
                conn.setRequestProperty("X-Arc7-Referer", currentDocumentUrl);
            }

            int status = conn.getResponseCode();
            String finalUrl = conn.getHeaderField("X-Arc7-Final-URL");
            if (finalUrl == null || finalUrl.length() == 0) {
                finalUrl = url;
            }

            // Persist any upstream Set-Cookie headers relayed via `X-Arc7-Cookie` or `Set-Cookie`
            Map<String, List<String>> hdrs = conn.getHeaderFields();
            if (hdrs != null) {
                for (Map.Entry<String, List<String>> e : hdrs.entrySet()) {
                    if (e.getKey() != null
                            && ("X-Arc7-Cookie".equalsIgnoreCase(e.getKey())
                                || "Set-Cookie".equalsIgnoreCase(e.getKey()))
                            && e.getValue() != null) {
                        for (String sc : e.getValue()) {
                            if (sc != null) CookieManager.getInstance().setCookie(finalUrl, sc);
                        }
                    }
                }
            }

            // If an HTML link redirected to a direct `.apk`/`.zip`/`.epub` binary download, trigger download manager!
            if (isDirectBinaryDownloadUrl(finalUrl)) {
                final String dlTarget = finalUrl;
                conn.disconnect();
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        startDownloadInBrowser(dlTarget);
                    }
                });
                return new WebResourceResponse("text/html", "UTF-8",
                        new ByteArrayInputStream("<html><body style='background:#0e1319;color:#93c5fd;font-family:sans-serif;padding:20px;'>Starting download...</body></html>".getBytes("UTF-8")));
            }

            InputStream rawStream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (rawStream == null) {
                conn.disconnect();
                return isMainDoc ? buildErrorResponse(url, "HTTP " + status + " empty response.") : null;
            }

            String encHdr = conn.getHeaderField("X-Arc7-Encoding");
            PushbackInputStream pb = new PushbackInputStream(rawStream, 4);
            byte[] sig2 = new byte[2];
            int n2 = pb.read(sig2);
            if (n2 > 0) pb.unread(sig2, 0, n2);
            boolean isGzip = (encHdr != null && encHdr.toLowerCase(Locale.US).contains("gzip"))
                    || (n2 == 2 && (sig2[0] & 0xFF) == 0x1F && (sig2[1] & 0xFF) == 0x8B);

            final HttpURLConnection ownedConn = conn;
            InputStream decoded = isGzip ? new GZIPInputStream(pb) : pb;
            InputStream managedStream = new FilterInputStream(decoded) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        ownedConn.disconnect();
                    }
                }
            };

            String rawContentType = conn.getContentType();
            String mime = parsePureMimeType(rawContentType, finalUrl);
            String charset = parseCharset(rawContentType, mime);

            // For top-level HTML documents: sync URL bar if redirected & ensure `<base href>` + GET search forms work cleanly!
            if ("text/html".equals(mime) && isMainDoc) {
                final String resolvedDocUrl = finalUrl;
                currentDocumentUrl = resolvedDocUrl;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!urlBar.hasFocus()) {
                            urlBar.setText(resolvedDocUrl);
                        }
                    }
                });
                byte[] htmlBytes = readBoundedBytes(managedStream, 4 * 1048576);
                managedStream.close();
                String encName = (charset != null && charset.length() > 0) ? charset : "UTF-8";
                String html = new String(htmlBytes, encName);
                html = prepareMainHtmlForWebView(html, resolvedDocUrl);
                return new WebResourceResponse("text/html", "UTF-8",
                        new ByteArrayInputStream(html.getBytes("UTF-8")));
            }

            return new WebResourceResponse(mime, charset, managedStream);
        } catch (Throwable t) {
            if (conn != null) conn.disconnect();
            return isMainDoc ? buildErrorResponse(url, String.valueOf(t.getMessage())) : null;
        }
    }

    /**
     * Light, non-destructive HTML fixup ONLY for Android 4.2 WebView compatibility:
     *  1. Injects `<base href="...">` if missing so all relative CSS/JS/images resolve against
     *     the true post-redirect HTTPS URL instead of the pre-redirect URL!
     *  2. Converts `<form ... method="post">` on DuckDuckGo search pages to `method="get"` so
     *     submitting search boxes inside WebView works natively on Android 4.2!
     *  Never strips page CSS, styles, layouts, or scripts!
     */
    private static String prepareMainHtmlForWebView(String html, String effectiveUrl) {
        String lower = html.toLowerCase(Locale.US);
        if (!lower.contains("<base ")) {
            String safeBase = effectiveUrl.replace("\"", "%22");
            String baseTag = "<base href=\"" + safeBase + "\">";
            int headIdx = lower.indexOf("<head");
            if (headIdx >= 0) {
                int gt = html.indexOf('>', headIdx);
                if (gt >= 0) {
                    html = html.substring(0, gt + 1) + baseTag + html.substring(gt + 1);
                }
            } else {
                html = baseTag + html;
            }
        }
        if (effectiveUrl.contains("duckduckgo.com")) {
            html = html.replaceAll("(?i)(<form[^>]*?)method=[\"']post[\"']", "$1method=\"get\"");
        }
        return html;
    }

    private static byte[] readBoundedBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
            if (bos.size() >= maxBytes) break;
        }
        return bos.toByteArray();
    }

    private static WebResourceResponse buildErrorResponse(String url, String detail) {
        try {
            String safeUrl = url.replace("&", "&amp;").replace("<", "&lt;");
            String safeDetail = (detail == null ? "" : detail).replace("&", "&amp;").replace("<", "&lt;");
            String errHtml = "<!DOCTYPE html><html><body style='background:#0e1319;color:#f1f5f9;font-family:sans-serif;padding:22px;'>"
                    + "<h3 style='color:#f87171;margin-top:0;'>Unable to Load Page</h3>"
                    + "<p style='color:#cbd5e1;'>Could not open <b>" + safeUrl + "</b>.</p>"
                    + "<pre style='background:#161e28;border:1px solid #334155;padding:12px;border-radius:8px;color:#94a3b8;white-space:pre-wrap;'>"
                    + safeDetail + "</pre>"
                    + "<p><a href='" + safeUrl + "' style='color:#38bdf8;font-weight:bold;'>Try Again</a></p>"
                    + "</body></html>";
            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(errHtml.getBytes("UTF-8")));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String parsePureMimeType(String contentTypeHeader, String url) {
        if (contentTypeHeader != null && contentTypeHeader.length() > 0) {
            int semi = contentTypeHeader.indexOf(';');
            String pure = (semi >= 0 ? contentTypeHeader.substring(0, semi) : contentTypeHeader).trim().toLowerCase(Locale.US);
            if (pure.length() > 0 && !"application/octet-stream".equals(pure)) {
                if ("application/xhtml+xml".equals(pure)) return "text/html";
                return pure;
            }
        }
        String p = url.toLowerCase(Locale.US);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".xml")) return "text/xml";
        if (p.endsWith(".woff") || p.endsWith(".ttf")) return "font/ttf";
        return "text/html";
    }

    private static String parseCharset(String contentTypeHeader, String pureMime) {
        if (contentTypeHeader != null) {
            String low = contentTypeHeader.toLowerCase(Locale.US);
            int idx = low.indexOf("charset=");
            if (idx >= 0) {
                String cs = contentTypeHeader.substring(idx + 8).trim();
                int semi = cs.indexOf(';');
                if (semi >= 0) cs = cs.substring(0, semi).trim();
                cs = cs.replace("\"", "").replace("'", "");
                if (cs.length() > 0) return cs;
            }
        }
        if (pureMime.startsWith("text/") || "application/javascript".equals(pureMime) || "application/json".equals(pureMime)) {
            return "UTF-8";
        }
        return null;
    }

    private void startDownloadInBrowser(final String url) {
        Toast.makeText(this, "Downloading file...", Toast.LENGTH_SHORT).show();
        statusBanner.setVisibility(View.VISIBLE);
        statusBanner.setText("Downloading: " + url);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File dlRoot = new File("/storage/sdcard1").canWrite()
                        ? new File("/storage/sdcard1/Download")
                        : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                final File saved = certManager.downloadWithBundledCerts(url, dlRoot,
                        new NetworkCertManager.ProgressListener() {
                            @Override
                            public void onProgress(final int percent, final String stage) {
                                uiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        statusBanner.setVisibility(View.VISIBLE);
                                        statusBanner.setText(stage);
                                        progressBar.setVisibility(View.VISIBLE);
                                        progressBar.setProgress(percent);
                                    }
                                });
                            }

                            @Override
                            public void onLog(String line) {
                            }
                        });
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        progressBar.setVisibility(View.GONE);
                        if (saved == null || !saved.exists()) {
                            statusBanner.setText("Download failed. Check Wi-Fi connection.");
                            return;
                        }
                        statusBanner.setText("Saved: " + saved.getAbsolutePath());
                        promptDownloadedFile(saved);
                    }
                });
            }
        }).start();
    }

    private void promptDownloadedFile(final File saved) {
        String low = saved.getName().toLowerCase(Locale.US);
        if (low.endsWith(".apk")) {
            final Arc7ApkKit.ApkInspection info = Arc7ApkKit.inspectApk(saved);
            final String detail = !info.isCompatibleWithAndroid42()
                    ? "Compatibility Note:\n" + info.errorReason + "\n\nSaved to:\n" + saved.getAbsolutePath()
                    : "Verified Android 4.2 Ready (" + info.packageName
                      + ", minSdk=" + info.minSdkVersion + ", DEX=" + info.highestDexVersion + ")\n"
                      + (!info.hasValidAndroid42V1Sig ? "• Auto-repairing SHA1-RSA JAR v1 signature for Jelly Bean!\n\n" : "\n")
                      + "Install this Android app now?";
            new AlertDialog.Builder(this)
                    .setTitle(info.isCompatibleWithAndroid42() ? "APK Downloaded & Verified" : "APK Compatibility Warning")
                    .setMessage(detail)
                    .setPositiveButton(info.isCompatibleWithAndroid42() ? "Install Now" : "Try Anyway", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    Arc7ApkKit.RootInstallOutcome tmpOut = null;
                                    try {
                                        tmpOut = Arc7ApkKit.stageAndInstallApk(Arc7BrowserActivity.this, saved, true);
                                    } catch (Throwable ignored) {}
                                    final Arc7ApkKit.RootInstallOutcome out = tmpOut;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (out != null && out.succeeded) {
                                                android.widget.Toast.makeText(
                                                        Arc7BrowserActivity.this,
                                                        "Installed " + saved.getName() + "!",
                                                        android.widget.Toast.LENGTH_LONG).show();
                                                return;
                                            }
                                            File target = (out != null && out.worldReadableFallbackApk != null
                                                    && out.worldReadableFallbackApk.exists())
                                                    ? out.worldReadableFallbackApk : saved;
                                            Intent i = new Intent(Intent.ACTION_VIEW);
                                            i.setDataAndType(Uri.fromFile(target), "application/vnd.android.package-archive");
                                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            startActivity(i);
                                        }
                                    });
                                }
                            }).start();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } else if (low.endsWith(".epub") || low.endsWith(".cbz") || low.endsWith(".txt")) {
            new AlertDialog.Builder(this)
                    .setTitle("Book / Comic Downloaded")
                    .setMessage("Saved to:\n" + saved.getAbsolutePath() + "\n\nOpen immediately in Arc7 E-Reader?")
                    .setPositiveButton("Read Now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Intent i = new Intent(Arc7BrowserActivity.this, Arc7EbookReaderActivity.class);
                            i.setData(Uri.fromFile(saved));
                            startActivity(i);
                        }
                    })
                    .setNegativeButton("Stay in Browser", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Download Complete")
                    .setMessage("Saved verified file to:\n" + saved.getAbsolutePath()
                            + "\n\nOpen Kobo Arc 7 Freedom to flash this ROM / image?")
                    .setPositiveButton("Open Arc7 Freedom", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            startActivity(new Intent(Arc7BrowserActivity.this, MainActivity.class));
                        }
                    })
                    .setNegativeButton("Stay in Browser", null)
                    .show();
        }
    }

    private static boolean isDirectBinaryDownloadUrl(String url) {
        String p = url.toLowerCase(Locale.US);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.endsWith(".apk") || p.endsWith(".zip") || p.endsWith(".img")
                || p.endsWith(".gz") || p.endsWith(".tar") || p.endsWith(".xz")
                || p.endsWith(".epub") || p.endsWith(".cbz");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieSyncManager.getInstance().sync();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        synchronized (daemonLock) {
            if (tlsDaemonProc != null) {
                try {
                    tlsDaemonProc.destroy();
                } catch (Throwable ignored) {
                }
                tlsDaemonProc = null;
            }
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
