package ca.arc7freedom.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Pure-Java Android 4.2 (`API 17`) Offline PDF Reader, Scan Enhancer & Multi-Page ISO PDF Writer (`v2.2.0`):
 *
 *   - Stock Android 4.2 lacks `android.graphics.pdf.PdfRenderer` (added in Android 5.0 API 21).
 *   - `Arc7PdfEngine` provides:
 *       1. **Offline PDF Parsing (`parsePdfFile`)**: Decompresses `/FlateDecode` zlib content streams,
 *          decodes PDF string literals `(text)` (octal escapes + UTF-16BE BOM `\376\377`), hex strings
 *          `<0048...>`, `Tj` / `TJ` / `'` / `"` text show operators, AND extracts embedded `/DCTDecode`
 *          JPEG scan/figure streams so BOTH text PDFs AND scanned camera PDFs render cleanly offline!
 *       2. **Document Scanner Enhancement (`enhanceDocumentScanBitmap`)**: Adaptive whiteboard/paper
 *          contrast stretch + optional crisp high-contrast B&W document mode for camera-captured pages!
 *       3. **Standard Multi-Page `.PDF` Generator (`writeBitmapsToPdf` / `writeJpegsToPdf`)**: Writes
 *          100% standards-compliant `%PDF-1.4` files with exact byte-offset `xref` tables openable on
 *          any PC, Chromebook, Mac, browser, or Kobo Arc 7!
 */
public final class Arc7PdfEngine {

    private Arc7PdfEngine() {}

    public static final class PdfPage {
        public int pageNumber;
        public String text = "";
        public byte[] embeddedJpeg = null;
    }

    public static final class ParsedPdf {
        public String title = "Document.pdf";
        public final List<PdfPage> pages = new ArrayList<PdfPage>();
    }

    /* ==================== 1. Offline PDF Parser ==================== */

    public static ParsedPdf parsePdfFile(File file) throws Exception {
        ParsedPdf doc = new ParsedPdf();
        doc.title = file.getName();
        long len = Math.min(file.length(), 28L * 1024L * 1024L);
        byte[] raw = new byte[(int) len];
        FileInputStream fis = new FileInputStream(file);
        try {
            int off = 0;
            while (off < raw.length) {
                int n = fis.read(raw, off, raw.length - off);
                if (n <= 0) break;
                off += n;
            }
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
        }

        String latin = new String(raw, Charset.forName("ISO-8859-1"));
        List<String> textChunks = new ArrayList<String>();
        List<byte[]> jpegStreams = new ArrayList<byte[]>();

        int pos = 0;
        while (pos < latin.length()) {
            int dictStart = Math.max(0, latin.lastIndexOf("<<", latin.indexOf("stream", pos)));
            int sIdx = latin.indexOf("stream", pos);
            if (sIdx < 0) break;
            // Must not match `endstream`!
            if (sIdx >= 3 && "end".equals(latin.substring(sIdx - 3, sIdx))) {
                pos = sIdx + 6;
                continue;
            }
            int dataStart = sIdx + 6;
            if (dataStart < raw.length && raw[dataStart] == '\r') dataStart++;
            if (dataStart < raw.length && raw[dataStart] == '\n') dataStart++;
            int endIdx = latin.indexOf("endstream", dataStart);
            if (endIdx < 0) break;
            int dataEnd = endIdx;
            while (dataEnd > dataStart && (raw[dataEnd - 1] == '\n' || raw[dataEnd - 1] == '\r')) {
                dataEnd--;
            }

            String dict = latin.substring(Math.max(0, dictStart), sIdx);
            int streamLen = Math.max(0, dataEnd - dataStart);
            if (streamLen > 0 && streamLen < 12 * 1024 * 1024) {
                if (dict.contains("/DCTDecode") || (streamLen > 64 && (raw[dataStart] & 0xFF) == 0xFF && (raw[dataStart + 1] & 0xFF) == 0xD8)) {
                    byte[] jpg = new byte[streamLen];
                    System.arraycopy(raw, dataStart, jpg, 0, streamLen);
                    jpegStreams.add(jpg);
                } else {
                    byte[] decoded = null;
                    if (dict.contains("/FlateDecode")) {
                        decoded = tryInflate(raw, dataStart, streamLen);
                    } else if (!dict.contains("/Filter")) {
                        decoded = new byte[streamLen];
                        System.arraycopy(raw, dataStart, decoded, 0, streamLen);
                    }
                    if (decoded != null && decoded.length > 0) {
                        String content = new String(decoded, Charset.forName("ISO-8859-1"));
                        if (content.contains("BT") && content.contains("ET")) {
                            String extracted = extractTextFromContentStream(content);
                            if (extracted.trim().length() > 0) {
                                textChunks.add(extracted.trim());
                            }
                        }
                    }
                }
            }
            pos = endIdx + 9;
        }

        int total = Math.max(textChunks.size(), jpegStreams.size());
        if (total == 0) {
            PdfPage empty = new PdfPage();
            empty.pageNumber = 1;
            empty.text = "(No extractable text or JPEG streams found in this PDF.)";
            doc.pages.add(empty);
            return doc;
        }
        for (int i = 0; i < total; i++) {
            PdfPage p = new PdfPage();
            p.pageNumber = i + 1;
            if (i < textChunks.size()) p.text = textChunks.get(i);
            if (i < jpegStreams.size()) p.embeddedJpeg = jpegStreams.get(i);
            doc.pages.add(p);
        }
        return doc;
    }

    private static byte[] tryInflate(byte[] src, int offset, int len) {
        for (int nowrap = 0; nowrap <= 1; nowrap++) {
            Inflater inf = new Inflater(nowrap == 1);
            try {
                inf.setInput(src, offset, len);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                while (!inf.finished() && !inf.needsInput() && out.size() < 4 * 1024 * 1024) {
                    int n = inf.inflate(buf);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                }
                if (out.size() > 0) return out.toByteArray();
            } catch (Throwable ignored) {
            } finally {
                inf.end();
            }
        }
        return null;
    }

    public static String extractTextFromContentStream(String content) {
        StringBuilder out = new StringBuilder();
        Matcher btMatcher = Pattern.compile("BT([\\s\\S]*?)ET").matcher(content);
        while (btMatcher.find()) {
            String block = btMatcher.group(1);
            Matcher tok = Pattern.compile(
                    "\\[(.*?)\\]\\s*TJ|\\((?:\\\\.|[^\\\\)])*\\)\\s*(?:Tj|'|\\\")|<([0-9A-Fa-f\\s]+)>\\s*Tj|(T\\*|Td|TD)")
                    .matcher(block);
            while (tok.find()) {
                String full = tok.group(0);
                if (tok.group(1) != null) {
                    Matcher inner = Pattern.compile("\\((?:\\\\.|[^\\\\)])*\\)|<([0-9A-Fa-f\\s]+)>|(-?\\d+(?:\\.\\d+)?)")
                            .matcher(tok.group(1));
                    while (inner.find()) {
                        String g = inner.group(0);
                        if (g.startsWith("(")) out.append(decodePdfLiteralString(g));
                        else if (inner.group(1) != null) out.append(decodePdfHexString(inner.group(1)));
                        else if (inner.group(2) != null) {
                            try {
                                if (Double.parseDouble(inner.group(2)) < -160) out.append(' ');
                            } catch (Throwable ignored) {}
                        }
                    }
                } else if (tok.group(2) != null) {
                    out.append(decodePdfHexString(tok.group(2)));
                } else if (tok.group(3) != null) {
                    out.append('\n');
                } else if (full.startsWith("(")) {
                    int endParen = full.lastIndexOf(')');
                    if (endParen > 0) out.append(decodePdfLiteralString(full.substring(0, endParen + 1)));
                }
            }
            out.append('\n');
        }
        return out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n");
    }

    private static String decodePdfLiteralString(String lit) {
        if (lit.length() < 2) return "";
        String body = lit.substring(1, lit.length() - 1);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(++i);
                if (n == 'n') raw.write('\n');
                else if (n == 'r') raw.write('\r');
                else if (n == 't') raw.write('\t');
                else if (n == '(' || n == ')' || n == '\\') raw.write(n);
                else if (n >= '0' && n <= '7') {
                    String oct = "" + n;
                    if (i + 1 < body.length() && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') oct += body.charAt(++i);
                    if (i + 1 < body.length() && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') oct += body.charAt(++i);
                    raw.write(Integer.parseInt(oct, 8) & 0xFF);
                } else {
                    raw.write(n & 0xFF);
                }
            } else {
                raw.write(c & 0xFF);
            }
        }
        byte[] b = raw.toByteArray();
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        }
        return new String(b, Charset.forName("ISO-8859-1"));
    }

    private static String decodePdfHexString(String hex) {
        String clean = hex.replaceAll("\\s+", "");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < clean.length(); i += 2) {
            raw.write(Integer.parseInt(clean.substring(i, i + 2), 16));
        }
        byte[] b = raw.toByteArray();
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        }
        return new String(b, Charset.forName("ISO-8859-1"));
    }

    /* ==================== 2. Camera Document Scanner Filter ==================== */

    public static Bitmap enhanceDocumentScanBitmap(Bitmap src, boolean crispBlackAndWhite) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // Compute 5th..95th percentile luminance to whiten paper background & sharpen ink!
        int[] hist = new int[256];
        for (int i = 0; i < px.length; i += 4) {
            int c = px[i];
            int lum = (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) >> 8;
            hist[lum]++;
        }
        int totalSamples = Math.max(1, px.length / 4);
        int lo = 18, hi = 235, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= totalSamples * 0.04) { lo = i; break; }
        }
        acc = 0;
        for (int i = 255; i >= 0; i--) {
            acc += hist[i];
            if (acc >= totalSamples * 0.06) { hi = i; break; }
        }
        int span = Math.max(24, hi - lo);

        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = clampByte(((Color.red(c) - lo) * 255) / span);
            int g = clampByte(((Color.green(c) - lo) * 255) / span);
            int b = clampByte(((Color.blue(c) - lo) * 255) / span);
            if (crispBlackAndWhite) {
                int lum = (r * 77 + g * 150 + b * 29) >> 8;
                int v = lum > 152 ? 255 : (lum < 88 ? 0 : lum);
                px[i] = Color.argb(255, v, v, v);
            } else {
                px[i] = Color.argb(255, r, g, b);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /* ==================== 3. ISO `%PDF-1.4` Multi-Page Writer ==================== */

    public static void writeBitmapsToPdf(List<Bitmap> bitmaps, File outPdf) throws Exception {
        List<byte[]> jpegs = new ArrayList<byte[]>();
        List<int[]> dims = new ArrayList<int[]>();
        for (int i = 0; i < bitmaps.size(); i++) {
            Bitmap b = bitmaps.get(i);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            jpegs.add(bos.toByteArray());
            dims.add(new int[]{b.getWidth(), b.getHeight()});
        }
        writeJpegPagesToPdf(jpegs, dims, outPdf);
    }

    public static void writeJpegFilesToPdf(List<File> imageFiles, boolean bwScanEnhance, File outPdf) throws Exception {
        List<byte[]> jpegs = new ArrayList<byte[]>();
        List<int[]> dims = new ArrayList<int[]>();
        for (int i = 0; i < imageFiles.size(); i++) {
            File f = imageFiles.get(i);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2;
            BitmapFactory.Options load = new BitmapFactory.Options();
            load.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), load);
            if (bmp == null) continue;
            if (bwScanEnhance) {
                Bitmap enh = enhanceDocumentScanBitmap(bmp, false);
                if (enh != bmp) bmp.recycle();
                bmp = enh;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, bos);
            jpegs.add(bos.toByteArray());
            dims.add(new int[]{bmp.getWidth(), bmp.getHeight()});
            bmp.recycle();
        }
        writeJpegPagesToPdf(jpegs, dims, outPdf);
    }

    /**
     * Generates a valid, self-contained `%PDF-1.4` file from raw JPEG page buffers + pixel dimensions
     * (works both on Android devices AND in pure desktop JVM unit tests!).
     */
    public static void writeJpegPagesToPdf(List<byte[]> jpegs, List<int[]> dims, File outPdf) throws Exception {
        if (jpegs == null || jpegs.isEmpty()) {
            throw new IllegalArgumentException("At least one page required to create PDF");
        }
        if (outPdf.getParentFile() != null && !outPdf.getParentFile().exists()) {
            outPdf.getParentFile().mkdirs();
        }
        int n = jpegs.size();
        // Object layout:
        //   1 = Catalog
        //   2 = Pages root
        //   For page i (0..n-1):
        //     pageObj = 3 + i*3
        //     imgObj  = 4 + i*3
        //     cntObj  = 5 + i*3
        int totalObjs = 2 + n * 3;
        long[] offsets = new long[totalObjs + 1];
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        Charset ascii = Charset.forName("ISO-8859-1");

        pdf.write("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n".getBytes(ascii));

        // Obj 1: Catalog
        offsets[1] = pdf.size();
        pdf.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(ascii));

        // Obj 2: Pages
        offsets[2] = pdf.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) kids.append(' ');
            kids.append(3 + i * 3).append(" 0 R");
        }
        pdf.write(("2 0 obj\n<< /Type /Pages /Kids [ " + kids + " ] /Count " + n + " >>\nendobj\n").getBytes(ascii));

        for (int i = 0; i < n; i++) {
            int pageObj = 3 + i * 3;
            int imgObj  = 4 + i * 3;
            int cntObj  = 5 + i * 3;
            byte[] jpg = jpegs.get(i);
            int pxW = Math.max(1, dims.get(i)[0]);
            int pxH = Math.max(1, dims.get(i)[1]);
            // Scale cleanly onto US Letter width (612 pt) preserving aspect ratio
            int ptW = 612;
            int ptH = Math.max(200, Math.round(612f * pxH / (float) pxW));

            offsets[pageObj] = pdf.size();
            pdf.write((pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 " + ptW + " " + ptH
                    + " ] /Resources << /XObject << /Im0 " + imgObj + " 0 R >> >> /Contents "
                    + cntObj + " 0 R >>\nendobj\n").getBytes(ascii));

            offsets[imgObj] = pdf.size();
            pdf.write((imgObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + pxW + " /Height " + pxH
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + jpg.length + " >>\nstream\n").getBytes(ascii));
            pdf.write(jpg);
            pdf.write("\nendstream\nendobj\n".getBytes(ascii));

            byte[] drawCmd = String.format(Locale.US, "q %d 0 0 %d 0 0 cm /Im0 Do Q\n", ptW, ptH).getBytes(ascii);
            offsets[cntObj] = pdf.size();
            pdf.write((cntObj + " 0 obj\n<< /Length " + drawCmd.length + " >>\nstream\n").getBytes(ascii));
            pdf.write(drawCmd);
            pdf.write("\nendstream\nendobj\n".getBytes(ascii));
        }

        long xrefOffset = pdf.size();
        pdf.write(("xref\n0 " + (totalObjs + 1) + "\n0000000000 65535 f \n").getBytes(ascii));
        for (int objId = 1; objId <= totalObjs; objId++) {
            pdf.write(String.format(Locale.US, "%010d 00000 n \n", offsets[objId]).getBytes(ascii));
        }
        pdf.write(("trailer\n<< /Size " + (totalObjs + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xrefOffset + "\n%%EOF\n").getBytes(ascii));

        FileOutputStream fos = new FileOutputStream(outPdf);
        try {
            fos.write(pdf.toByteArray());
        } finally {
            try { fos.close(); } catch (Throwable ignored) {}
        }
    }
}
