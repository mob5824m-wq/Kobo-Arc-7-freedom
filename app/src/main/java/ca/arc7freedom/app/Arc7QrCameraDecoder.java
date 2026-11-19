package ca.arc7freedom.app;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;

/** Bounded camera-luma decoding; alternate threshold/crop handles screen glare and false finders. */
public final class Arc7QrCameraDecoder {
    private final QRCodeReader reader = new QRCodeReader();
    private byte[] rotatedLuma;
    private final EnumMap<DecodeHintType,Object> hints = new EnumMap<DecodeHintType,Object>(DecodeHintType.class);
    public Arc7QrCameraDecoder() { hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE); }

    /** Single worker only. NV21 chroma is unused; sensor orientation/mirroring is detected by QR. */
    public String decode(byte[] nv21, int width, int height) throws ReaderException {
        if (width <= 0 || height <= 0 || width > 1280 || height > 1280 || nv21 == null || nv21.length < width * height)
            throw NotFoundException.getNotFoundInstance();
        PlanarYUVLuminanceSource full = new PlanarYUVLuminanceSource(nv21,width,height,0,0,width,height,false);
        try { return reader.decode(new BinaryBitmap(new HybridBinarizer(full)),hints).getText(); }
        catch (ReaderException retry) {
            try { return reader.decode(new BinaryBitmap(new GlobalHistogramBinarizer(full)),hints).getText(); }
            catch (ReaderException alternatives) {
                // Old camera sensors can be 90/180/270 degrees away from the displayed preview.
                // Trying another scan direction also avoids false finder candidates in dense QRs.
                int pixels=width*height;
                if (rotatedLuma == null || rotatedLuma.length != pixels) rotatedLuma=new byte[pixels];
                for (int turn=1;turn<=3;turn++) {
                    int w=turn==2?width:height,h=turn==2?height:width;
                    for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                        int destination=turn==1?x*height+(height-1-y):turn==2?pixels-1-(y*width+x):(width-1-x)*height+y;
                        rotatedLuma[destination]=nv21[y*width+x];
                    }
                    try {
                        PlanarYUVLuminanceSource rotated=new PlanarYUVLuminanceSource(rotatedLuma,w,h,0,0,w,h,false);
                        return reader.decode(new BinaryBitmap(new HybridBinarizer(rotated)),hints).getText();
                    } catch (ReaderException nextDirection) { /* bounded to three additional directions */ }
                }
                int w=width*3/4,h=height*3/4;
                PlanarYUVLuminanceSource center=new PlanarYUVLuminanceSource(nv21,width,height,(width-w)/2,(height-h)/2,w,h,false);
                return reader.decode(new BinaryBitmap(new HybridBinarizer(center)),hints).getText();
            }
        }
    }
}
