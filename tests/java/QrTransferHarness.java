package ca.arc7freedom.app;

import com.google.zxing.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Production QR -> synthetic camera -> production reader, and real streaming/disk/resume tests. */
public final class QrTransferHarness {
    private static final int W=640,H=480;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private interface Fails{void run()throws Exception;}
    private static void rejected(Fails action,String message)throws Exception{
        try{action.run();}catch(IOException expected){return;}throw new AssertionError(message);
    }
    private static String seal(String text)throws Exception{
        CRC32 crc=new CRC32();crc.update(text.getBytes("US-ASCII"));return text+"|"+String.format(Locale.US,"%08x",crc.getValue());
    }
    private static String changed(String text,int field,String value)throws Exception{
        String[] fields=text.split("\\|",-1);fields[field]=value;
        return seal(String.join("|",Arrays.copyOf(fields,fields.length-1)));
    }
    static byte[] camera(String text,boolean mirror,int rotation){
        boolean[][] modules=QrCodeEncoder.encodeMatrix(text);int n=modules.length;
        int cell=Math.min(5,(Math.min(W,H)-32)/(n+8)),left=(W-n*cell)/2,top=(H-n*cell)/2;
        check(cell>=3,"Camera fixture cannot resolve this density");
        byte[] y=new byte[W*H];Arrays.fill(y,(byte)242);
        for(int r=0;r<n;r++)for(int c=0;c<n;c++)if(modules[r][c])for(int dy=0;dy<cell;dy++)for(int dx=0;dx<cell;dx++){
            int x=left+c*cell+dx,v=top+r*cell+dy;if(mirror)x=W-1-x;if(rotation==180){x=W-1-x;v=H-1-v;}y[v*W+x]=(byte)18;
        }
        Random noise=new Random(text.hashCode());for(int i=0;i<y.length;i++)y[i]=(byte)Math.max(0,Math.min(255,(y[i]&255)+noise.nextInt(15)-7));
        return y;
    }
    static String decode(byte[] y,int w,int h)throws Exception{
        try{return new Arc7QrCameraDecoder().decode(y,w,h);}catch(ReaderException error){throw new AssertionError("Synthetic camera decode failed at "+w+"x"+h,error);}
    }
    private static void put(File file,byte[] bytes)throws IOException{Files.write(file.toPath(),bytes);}
    private static byte[] read(File file)throws IOException{return Files.readAllBytes(file.toPath());}
    private static Arc7QrTransfer.Frame parse(String text)throws IOException{return Arc7QrTransfer.parse(text);}

    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);
        if(args.length>1&&args[1].equals("crash")){
            List<String> frames=Files.readAllLines(new File(root,"crash.frames").toPath(),StandardCharsets.US_ASCII);
            Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(parse(frames.get(0)),new File(root,"resume"),true);
            for(int i=1;i<frames.size();i++)rx.accept(parse(frames.get(i)));
            Runtime.getRuntime().halt(0); // Simulated process loss: no close/finally/checkpoint.
        }
        File cache=new File(root,"cache"),saved=new File(root,"saved");cache.mkdirs();saved.mkdirs();
        final byte[] original=read(new File(root,"payload.bin"));File source=new File(root,"résumé 📷.bin");put(source,original);
        try(Arc7QrTransfer.Sender sender=new Arc7QrTransfer.Sender(source,cache)){
            final String header=sender.manifest();check(header.startsWith("A7Q2|")&&sender.chunk==512,"Wrong default protocol");
            check(decode(camera(header,false,0),W,H).equals(header),"Header camera round trip");
            check(decode(camera(header,true,0),W,H).equals(header),"Mirrored/front camera");
            check(decode(camera(header,false,180),W,H).equals(header),"Upside-down camera");
            byte[] normal=camera(header,false,0),rotated=new byte[normal.length];
            for(int y=0;y<H;y++)for(int x=0;x<W;x++)rotated[x*H+H-1-y]=normal[y*W+x];
            check(decode(rotated,H,W).equals(header),"Portrait sensor rotation");
            put(source,new byte[]{9}); // Source mutation must not affect the immutable disk snapshot.
            try(final Arc7QrTransfer.Receiver sink=new Arc7QrTransfer.Receiver(parse(header),cache)){
                rejected(()->sink.verify(),"Incomplete file verified");
                List<String> frames=new ArrayList<String>();frames.add(header);for(int i=0;i<sender.total;i++)frames.add(sender.data(i));
                Files.write(new File(root,"java.frames").toPath(),frames,StandardCharsets.US_ASCII);
                put(new File(root,"java-camera.y"),camera(sender.data(0),false,0));
                List<Integer> order=new ArrayList<Integer>();for(int i=0;i<sender.total;i++)if(i!=2)order.add(i);Collections.shuffle(order,new Random(7));
                for(int i:order){
                    String text=sender.data(i);check(text.length()<=Arc7QrTransfer.MAX_TEXT,"Frame capacity");
                    check(decode(camera(text,false,i%2==0?180:0),W,H).equals(text),"Camera changed bytes");
                    check(sink.accept(parse(text)),"New frame rejected");check(!sink.accept(parse(text)),"Repeat counted twice");
                }
                check(!sink.complete()&&sink.missing().equals("3"),"Missing tracking");sink.accept(parse(sender.data(2)));sink.verify();
                File collision=new File(saved,sender.name);put(collision,new byte[]{42});File done=sink.save(saved);
                check(!done.equals(collision)&&Arrays.equals(read(done),original)&&read(collision)[0]==42,"Atomic save/collision");
                rejected(()->parse(sender.data(0).substring(0,10)+"X"+sender.data(0).substring(11)),"CRC damage accepted");
                try(final Arc7QrTransfer.Sender other=new Arc7QrTransfer.Sender(source,cache)){
                    rejected(()->sink.accept(parse(other.manifest())),"Mixed transfer accepted");
                }
                for(final String name:new String[]{"../escape","/absolute","..","bad\\path","bad\nname"})
                    rejected(()->parse(changed(header,7,Arc7QrTransfer.base64(name.getBytes("UTF-8")))),"Unsafe name accepted");
                rejected(()->parse(changed(header,3,"67108865")),"Over-64-MiB manifest accepted");
                rejected(()->parse(changed(header,4,"256")),"Unsupported density accepted");
                rejected(()->parse(changed(header,5,"0")),"Zero frames accepted");
                rejected(()->parse(changed(sender.data(0),6,"AB==")),"Noncanonical base64 accepted");
                rejected(()->sink.accept(parse(changed(sender.data(0),6,"AA=="))),"Wrong data length accepted");
                rejected(()->sink.accept(parse(changed(sender.data(0),6,Arc7QrTransfer.base64(new byte[512])))),"Conflicting duplicate accepted");
                rejected(()->sink.accept(parse(changed(sender.data(0),5,"128"))),"Profile mix accepted");
                try(final Arc7QrTransfer.Receiver wrong=new Arc7QrTransfer.Receiver(parse(changed(header,6,String.format("%064d",0))),cache)){
                    for(int i=0;i<sender.total;i++)wrong.accept(parse(sender.data(i)));
                    rejected(()->wrong.save(saved),"Valid per-frame CRC with wrong file SHA was published");
                }
            }
        }
        // v1 legacy + all three v2 densities: actual QR pixels and deterministic cross-language IDs.
        put(source,original);List<String> ids=Files.readAllLines(new File(root,"browser.ids").toPath(),StandardCharsets.US_ASCII);
        for(int profile=0;profile<4;profile++){
            int density=new int[]{128,512,1024,128}[profile];boolean legacy=profile==3;
            try(Arc7QrTransfer.Sender tx=new Arc7QrTransfer.Sender(source,cache,density,legacy)){
                check(tx.id.equals(ids.get(profile)),"Browser/Java deterministic transfer ID differs");
                String text=tx.data(0);check(decode(camera(text,true,0),W,H).equals(text),"Camera profile "+profile);
                check(!legacy||text.length()<=271,"Legacy capacity changed");
                put(new File(root,"java-profile-"+profile+".y"),camera(text,false,0));
                Files.write(new File(root,"java-profile-"+profile+".txt").toPath(),text.getBytes("US-ASCII"));
            }
        }
        check(decode(read(new File(root,"browser-camera.y")),W,H).startsWith("A7Q2|M|"),"Browser QR -> Android reader");
        List<String> browser=Files.readAllLines(new File(root,"browser.frames").toPath(),StandardCharsets.US_ASCII);
        try(Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(parse(browser.get(0)),cache)){
            for(int i=browser.size()-1;i>0;i--)rx.accept(parse(browser.get(i)));rx.verify();check(Arrays.equals(read(rx.save(saved)),original),"Browser bytes differ");
        }
        // Larger than the previous limit: full streaming transfer, periodic checkpoints and process-loss recovery.
        byte[] large=new byte[2*1024*1024+77];new Random(944).nextBytes(large);File largeFile=new File(root,"large.bin");put(largeFile,large);
        final File resume=new File(root,"resume");resume.mkdirs();
        try(Arc7QrTransfer.Sender tx=new Arc7QrTransfer.Sender(largeFile,cache)){
            final Arc7QrTransfer.Frame meta=parse(tx.manifest());List<String> crash=new ArrayList<String>();crash.add(tx.manifest());for(int i=0;i<35;i++)crash.add(tx.data(i));
            Files.write(new File(root,"crash.frames").toPath(),crash,StandardCharsets.US_ASCII);
            Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),"-cp",System.getProperty("java.class.path"),QrTransferHarness.class.getName(),root.getPath(),"crash").inheritIO().start();
            check(child.waitFor()==0,"Crash fixture did not exit");
            check(Arc7QrTransfer.Receiver.pending(resume,meta.id).sha256.equals(meta.sha256),"Pending metadata");
            try(Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(meta,resume,true)){
                check(rx.receivedCount()==32,"Crash recovery must trust only the durable bitmap");
                rejected(()->new Arc7QrTransfer.Receiver(meta,resume,true),"Two receivers acquired the same private progress");
                rejected(()->Arc7QrTransfer.Receiver.discardPending(resume,meta.id),"Discard deleted an active receive");
                check(!rx.accept(parse(tx.data(0))),"Durable duplicate counted again");
                for(int i=tx.total-1;i>=32;i--)rx.accept(parse(tx.data(i)));
                rx.verify();rx.checkpoint();
            }
            try(Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(meta,resume,true)){
                check(rx.complete(),"Completed checkpoint lost");rx.verify();check(Arrays.equals(read(rx.save(saved)),large),"Large resumed bytes differ");rx.discard();
            }
            // Corrupt journal, truncated private bytes and conflicting headers fail closed and retain progress.
            Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(meta,resume,true);rx.accept(parse(tx.data(0)));rx.close();
            rejected(()->new Arc7QrTransfer.Receiver(parse(changed(tx.manifest(),6,String.format("%064d",0))),resume,true),"Checkpoint metadata conflict accepted");
            File state=new File(resume,"receive-"+meta.id+".state");byte[] valid=read(state),damaged=valid.clone();damaged[damaged.length-1]^=1;put(state,damaged);
            rejected(()->new Arc7QrTransfer.Receiver(meta,resume,true),"Corrupt journal accepted");check(state.exists(),"Corrupt checkpoint deleted without consent");put(state,valid);
            try(RandomAccessFile partial=new RandomAccessFile(new File(resume,"receive-"+meta.id+".part"),"rw")){partial.setLength(1);}
            rejected(()->new Arc7QrTransfer.Receiver(meta,resume,true),"Truncated progress accepted");
            Arc7QrTransfer.Receiver.discardPending(resume,meta.id);check(resume.list().length==0,"Explicit damaged-progress discard leaked files");
            rejected(()->Arc7QrTransfer.Receiver.discardPending(resume,"../escape"),"Discard path escaped private storage");
            rejected(()->new Arc7QrTransfer.Sender(largeFile,cache,128,true),"Legacy mode accepted >1 MiB");
        }
        // Empty/exact/final-short boundaries for every density; the 64 MiB sender uses a sparse disk file,
        // not a 64 MiB Java byte[]. Only metadata/end frames are checked at the absolute maximum.
        for(int density:new int[]{128,512,1024})for(int length:new int[]{0,1,density-1,density,density+1}){
            byte[] bytes=new byte[length];new Random(length).nextBytes(bytes);File file=new File(root,"boundary.bin");put(file,bytes);
            try(Arc7QrTransfer.Sender tx=new Arc7QrTransfer.Sender(file,cache,density,false);Arc7QrTransfer.Receiver rx=new Arc7QrTransfer.Receiver(parse(tx.manifest()),cache)){
                for(int i=0;i<tx.total;i++)rx.accept(parse(tx.data(i)));rx.verify();check(Arrays.equals(read(rx.save(saved)),bytes),"Boundary bytes");
            }
        }
        File max=new File(root,"max.bin");try(RandomAccessFile file=new RandomAccessFile(max,"rw")){file.setLength(Arc7QrTransfer.MAX_BYTES);}
        try(Arc7QrTransfer.Sender tx=new Arc7QrTransfer.Sender(max,cache,1024,false)){
            check(parse(tx.manifest()).size==Arc7QrTransfer.MAX_BYTES&&parse(tx.data(tx.total-1)).bytes.length==1024,"64 MiB boundary");
        }
        try(RandomAccessFile file=new RandomAccessFile(max,"rw")){file.setLength(Arc7QrTransfer.MAX_BYTES+1L);}
        rejected(()->new Arc7QrTransfer.Sender(max,cache),"Oversized source accepted");
        check(cache.list().length==0,"Ephemeral send/receive snapshots leaked");
        System.out.println("PASS: noisy/rotated/mirrored synthetic QR camera; Java/browser v1+v2/three-density interoperability; >2 MiB full transfer; 64 MiB streaming boundary; process-loss checkpoints/resume/discard; CRC/SHA/order/repeat/path/collision/size safety");
    }
}
