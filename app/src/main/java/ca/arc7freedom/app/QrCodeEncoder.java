package ca.arc7freedom.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import java.util.EnumMap;

/** API-15 QR facade. Upstream QR writer; no truncated URLs/file-byte frames. */
public final class QrCodeEncoder {
    private QrCodeEncoder() {}

    /** ISO/IEC 18004 QR byte matrices; rejects oversize input instead of silently losing bytes. */
    public static boolean[][] encodeMatrix(String text) {
        if (text == null) text = "";
        try {
            if (text.getBytes("UTF-8").length > 271) throw new IllegalArgumentException("QR text exceeds 271 UTF-8 bytes");
            EnumMap<EncodeHintType,Object> hints = new EnumMap<EncodeHintType,Object>(EncodeHintType.class);
            // ASCII needs no ECI overhead; real Unicode is explicitly marked as UTF-8.
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) > 127) { hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); break; }
            QRCode code = Encoder.encode(text, ErrorCorrectionLevel.L, hints);
            ByteMatrix matrix = code.getMatrix();
            boolean[][] result = new boolean[matrix.getHeight()][matrix.getWidth()];
            for (int y = 0; y < result.length; y++) for (int x = 0; x < result[y].length; x++) result[y][x] = matrix.get(x,y) == 1;
            return result;
        } catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
        catch (WriterException invalid) { throw new IllegalArgumentException("Could not encode QR: " + invalid.getMessage()); }
    }

    /** Crisp integer-sized modules and a mandatory four-module quiet zone. No smoothing. */
    public static Bitmap renderQrBitmap(String text, int targetSizePx) {
        boolean[][] modules = encodeMatrix(text);
        int size = modules.length, quiet = 4, total = size + quiet * 2, pixels = Math.max(total * 4, targetSizePx);
        Bitmap image = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint ink = new Paint(); ink.setColor(Color.BLACK); ink.setAntiAlias(false);
        int cell = pixels / total, start = (pixels - cell * total) / 2;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (modules[y][x]) {
            int left = start + (x + quiet) * cell, top = start + (y + quiet) * cell;
            canvas.drawRect(left, top, left + cell, top + cell, ink);
        }
        return image;
    }
}
