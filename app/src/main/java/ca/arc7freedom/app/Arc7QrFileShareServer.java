package ca.arc7freedom.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived, bearer-token protected local HTTP file share. The server has no Android
 * dependency so its real request handling can be exercised by the host regression suite.
 * It intentionally shares only the caller-selected directory, not the whole filesystem.
 */
public final class Arc7QrFileShareServer {
    public static final int DEFAULT_PORT = 8091;
    public static final long MAX_UPLOAD_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final int MAX_HEADER_BYTES = 24576;
    private static final String COOKIE_NAME = "arc7share";

    private final File root;
    private final String token;
    private final int[] ports;
    private final Object fileLock = new Object();
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean running;
    private volatile ServerSocket listener;
    private volatile ThreadPoolExecutor workers;
    private volatile int activePort;

    public Arc7QrFileShareServer(File root, String token) throws IOException {
        this(root, token, new int[]{DEFAULT_PORT, DEFAULT_PORT + 1, DEFAULT_PORT + 2});
    }

    /** The port-list overload also lets offline tests request an OS-assigned port with {0}. */
    public Arc7QrFileShareServer(File root, String token, int[] ports) throws IOException {
        if (root == null || token == null || !validToken(token) || ports == null || ports.length == 0)
            throw new IOException("Invalid file-share configuration");
        File canonical = root.getCanonicalFile();
        if (!canonical.exists() || !canonical.isDirectory()) throw new IOException("Choose an existing folder to share");
        this.root = canonical;
        this.token = token;
        this.ports = ports.clone();
    }

    public synchronized void start() throws IOException {
        if (running) return;
        IOException last = null;
        for (int port : ports) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 24);
                listener = candidate;
                activePort = candidate.getLocalPort();
                workers = new ThreadPoolExecutor(2, 4, 30L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<Runnable>(12));
                workers.allowCoreThreadTimeOut(true);
                running = true;
                Thread accept = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "Arc7-QR-Share-Accept");
                accept.setDaemon(true);
                accept.start();
                return;
            } catch (IOException e) {
                last = e;
                if (candidate != null) try { candidate.close(); } catch (Exception ignored) {}
            }
        }
        throw last != null ? last : new IOException("Could not open a local file-share port");
    }

    public int getPort() { return activePort; }
    public boolean isRunning() { return running && listener != null && !listener.isClosed(); }

    public void stop() {
        running = false;
        ServerSocket socket = listener;
        listener = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        synchronized (clients) {
            for (Socket client : clients) try { client.close(); } catch (Exception ignored) {}
            clients.clear();
        }
        ThreadPoolExecutor pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
        activePort = 0;
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = listener;
            if (socket == null) break;
            try {
                final Socket client = socket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(120000);
                clients.add(client);
                ThreadPoolExecutor pool = workers;
                if (pool == null) { close(client); continue; }
                try {
                    pool.execute(new Runnable() { public void run() { handle(client); } });
                } catch (RejectedExecutionException full) {
                    try { writeText(new BufferedOutputStream(client.getOutputStream()), "503 Service Unavailable",
                            "Too many active file-share requests.", false, null); } catch (Exception ignored) {}
                    close(client);
                }
            } catch (IOException e) {
                if (running) continue;
                break;
            }
        }
    }

    private void handle(Socket socket) {
        boolean responseStarted = false;
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream(), 16384);
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 16384);
            Request request = readRequest(input);
            if (request == null) return;
            if (!"GET".equals(request.method) && !"HEAD".equals(request.method) && !"POST".equals(request.method)) {
                writeText(output, "405 Method Not Allowed", "Only GET, HEAD and POST are supported.",
                        "HEAD".equals(request.method), null); return;
            }

            String queryToken = queryParam(request.query, "t");
            boolean tokenInQuery = secureEquals(queryToken, token);
            boolean tokenInCookie = cookieAuthorized(request.cookie);
            if (!tokenInQuery && !tokenInCookie) {
                writeText(output, "401 Unauthorized", "Open the QR link shown on the Kobo.",
                        "HEAD".equals(request.method), null); return;
            }
            String setCookie = tokenInQuery ? "exchange" : null;
            if ("/".equals(request.path) && tokenInQuery) {
                writeRedirect(output, "/" + (request.query.length() == 0 || queryParam(request.query, "path").length() == 0
                        ? "" : "?path=" + encode(queryParam(request.query, "path"))), setCookie);
                return;
            }
            if ("POST".equals(request.method)
                    && (!sameOrigin(request.origin, request.host) || !secureEquals(request.shareHeader, token))) {
                writeText(output, "403 Forbidden", "The upload session could not be verified.", false, setCookie); return;
            }
            if ("/".equals(request.path) && ("GET".equals(request.method) || "HEAD".equals(request.method))) {
                File directory = resolveDirectory(queryParam(request.query, "path"));
                if (directory == null) {
                    writeText(output, "403 Forbidden", "That folder is outside the shared directory.",
                            "HEAD".equals(request.method), setCookie); return;
                }
                if (!directory.exists() || !directory.isDirectory()) {
                    writeText(output, "404 Not Found", "Folder not found.", "HEAD".equals(request.method), setCookie); return;
                }
                byte[] page = renderDirectory(directory).getBytes("UTF-8");
                writeBytes(output, "200 OK", "text/html; charset=utf-8", page, "HEAD".equals(request.method), setCookie, null);
                return;
            }
            if ("/download".equals(request.path) && ("GET".equals(request.method) || "HEAD".equals(request.method))) {
                File file = resolveFile(queryParam(request.query, "path"));
                if (file == null) {
                    writeText(output, "403 Forbidden", "That file is outside the shared directory.",
                            "HEAD".equals(request.method), setCookie); return;
                }
                if (!file.exists() || !file.isFile() || !file.canRead()) {
                    writeText(output, "404 Not Found", "File not found.", "HEAD".equals(request.method), setCookie); return;
                }
                streamFile(output, file, request.range, "HEAD".equals(request.method), setCookie);
                return;
            }
            if ("/upload".equals(request.path) && "POST".equals(request.method)) {
                if (request.transferEncoding.length() > 0) {
                    writeText(output, "411 Length Required", "The upload must include a Content-Length header.", false, setCookie); return;
                }
                if (request.contentLength < 0) {
                    writeText(output, "411 Length Required", "The upload must include a Content-Length header.", false, setCookie); return;
                }
                if (request.contentLength > MAX_UPLOAD_BYTES) {
                    writeText(output, "413 Payload Too Large", "The upload exceeds the 4 GiB limit.", false, setCookie); return;
                }
                File directory = resolveDirectory(queryParam(request.query, "path"));
                if (directory == null) {
                    writeText(output, "403 Forbidden", "That folder is outside the shared directory.", false, setCookie); return;
                }
                if (!directory.exists() || !directory.isDirectory() || !directory.canWrite()) {
                    writeText(output, "403 Forbidden", "The selected folder is not writable.", false, setCookie); return;
                }
                String name = safeFilename(queryParam(request.query, "name"));
                if (name == null) {
                    writeText(output, "400 Bad Request", "Choose a valid file name.", false, setCookie); return;
                }
                if (request.expect.toLowerCase(Locale.US).contains("100-continue")) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("US-ASCII")); output.flush();
                }
                File saved = receiveUpload(input, directory, name, request.contentLength);
                String message = "Uploaded " + saved.getName() + " (" + saved.length() + " bytes).";
                writeText(output, "201 Created", message, false, setCookie);
                return;
            }
            writeText(output, "404 Not Found", "Page not found.", "HEAD".equals(request.method), setCookie);
        } catch (ClientInputException invalid) {
            try { writeText(new BufferedOutputStream(socket.getOutputStream()), invalid.status,
                    invalid.getMessage(), false, null); } catch (Exception ignored) {}
        } catch (Exception failure) {
            if (running) try { writeText(new BufferedOutputStream(socket.getOutputStream()), "500 Internal Server Error",
                    "The file operation failed; existing files were not replaced.", false, null); } catch (Exception ignored) {}
        } finally {
            clients.remove(socket);
            close(socket);
        }
    }

    private File receiveUpload(InputStream input, File directory, String name, long length) throws Exception {
        File temporary = File.createTempFile(".arc7-share-", ".part", directory);
        boolean moved = false;
        try {
            FileOutputStream file = new FileOutputStream(temporary);
            try {
                byte[] block = new byte[65536];
                long remaining = length;
                while (remaining > 0) {
                    if (!running || Thread.currentThread().isInterrupted()) throw new IOException("Share stopped");
                    int count = input.read(block, 0, (int) Math.min((long) block.length, remaining));
                    if (count < 0) throw new IOException("Upload ended before all bytes arrived");
                    if (count == 0) continue;
                    file.write(block, 0, count);
                    remaining -= count;
                }
                file.flush(); file.getFD().sync();
                java.util.Arrays.fill(block, (byte) 0);
            } finally { file.close(); }
            synchronized (fileLock) {
                File destination = uniqueDestination(directory, name);
                if (!temporary.renameTo(destination)) throw new IOException("Could not finish upload");
                moved = true;
                return destination;
            }
        } finally {
            if (!moved) temporary.delete();
        }
    }

    private File uniqueDestination(File directory, String name) throws IOException {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 9999; i++) {
            candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("Too many files with that name");
    }

    private String renderDirectory(File directory) throws IOException {
        String relative = relativePath(directory);
        String display = relative.length() == 0 ? root.getName() : relative;
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>Kobo file share</title><style>")
            .append("body{margin:0;background:#0b1220;color:#e5edf8;font:16px system-ui,-apple-system,sans-serif}")
            .append("main{max-width:900px;margin:24px auto;padding:0 16px}.card{background:#131e30;border:1px solid #26354c;border-radius:16px;padding:20px;margin:14px 0}")
            .append("h1{font-size:24px;margin:0 0 6px}.muted{color:#9eb0c8;font-size:14px}a{color:#7dd3fc;text-decoration:none;word-break:break-word}")
            .append(".row{display:flex;justify-content:space-between;gap:14px;padding:12px 2px;border-bottom:1px solid #26354c}.row:last-child{border:0}")
            .append("button{background:#1675d1;border:0;border-radius:10px;color:white;font:inherit;font-weight:600;padding:12px 16px;margin:8px 8px 0 0}input{max-width:100%;padding:8px}")
            .append("progress{width:100%;height:18px}code{overflow-wrap:anywhere}</style></head><body><main>")
            .append("<section class=\"card\"><h1>Kobo file share</h1><div class=\"muted\">Shared folder: <code>")
            .append(html(display)).append("</code></div><p>Download files from the Kobo or upload files into this folder. Existing files are never overwritten; duplicate names receive a number.</p>")
            .append("<input id=\"files\" type=\"file\" multiple><br><button type=\"button\" onclick=\"uploadFiles()\">Upload selected files</button>")
            .append("<progress id=\"progress\" max=100 value=0></progress><div id=\"status\" class=\"muted\">Only this folder is shared.</div>")
            .append("<input id=\"currentPath\" type=\"hidden\" value=\"").append(html(relative)).append("\"></section>")
            .append("<section class=\"card\"><strong>Files and folders</strong>");
        if (relative.length() > 0) {
            String parent = parentPath(relative);
            html.append("<div class=\"row\"><a href=\"/?path=").append(encode(parent)).append("\">⬆ Parent folder</a><span class=\"muted\">Folder</span></div>");
        }
        File[] children = directory.listFiles();
        if (children != null) {
            List<File> files = new ArrayList<File>();
            for (File child : children) {
                if (child == null || child.getName().startsWith(".arc7-share-") || !child.canRead() || !insideRoot(child)) continue;
                files.add(child);
            }
            Collections.sort(files, new Comparator<File>() {
                public int compare(File left, File right) {
                    if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            for (File child : files) {
                String childPath = relative.length() == 0 ? child.getName() : relative + "/" + child.getName();
                String href = child.isDirectory() ? "/?path=" + encode(childPath) : "/download?path=" + encode(childPath);
                html.append("<div class=\"row\"><a href=\"").append(href).append("\">")
                    .append(child.isDirectory() ? "📁 " : "📄 ").append(html(child.getName())).append("</a><span class=\"muted\">")
                    .append(child.isDirectory() ? "Folder" : formatSize(child.length())).append("</span></div>");
            }
        }
        html.append("</section><section class=\"card muted\">The link is a temporary access key for anyone who has it. Use only on a trusted local network; HTTP is not encrypted. Stop sharing on the Kobo when finished.</section>")
            .append("</main><script>var shareKey='").append(token).append("';async function uploadFiles(){var input=document.getElementById('files'),files=input.files;if(!files.length)return;var path=document.getElementById('currentPath').value,bar=document.getElementById('progress'),status=document.getElementById('status');for(var i=0;i<files.length;i++){var f=files[i];status.textContent='Uploading '+f.name+' ('+(i+1)+'/'+files.length+')';try{var response=await fetch('/upload?path='+encodeURIComponent(path)+'&name='+encodeURIComponent(f.name),{method:'POST',headers:{'X-Arc7-Share':shareKey},body:f});if(!response.ok)throw new Error(await response.text());bar.value=Math.round((i+1)*100/files.length);}catch(error){status.textContent='Upload stopped: '+error.message;return;}}status.textContent='Upload complete. Refresh the page to see new files.';input.value='';}</script></body></html>");
        return html.toString();
    }

    private void streamFile(OutputStream output, File file, String rangeHeader, boolean headOnly, String setCookie) throws Exception {
        long size = file.length();
        long start = 0, end = size - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.length() > 0) {
            long[] range = parseRange(rangeHeader, size);
            if (range == null) {
                writeBytes(output, "416 Range Not Satisfiable", "text/plain; charset=utf-8",
                        "Invalid byte range.".getBytes("UTF-8"), headOnly, setCookie, "Content-Range: bytes */" + size + "\r\n");
                return;
            }
            start = range[0]; end = range[1]; partial = true;
        }
        long count = size == 0 ? 0 : end - start + 1;
        String extra = "Accept-Ranges: bytes\r\n";
        if (partial) extra += "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n";
        writeHeader(output, partial ? "206 Partial Content" : "200 OK", "application/octet-stream", count,
                setCookie, extra + "Content-Disposition: attachment; filename=\"" + asciiFilename(file.getName())
                        + "\"; filename*=UTF-8''" + encode(file.getName()) + "\r\n");
        if (!headOnly && count > 0) {
            FileInputStream input = new FileInputStream(file);
            try {
                skipFully(input, start);
                byte[] block = new byte[65536];
                long remaining = count;
                while (remaining > 0) {
                    int n = input.read(block, 0, (int) Math.min((long) block.length, remaining));
                    if (n < 0) break;
                    output.write(block, 0, n); remaining -= n;
                }
            } finally { input.close(); }
        }
        output.flush();
    }

    private File resolveDirectory(String relative) {
        File file = resolve(relative);
        return file != null && file.isDirectory() ? file : file;
    }

    private File resolveFile(String relative) { return resolve(relative); }

    private File resolve(String relative) {
        try {
            if (relative == null) relative = "";
            if (relative.indexOf('\u0000') >= 0 || relative.indexOf('\\') >= 0) return null;
            File candidate = new File(root, relative).getCanonicalFile();
            String rootPath = root.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            if (path.equals(rootPath) || path.startsWith(rootPath + File.separator)) return candidate;
        } catch (Exception ignored) {}
        return null;
    }

    private String relativePath(File file) throws IOException {
        String base = root.getCanonicalPath(), path = file.getCanonicalPath();
        if (path.equals(base)) return "";
        if (!path.startsWith(base + File.separator)) throw new IOException("Outside shared folder");
        return path.substring(base.length() + 1).replace(File.separatorChar, '/');
    }

    private boolean insideRoot(File file) {
        try {
            String base = root.getCanonicalPath(), path = file.getCanonicalPath();
            return path.equals(base) || path.startsWith(base + File.separator);
        } catch (Exception invalid) { return false; }
    }

    private static Request readRequest(InputStream input) throws Exception {
        String requestLine = readLine(input, 8192);
        if (requestLine == null || requestLine.length() == 0) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || parts.length > 3) throw new ClientInputException("400 Bad Request", "Invalid request line.");
        Request request = new Request();
        request.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        if (!target.startsWith("/") || target.startsWith("//")) throw new ClientInputException("400 Bad Request", "Invalid request path.");
        int q = target.indexOf('?');
        request.path = q >= 0 ? target.substring(0, q) : target;
        request.query = q >= 0 ? target.substring(q + 1) : "";
        int headerBytes = requestLine.length();
        String line;
        while ((line = readLine(input, 8192)) != null && line.length() > 0) {
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) throw new ClientInputException("431 Request Header Fields Too Large", "Request headers are too large.");
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("content-length".equals(key)) {
                try { request.contentLength = Long.parseLong(value); }
                catch (Exception invalid) { throw new ClientInputException("400 Bad Request", "Invalid Content-Length."); }
            } else if ("transfer-encoding".equals(key)) request.transferEncoding = value;
            else if ("cookie".equals(key)) request.cookie = value;
            else if ("origin".equals(key)) request.origin = value;
            else if ("host".equals(key)) request.host = value;
            else if ("range".equals(key)) request.range = value;
            else if ("expect".equals(key)) request.expect = value;
            else if ("x-arc7-share".equals(key)) request.shareHeader = value;
        }
        return request;
    }

    private static String readLine(InputStream input, int maximum) throws IOException, ClientInputException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bytes.write(c);
            if (bytes.size() > maximum) throw new ClientInputException("431 Request Header Fields Too Large", "Request line is too long.");
        }
        if (c == -1 && bytes.size() == 0) return null;
        return bytes.toString("ISO-8859-1");
    }

    private boolean cookieAuthorized(String cookieHeader) {
        if (cookieHeader == null) return false;
        for (String item : cookieHeader.split(";")) {
            int equal = item.indexOf('=');
            if (equal > 0 && COOKIE_NAME.equals(item.substring(0, equal).trim()))
                return secureEquals(item.substring(equal + 1).trim(), token);
        }
        return false;
    }

    private static boolean validToken(String value) {
        if (value.length() != 48) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static boolean secureEquals(String left, String right) {
        if (left == null || right == null) return false;
        int difference = left.length() ^ right.length();
        int count = Math.max(left.length(), right.length());
        for (int i = 0; i < count; i++) difference |= (i < left.length() ? left.charAt(i) : 0) ^ (i < right.length() ? right.charAt(i) : 0);
        return difference == 0;
    }

    private String cookieValue() { return COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict"; }

    private void writeText(OutputStream output, String status, String message, boolean headOnly, String setCookie) throws IOException {
        writeBytes(output, status, "text/plain; charset=utf-8", message.getBytes("UTF-8"), headOnly, setCookie, null);
    }

    private void writeRedirect(OutputStream output, String location, String setCookie) throws IOException {
        String headers = "HTTP/1.1 303 See Other\r\nLocation: " + location + "\r\nCache-Control: no-store\r\n";
        if (setCookie != null) headers += "Set-Cookie: " + cookieValue() + "\r\n";
        output.write((headers + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        output.flush();
    }

    private void writeBytes(OutputStream output, String status, String type, byte[] body, boolean headOnly,
                            String setCookie, String extraHeaders) throws IOException {
        writeHeader(output, status, type, body.length, setCookie, extraHeaders);
        if (!headOnly && body.length > 0) output.write(body);
        output.flush();
    }

    private void writeHeader(OutputStream output, String status, String type, long length,
                             String setCookie, String extraHeaders) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append("\r\nContent-Type: ").append(type)
                .append("\r\nContent-Length: ").append(length)
                .append("\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n")
                .append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'\r\n");
        if (setCookie != null) header.append("Set-Cookie: ").append(cookieValue()).append("\r\n");
        if (extraHeaders != null) header.append(extraHeaders);
        header.append("Connection: close\r\n\r\n");
        output.write(header.toString().getBytes("UTF-8"));
    }

    private static boolean sameOrigin(String origin, String host) {
        if (origin == null || origin.length() == 0) return true; // non-browser clients can use the QR token directly
        if (host == null || host.length() == 0) return false;
        try {
            java.net.URI uri = new java.net.URI(origin);
            return "http".equalsIgnoreCase(uri.getScheme()) && uri.getRawAuthority() != null
                    && uri.getRawAuthority().equalsIgnoreCase(host) && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (Exception invalid) { return false; }
    }

    private static long[] parseRange(String value, long length) {
        try {
            if (value == null || !value.startsWith("bytes=") || value.indexOf(',') >= 0 || length < 0) return null;
            String spec = value.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) return null;
            String left = spec.substring(0, dash).trim(), right = spec.substring(dash + 1).trim();
            long start, end;
            if (left.length() == 0) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0 || length == 0) return null;
                start = Math.max(0L, length - suffix); end = length - 1;
            } else {
                start = Long.parseLong(left);
                end = right.length() == 0 ? length - 1 : Long.parseLong(right);
                if (start < 0 || start >= length || end < start) return null;
                end = Math.min(end, length - 1);
            }
            return new long[]{start, end};
        } catch (Exception invalid) { return null; }
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new IOException("File changed while downloading");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.length() == 0) return "";
        for (String item : query.split("&")) {
            int equal = item.indexOf('=');
            String rawName = equal < 0 ? item : item.substring(0, equal);
            try {
                if (key.equals(URLDecoder.decode(rawName, "UTF-8")))
                    return equal < 0 ? "" : URLDecoder.decode(item.substring(equal + 1), "UTF-8");
            } catch (Exception invalid) { return ""; }
        }
        return "";
    }

    private static String safeFilename(String value) {
        if (value == null) return null;
        String name = value.trim();
        if (name.length() == 0 || name.equals(".") || name.equals("..") || name.length() > 240
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) return null;
        try { if (name.getBytes("UTF-8").length > 240) return null; }
        catch (Exception impossible) { return null; }
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return null;
        return name;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%21", "!")
                .replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~"); }
        catch (Exception impossible) { return ""; }
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String asciiFilename(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 32 && c < 127 && c != '"' && c != '\\' ? c : '_');
        }
        return out.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String parentPath(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private static void close(Socket socket) { try { socket.close(); } catch (Exception ignored) {} }

    private static final class Request {
        String method = "", path = "", query = "", cookie = "", origin = "", host = "", range = "", transferEncoding = "", expect = "", shareHeader = "";
        long contentLength = -1;
    }

    private static final class ClientInputException extends IOException {
        final String status;
        ClientInputException(String status, String message) { super(message); this.status = status; }
    }
}
