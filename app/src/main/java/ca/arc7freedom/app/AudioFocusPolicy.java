package ca.arc7freedom.app;

/** Pure state policy. Resumption requires a live focus request, the same playback generation and user intent. */
public final class AudioFocusPolicy {
    public static final int NONE=0, PAUSE=1, DUCK=2, RESTORE=3, RESUME=4;
    private boolean requested, held, wantsPlay, ducked;
    private int resumeGeneration=-1;
    public void requested(boolean granted) { requested=granted; held=granted; wantsPlay=granted; ducked=false; resumeGeneration=-1; }
    public boolean held() { return held; }
    public boolean ducked() { return ducked; }
    public void abandon() { requested=false; held=false; wantsPlay=false; ducked=false; resumeGeneration=-1; }
    public int change(int change, boolean playing, int generation) {
        if (!requested) return NONE;
        if (change == 1) {
            held=true; ducked=false;
            boolean resume=wantsPlay && resumeGeneration==generation;
            resumeGeneration=-1;
            return resume ? RESUME : RESTORE;
        }
        if (change == -3) { if (!held) return NONE; ducked=true; return DUCK; }
        if (change == -2) { held=false; ducked=false; resumeGeneration=wantsPlay && (playing || resumeGeneration==generation) ? generation : -1; return PAUSE; }
        if (change == -1) { abandon(); return PAUSE; }
        return NONE;
    }
}
