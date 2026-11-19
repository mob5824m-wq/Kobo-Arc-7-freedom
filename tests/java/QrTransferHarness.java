package ca.arc7freedom.app;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;

/** The production encoder -> synthetic NV21 camera luma -> production ZXing reader -> real disk receiver. */
public final class QrTransferHarness {
    private static final int W = 640, H = 480;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private interface Fails { void run() throws Exception; }
    private static void rejected(Fails action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; } throw new AssertionError(message);
    }
    private static String seal(String text) throws Exception {
        CRC32 crc = new CRC32(); crc.update(text.getBytes("US-ASCII")); return text + "|" + String.format(Locale.US,"%08x",crc.getValue());
    }
    static byte[] camera(String text, boolean mirror, int rotation) {
        boolean[][] modules = QrCodeEncoder.encodeMatrix(text); int n = modules.length, cell = 5, left = (W-n*cell)/2, top = (H-n*cell)/2;
        byte[] y = new byte[W*H]; Arrays.fill(y,(byte)242);
        for (int r=0;r<n;r++) for (int c=0;c<n;c++) if (modules[r][c]) for(int dy=0;dy<cell;dy++) for(int dx=0;dx<cell;dx++) {
            int x=left+c*cell+dx, v=top+r*cell+dy;
            if(mirror) x=W-1-x;
            if(rotation==180) {x=W-1-x;v=H-1-v;}
            y[v*W+x]=(byte)18;
        }
        // Mild deterministic sensor noise, still exercising thresholding rather than PURE_BARCODE mode.
        Random noise = new Random(text.hashCode());
        for(int i=0;i<y.length;i++) y[i]=(byte)Math.max(0,Math.min(255,(y[i]&255)+noise.nextInt(15)-7));
        return y;
    }
    static String decode(byte[] y, int w, int h) throws Exception {
        try { return new Arc7QrCameraDecoder().decode(y,w,h); }
        catch (ReaderException error) { throw new AssertionError("Camera QR decode failed at " + w + "x" + h, error); }
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]), cache=new File(root,"cache"), saved=new File(root,"saved"); cache.mkdirs(); saved.mkdirs();
        final byte[] original=Files.readAllBytes(new File(root,"payload.bin").toPath());
        File source=new File(root,"résumé 📷.bin"); Files.write(source.toPath(),original);
        Arc7QrTransfer.Sender sender=new Arc7QrTransfer.Sender(source,cache);
        Arc7QrTransfer.Receiver receiver=null;
        try {
            String manifest=sender.manifest(); check(manifest.length()<=271,"Manifest exceeds encoder capacity");
            check(decode(camera(manifest,false,0),W,H).equals(manifest),"Header QR round-trip failed");
            check(decode(camera(manifest,true,0),W,H).equals(manifest),"Mirrored/front camera QR failed");
            check(decode(camera(manifest,false,180),W,H).equals(manifest),"Upside-down camera QR failed");
            byte[] normal=camera(manifest,false,0), rotated=new byte[normal.length];
            for(int y=0;y<H;y++) for(int x=0;x<W;x++) rotated[x*H+H-1-y]=normal[y*W+x];
            check(decode(rotated,H,W).equals(manifest),"Portrait/rotated sensor QR failed");
            Files.write(source.toPath(),new byte[]{9}); // Sender is an immutable snapshot, not the live source.
            receiver=new Arc7QrTransfer.Receiver(Arc7QrTransfer.parse(decode(camera(manifest,false,0),W,H)),cache);
            final Arc7QrTransfer.Receiver sink=receiver;
            rejected(new Fails(){public void run() throws Exception{sink.verify();}},"Incomplete file verified");
            List<String> frames=new ArrayList<String>(); frames.add(manifest);
            for(int i=0;i<sender.total;i++) frames.add(sender.data(i));
            Files.write(new File(root,"java.frames").toPath(),frames,java.nio.charset.StandardCharsets.US_ASCII);
            Files.write(new File(root,"java-camera.y").toPath(),camera(sender.data(0),false,0));
            List<Integer> order=new ArrayList<Integer>(); for(int i=0;i<sender.total;i++) if(i!=2)order.add(i);
            Collections.shuffle(order,new Random(7));
            for(int i:order) {
                String text=sender.data(i); check(text.length()<=271,"Data exceeds capacity");
                String scanned;
                try { scanned=decode(camera(text,false,i%2==0?180:0),W,H); }
                catch (AssertionError error) {
                    System.err.println("Failed frame " + i + " payload=" + text);
                    System.err.println("Direct matrix decode=" + new com.google.zxing.qrcode.decoder.Decoder().decode(QrCodeEncoder.encodeMatrix(text)).getText());
                    Files.write(new File("build/qr-failure.y").toPath(),camera(text,false,i%2==0?180:0));
                    Files.write(new File("build/qr-failure.txt").toPath(),text.getBytes("US-ASCII"));
                    throw error;
                }
                check(scanned.equals(text),"Camera changed frame bytes");
                check(sink.accept(Arc7QrTransfer.parse(scanned)),"New frame not accepted");
                check(!sink.accept(Arc7QrTransfer.parse(scanned)),"Repeat counted twice");
            }
            check(!sink.complete() && sink.missing().equals("3"),"Missing frame tracking is not exact");
            sink.accept(Arc7QrTransfer.parse(sender.data(2))); sink.verify();
            File collision=new File(saved,sender.name); Files.write(collision.toPath(),new byte[]{42});
            File completed=sink.save(saved);
            check(!completed.equals(collision) && Arrays.equals(Files.readAllBytes(completed.toPath()),original),"Saved bytes differ / collision overwritten");
            check(Arrays.equals(Files.readAllBytes(collision.toPath()),new byte[]{42}),"Existing file was replaced");
            final String damaged=sender.data(0).replace("A7Q1|D|","A7Q1|X|");
            rejected(new Fails(){public void run()throws Exception{Arc7QrTransfer.parse(damaged);}},"Damaged CRC accepted");
            final Arc7QrTransfer.Sender other=new Arc7QrTransfer.Sender(source,cache);
            try{rejected(new Fails(){public void run()throws Exception{sink.accept(Arc7QrTransfer.parse(other.manifest()));}},"Mixed transfer accepted");}finally{other.close();}
            String[] parts=manifest.split("\\|",-1);
            for(final String name:new String[]{"../escape.bin","/absolute","..","bad\\path","bad\nname"}) {
                final String bad=seal("A7Q1|M|"+parts[2]+"|"+parts[3]+"|128|"+parts[5]+"|"+parts[6]+"|"+Arc7QrTransfer.base64(name.getBytes("UTF-8")));
                rejected(new Fails(){public void run()throws Exception{Arc7QrTransfer.parse(bad);}},"Unsafe name accepted: "+name);
            }
            final String tooLarge=seal("A7Q1|M|"+parts[2]+"|1048577|128|8192|"+parts[6]+"|YQ==");
            rejected(new Fails(){public void run()throws Exception{Arc7QrTransfer.parse(tooLarge);}},"Oversized allocation accepted");
            final String badBase=seal("A7Q1|D|"+parts[2]+"|0|"+parts[5]+"|AB==");
            rejected(new Fails(){public void run()throws Exception{Arc7QrTransfer.parse(badBase);}},"Noncanonical base64 accepted");
            final String shortData=seal("A7Q1|D|"+parts[2]+"|0|"+parts[5]+"|AA==");
            rejected(new Fails(){public void run()throws Exception{sink.accept(Arc7QrTransfer.parse(shortData));}},"Wrong data length accepted");
            byte[] conflict=new byte[128];
            final String conflictText=seal("A7Q1|D|"+parts[2]+"|0|"+parts[5]+"|"+Arc7QrTransfer.base64(conflict));
            rejected(new Fails(){public void run()throws Exception{sink.accept(Arc7QrTransfer.parse(conflictText));}},"Conflicting duplicate accepted");
            // Valid per-frame CRC but a wrong file hash must never produce a final file.
            String forged=seal("A7Q1|M|"+parts[2]+"|"+parts[3]+"|128|"+parts[5]+"|"+String.format("%064d",0)+"|"+parts[7]);
            final Arc7QrTransfer.Receiver wrong=new Arc7QrTransfer.Receiver(Arc7QrTransfer.parse(forged),cache);
            try {
                for(int i=0;i<sender.total;i++)wrong.accept(Arc7QrTransfer.parse(sender.data(i)));
                rejected(new Fails(){public void run()throws Exception{wrong.save(saved);}},"Bad full-file hash was saved");
            } finally {wrong.close();}
        } finally { if(receiver!=null)receiver.close(); sender.close(); }
        // Browser encoder -> actual Java camera reader, then browser-framed bytes -> Java receiver.
        check(decode(Files.readAllBytes(new File(root,"browser-camera.y").toPath()),W,H).startsWith("A7Q1|M|"),"Browser QR could not be scanned by production reader");
        List<String> browser=Files.readAllLines(new File(root,"browser.frames").toPath(),java.nio.charset.StandardCharsets.US_ASCII);
        Arc7QrTransfer.Receiver fromBrowser=new Arc7QrTransfer.Receiver(Arc7QrTransfer.parse(browser.get(0)),cache);
        try { for(int i=browser.size()-1;i>0;i--)fromBrowser.accept(Arc7QrTransfer.parse(browser.get(i))); fromBrowser.verify();
            check(Arrays.equals(Files.readAllBytes(fromBrowser.save(saved).toPath()),original),"Browser-to-Android bytes differ");
        } finally {fromBrowser.close();}
        for(int length:new int[]{0,1,127,128,129,Arc7QrTransfer.MAX_BYTES}) {
            File file=new File(root,"boundary.bin"); byte[] bytes=new byte[length];new Random(length).nextBytes(bytes);Files.write(file.toPath(),bytes);
            Arc7QrTransfer.Sender tx=new Arc7QrTransfer.Sender(file,cache);
            try {
                check(Arc7QrTransfer.parse(tx.manifest()).size==length,"Boundary length metadata");
                check(Arc7QrTransfer.parse(tx.data(tx.total-1)).index==tx.total-1,"Last frame boundary");
                if(length<130) {
                    Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(Arc7QrTransfer.parse(tx.manifest()),cache);
                    try{for(int i=0;i<tx.total;i++)rx.accept(Arc7QrTransfer.parse(tx.data(i)));rx.verify();check(Arrays.equals(Files.readAllBytes(rx.save(saved).toPath()),bytes),"Boundary bytes differ");}finally{rx.close();}
                }
            }finally{tx.close();}
        }
        check(cache.list().length==0,"Sender/receiver partials leaked on close");
        System.out.println("PASS: real QR encode -> noisy/rotated/mirrored camera luma -> ZXing decode -> byte reassembly; Java/browser interoperability; order/repeats/missing/CRC/SHA/path/size/atomic-collision/cancel boundaries");
    }
}
