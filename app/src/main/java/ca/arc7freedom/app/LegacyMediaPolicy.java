package ca.arc7freedom.app;

import java.util.Locale;

/** Conservative, testable MT8125/Android4 stream compatibility and radio URL repairs. */
public final class LegacyMediaPolicy {
    private LegacyMediaPolicy() {}
    public static boolean aacLc(String codecs,int channels,int sampleRate) {
        String c=codecs==null ? "" : codecs.toLowerCase(Locale.US);
        return c.matches(".*mp4a\\.40\\.2(?:[\\\" ,;]|$).*") && channels<=2 && sampleRate<=48000;
    }
    public static boolean h264(String codecs,int height,int fps) {
        String c=codecs==null ? "" : codecs.toLowerCase(Locale.US);
        return (c.contains("avc1") || c.contains("avc3")) && height<=720 && fps<=30
                && !c.contains("av01") && !c.contains("vp09") && !c.contains("vp9") && !c.contains("hev1") && !c.contains("hvc1");
    }
    public static String canonicalRadio(String input) {
        if(input==null) return "";
        // CBC moved these MP3 mounts to *_CBC. Keep the same regional station, never
        // replace Kitchener programming with a differently named Toronto station.
        if(input.startsWith("https://playerservices.streamtheworld.com/api/livestream-redirect/")) {
            for(String call:new String[]{"CBLAFM2","CBLAFM","CBLFM"})
                if(input.endsWith("/"+call+".mp3")) return input.substring(0,input.length()-4)+"_CBC.mp3";
        }
        return input;
    }
    public static String radioFallback(String input) {
        String url=canonicalRadio(input);
        if(url.endsWith("/CBLAFM_CBC.mp3") && url.contains("playerservices.streamtheworld.com/"))
            return "https://cbcradiolive.akamaized.net/hls/live/2041036/ES_R1ETR/master.m3u8";
        return "";
    }
    public static boolean audioContainerFailure(int what, int extra) {
        return what == 1 && (extra == -1010 || extra == -1007 || extra == Integer.MIN_VALUE);
    }
    public static String error(int what,int extra) {
        String detail=(what == -38 || extra == -38) ? "Invalid player state for this operation. Try the track again."
                : extra==-1010 ? "Unsupported media format. Use AAC-LC audio / H.264 video (720p, 30fps maximum)."
                : extra==-1004 || extra==1004 ? "Stream I/O failed. Check the station address, connection or provider availability."
                : extra==-1007 ? "Malformed or incomplete stream." : "The device could not play this stream.";
        return "MediaPlayer "+what+"/"+extra+" — "+detail;
    }
}
