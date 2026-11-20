package ca.arc7freedom.app;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Actual esds/ASC validation + AAC access-unit preservation through both legacy containers. */
public final class AudioCompatibilityHarness {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private interface Bad { void run() throws Exception; }
    private static void reject(Bad action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { return; } throw new AssertionError(label);
    }
    static MorpheRemuxer.Track track(final byte[] bytes, long initEnd) {
        return new MorpheRemuxer.Track("audio/mp4",bytes.length,initEnd,3000,new MorpheRemuxer.RangeFetcher(){
            public byte[] fetch(long start,long end)throws Exception {
                if(start<0 || end>=bytes.length || end<start)throw new EOFException();return Arrays.copyOfRange(bytes,(int)start,(int)end+1);
            }
        });
    }
    static int ascOffset(byte[] bytes) {
        for(int i=0;i+6<bytes.length;i++) if(bytes[i]==5 && bytes[i+1]==(byte)128 && bytes[i+2]==(byte)128 && bytes[i+3]==(byte)128 && bytes[i+4]==5 && bytes[i+5]==0x12 && bytes[i+6]==8)return i+5;
        throw new AssertionError("Fixture ASC not found");
    }
    public static void main(String[] args)throws Exception {
        File fixtures=new File(args[0]),output=new File(args[1]);
        byte[] init=Files.readAllBytes(new File(fixtures,"audio-init.mp4").toPath());
        ByteArrayOutputStream input=new ByteArrayOutputStream();input.write(init);
        for(int i=0;i<4;i++)input.write(Files.readAllBytes(new File(fixtures,String.format(Locale.US,"audio-%02d.m4s",i)).toPath()));
        byte[] source=input.toByteArray();MorpheRemuxer.Track valid=track(source,init.length-1);
        MorpheRemuxer.validateLegacyAudio(valid);
        MorpheRemuxer.ClassicTrackBuilder builder=new MorpheRemuxer.ClassicTrackBuilder(init,1,false,3000);
        check(builder.aac.sampleRate==44100 && builder.aac.channels==1,"ASC was not read correctly");
        byte[] inconsistent=builder.stsdBox.clone();inconsistent[41]=2;inconsistent[48]=0;inconsistent[49]=1;
        LegacyAacConfig authoritative=LegacyAacConfig.fromStsd(inconsistent);
        byte[] normalized=authoritative.normalizedStsd(inconsistent);
        check(normalized[41]==1 && (normalized[48]&255)==172 && (normalized[49]&255)==68,"Sample entry was not normalized from actual ASC");
        File m4a=new File(output,"verified-audio.m4a"),aac=new File(output,"compatibility-audio.aac");
        RandomAccessFile out=new RandomAccessFile(m4a,"rw");try{MorpheRemuxer.remuxSingleTrackClassicMp4(valid,out,null);}finally{out.close();}
        out=new RandomAccessFile(aac,"rw");try{MorpheRemuxer.remuxSingleTrackAdts(valid,out,null);}finally{out.close();}
        MorpheRemuxer.validateClassicFile(m4a,true);
        byte[] mp4=Files.readAllBytes(m4a.toPath()),adts=Files.readAllBytes(aac.toPath());
        int start=-1,end=-1;
        for(MorpheRemuxer.Box box:MorpheRemuxer.boxes(mp4,0,mp4.length))if("mdat".equals(box.type)){start=box.contentStart;end=box.contentEnd;}
        ByteArrayOutputStream payload=new ByteArrayOutputStream();int position=0,frames=0;
        while(position<adts.length) {
            check((adts[position]&255)==255 && (adts[position+1]&255)==241,"Invalid MPEG-4 ADTS sync/header");
            check(((adts[position+2]>>>6)&3)==1 && ((adts[position+2]>>>2)&15)==4,"Not AAC-LC/44100 ADTS");
            int channels=((adts[position+2]&1)<<2)|((adts[position+3]>>>6)&3);check(channels==1,"ADTS channel count");
            int length=((adts[position+3]&3)<<11)|((adts[position+4]&255)<<3)|((adts[position+5]>>>5)&7);
            check(length>7 && position+length<=adts.length,"Invalid ADTS frame length");
            payload.write(adts,position+7,length-7);position+=length;frames++;
        }
        check(frames>100 && Arrays.equals(payload.toByteArray(),Arrays.copyOfRange(mp4,start,end)),"AAC samples changed or were dropped in ADTS recovery");
        for(final byte[] asc:new byte[][]{{0x2a,0x08},{(byte)0xea,0x08},{0x12,0x00},{0x12,0x38},{0x10,0x08},{0x12,0x0c},{0x12},{0x12,0x08,0x56,(byte)0xe5,(byte)0x80}})
            reject(new Bad(){public void run()throws Exception{LegacyAacConfig.fromAudioSpecificConfig(asc,0,asc.length);}},"Unsupported/truncated ASC accepted: "+Arrays.toString(asc));
        for(final int objectType:new int[]{5,29,31}) {
            final byte[] lie=source.clone();int offset=ascOffset(lie);lie[offset]=(byte)((objectType<<3)|(lie[offset]&7));
            reject(new Bad(){public void run()throws Exception{MorpheRemuxer.validateLegacyAudio(track(lie,init.length-1));}},"MIME .40.2 concealed unsupported AAC object type");
        }
        for(final int corrupt:new int[]{0,8,12,16,32,52}) {
            final byte[] broken=builder.stsdBox.clone();broken[corrupt]=(byte)255;
            reject(new Bad(){public void run()throws Exception{LegacyAacConfig.fromStsd(broken);}},"Corrupt stsd/esds boundary accepted: "+corrupt);
        }
        final LegacyAacConfig config=builder.aac;
        reject(new Bad(){public void run()throws Exception{config.adtsHeader(8191);}},"Oversize ADTS access unit accepted");
        check(LegacyMediaPolicy.audioContainerFailure(1,-1010) && LegacyMediaPolicy.audioContainerFailure(1,-1007)
                && !LegacyMediaPolicy.audioContainerFailure(1,-1004) && !LegacyMediaPolicy.audioContainerFailure(100,-1010),"Container-error recovery policy");
        Thread.currentThread().interrupt();
        try {
            RandomAccessFile cancelled=new RandomAccessFile(new File(output,"cancelled.aac"),"rw");
            try{final RandomAccessFile target=cancelled;reject(new Bad(){public void run()throws Exception{MorpheRemuxer.remuxSingleTrackAdts(track(source,init.length-1),target,null);}},"Cancelled recovery completed");}finally{cancelled.close();}
        } finally {Thread.interrupted();}
        System.out.println("PASS: actual AAC-LC esds/ASC preflight, MIME lies/HE-AAC/SBR/channel/rate/truncation rejection, normalized sample entry, sample-exact M4A -> ADTS recovery and cancellation");
    }
}
