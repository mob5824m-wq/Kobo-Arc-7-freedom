package ca.arc7freedom.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Touch-friendly key names shared with the PC host's strict HOTKEY grammar. */
public final class Arc7KeyBinding {
    private Arc7KeyBinding() {}
    public static final String[] MODIFIERS={"CTRL","SHIFT","ALT","WIN"};
    public static final String[] KEYS;
    static {
        List<String> keys=new ArrayList<String>();
        for(char c='A';c<='Z';c++) keys.add(""+c);
        for(char c='0';c<='9';c++) keys.add(""+c);
        for(int i=1;i<=24;i++) keys.add("F"+i);
        keys.addAll(Arrays.asList("ENTER","ESC","TAB","SPACE","BACKSPACE","DELETE","UP","DOWN","LEFT","RIGHT",
                "HOME","END","PAGEUP","PAGEDOWN","PRINT_SCREEN","PLUS","MINUS","`",".",
                "VOL_MUTE","VOL_DOWN","VOL_UP","NEXT_TRACK","PREV_TRACK","MEDIA_STOP","PLAY_PAUSE"));
        KEYS=keys.toArray(new String[keys.size()]);
    }
    public static String normalize(String combo) {
        if(combo==null || combo.trim().length()==0 || combo.length()>100) throw new IllegalArgumentException("Choose a key first");
        Set<String> modifiers=new HashSet<String>(); String key=null;
        for(String token:combo.toUpperCase(Locale.US).replace('-','+').split("\\+",-1)) {
            String name=token.trim();
            if(name.equals("CONTROL")) name="CTRL";
            if(name.equals("CMD") || name.equals("SUPER")) name="WIN";
            if(name.equals("RETURN")) name="ENTER";
            if(name.equals("ESCAPE")) name="ESC";
            if(Arrays.asList(MODIFIERS).contains(name)) {
                if(!modifiers.add(name)) throw new IllegalArgumentException("Duplicate modifier: "+name);
            } else {
                if(!Arrays.asList(KEYS).contains(name)) throw new IllegalArgumentException("Unknown key: "+name+". Use PLUS or MINUS for those keys.");
                if(key!=null) throw new IllegalArgumentException("Choose one key plus optional modifiers"); key=name;
            }
        }
        if(key==null) throw new IllegalArgumentException("A shortcut needs a key, not only modifiers");
        StringBuilder out=new StringBuilder(); for(String mod:MODIFIERS) if(modifiers.contains(mod)) out.append(mod).append('+');
        return out.append(key).toString();
    }
}
