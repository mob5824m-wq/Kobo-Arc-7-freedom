#!/usr/bin/env python3
"""Production podcast favourites + media-header structure/events with host Android fakes.

Layout allocations are recorded, not rendered by Android. Artist requests/browse are
also executed by test-suite-features.py. No live provider, device or GPU is implied.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/ca/arc7freedom/app'

STUBS = {
    'android/content/Context.java': '''package android.content;import java.util.*;public class Context {
 public Map<String,SharedPreferences> prefs=new HashMap<String,SharedPreferences>();
 public static final int MODE_PRIVATE=0;public SharedPreferences getSharedPreferences(String n,int m){if(!prefs.containsKey(n))prefs.put(n,new SharedPreferences());return prefs.get(n);}}
''',
    'android/content/SharedPreferences.java': '''package android.content;import java.util.*;public class SharedPreferences {
 public Map<String,String> data=new HashMap<String,String>();public boolean fail;
 public String getString(String k,String d){return data.containsKey(k)?data.get(k):d;}
 public Editor edit(){return new Editor();}public class Editor{Map<String,String> pending=new HashMap<String,String>();
 public Editor putString(String k,String v){pending.put(k,v);return this;}public boolean commit(){if(fail)return false;data.putAll(pending);return true;}}}
''',
    'android/graphics/Color.java': 'package android.graphics;public class Color{public static final int WHITE=-1;}',
    'android/graphics/Typeface.java': 'package android.graphics;public class Typeface{public static final Typeface DEFAULT_BOLD=new Typeface();}',
    'android/text/TextUtils.java': 'package android.text;public class TextUtils{public enum TruncateAt{END}}',
    'android/view/Gravity.java': 'package android.view;public class Gravity{public static final int CENTER_VERTICAL=16;}',
    'android/view/KeyEvent.java': '''package android.view;public class KeyEvent{public static final int KEYCODE_ENTER=66,ACTION_UP=1,ACTION_DOWN=0;
 int key,action;public KeyEvent(int a,int k){action=a;key=k;}public int getAction(){return action;}public int getKeyCode(){return key;}}
''',
    'android/view/inputmethod/EditorInfo.java': 'package android.view.inputmethod;public class EditorInfo{public static final int IME_ACTION_SEARCH=3;}',
    'android/view/View.java': '''package android.view;import android.content.*;public class View{
 Context context;public int measuredWidth,measuredHeight,padLeft,padRight;public CharSequence description;
 public ViewGroup.LayoutParams params;public OnClickListener click;public View(Context c){context=c;}public Context getContext(){return context;}
 public interface OnClickListener{void onClick(View v);}public void setOnClickListener(OnClickListener l){click=l;}public void performClick(){if(click!=null)click.onClick(this);}
 public void setPadding(int l,int t,int r,int b){padLeft=l;padRight=r;}public void setBackgroundDrawable(Object d){}public void setContentDescription(CharSequence s){description=s;}
 public void setMinimumWidth(int n){}public void setMinimumHeight(int n){}public void measureForWidth(int width){measuredWidth=width;measuredHeight=params==null?0:params.height;}}
''',
    'android/view/ViewGroup.java': '''package android.view;import android.content.*;import java.util.*;public class ViewGroup extends View{
 public List<View> children=new ArrayList<View>();public ViewGroup(Context c){super(c);}public static class LayoutParams{public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;public int width,height;
 public LayoutParams(int w,int h){width=w;height=h;}}public void addView(View v,LayoutParams p){v.params=p;children.add(v);}public void addView(View v){addView(v,new LayoutParams(-2,-2));}
 public View getChildAt(int i){return children.get(i);}public int getChildCount(){return children.size();}}
''',
    'android/widget/LinearLayout.java': '''package android.widget;import android.view.*;import android.content.*;public class LinearLayout extends ViewGroup{
 public static final int HORIZONTAL=0,VERTICAL=1;public int orientation;public LinearLayout(Context c){super(c);}public void setOrientation(int n){orientation=n;}public void setGravity(int n){}
 public static class LayoutParams extends ViewGroup.LayoutParams{public float weight;public int leftMargin,rightMargin;public LayoutParams(int w,int h){super(w,h);}public LayoutParams(int w,int h,float f){super(w,h);weight=f;}}
 @Override public void measureForWidth(int width){super.measureForWidth(width);int available=width-padLeft-padRight,fixed=0;float weights=0;
 if(orientation==HORIZONTAL)for(View child:children){LayoutParams p=(LayoutParams)child.params;fixed+=Math.max(0,p.width)+p.leftMargin+p.rightMargin;weights+=p.weight;}
 for(View child:children){LayoutParams p=(LayoutParams)child.params;int w=orientation==VERTICAL?available:p.weight>0?Math.round((available-fixed)*p.weight/weights):p.width;child.measureForWidth(w);}}}
''',
    'android/widget/TextView.java': '''package android.widget;import android.view.*;import android.content.*;import android.text.*;import android.graphics.*;public class TextView extends View{
 public CharSequence text="",hint="";public boolean singleLine;public TextUtils.TruncateAt ellipsize;public OnEditorActionListener editor;
 public TextView(Context c){super(c);}public void setText(CharSequence s){text=s;}public CharSequence getText(){return text;}public void setTextColor(int n){}public void setTextSize(float n){}public void setTypeface(Typeface t){}
 public void setSingleLine(boolean b){singleLine=b;}public void setEllipsize(TextUtils.TruncateAt e){ellipsize=e;}public void setHint(CharSequence s){hint=s;}public void setHintTextColor(int n){}
 public void setMinWidth(int n){}public void setMinHeight(int n){}public void setImeOptions(int n){}public interface OnEditorActionListener{boolean onEditorAction(TextView v,int a,KeyEvent e);}
 public void setOnEditorActionListener(OnEditorActionListener l){editor=l;}}
''',
    'android/widget/EditText.java': 'package android.widget;public class EditText extends TextView{public EditText(android.content.Context c){super(c);}}',
    'android/widget/Button.java': 'package android.widget;public class Button extends TextView{public Button(android.content.Context c){super(c);}}',
    'android/widget/ImageView.java': 'package android.widget;public class ImageView extends android.view.View{public ImageView(android.content.Context c){super(c);}public void setImageResource(int n){}}',
    'ca/arc7freedom/app/Arc7Skin.java': '''package ca.arc7freedom.app;import android.content.*;import android.widget.*;public class Arc7Skin{
 public static class Palette{}public static int dp(Context c,int n){return n;}public static Object headerDrawable(Palette p){return new Object();}public static Object cardDrawable(Context c,Palette p,int r){return new Object();}
 public static Button actionButton(Context c,String s,int n){Button b=new Button(c);b.setText(s);return b;}}
''',
}

HARNESS = r'''package ca.arc7freedom.app;
import android.content.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;
import java.util.*;import org.json.*;
public final class DiscoveryHarness{
 interface Attempt{void run()throws Exception;}static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void reject(Attempt a,String m)throws Exception{try{a.run();}catch(Exception expected){return;}throw new AssertionError("Accepted "+m);}
 static void podcasts()throws Exception{
  Context c=new Context();SharedPreferences sp=c.getSharedPreferences("arc7_radio_rss",0);sp.edit().putString("custom_rss_urls","retained").commit();
  Arc7PodcastLibrary.Show one=new Arc7PodcastLibrary.Show("Science Café","Radio Monde","HTTPS://EXAMPLE.COM:443/caf%C3%A9.xml?sig=a%2Fb#fragment");
  Arc7PodcastLibrary.Show two=new Arc7PodcastLibrary.Show("Daily History","Publisher Two","https://other.example/feed");
  check(one.feedUrl.equals("https://example.com/caf%C3%A9.xml?sig=a%2Fb"),"canonical scheme/host/default port without corrupting signed query bytes");
  check(Arc7PodcastLibrary.load(c).isEmpty(),"new library empty");Arc7PodcastLibrary.setFavorite(c,one,true);Arc7PodcastLibrary.setFavorite(c,two,true);
  Arc7PodcastLibrary.setFavorite(c,one,true);List<Arc7PodcastLibrary.Show> saved=Arc7PodcastLibrary.load(c);
  check(saved.size()==2&&saved.get(0).feedUrl.equals(two.feedUrl),"favourites survive reload, dedup and stable order");
  Context recreated=new Context();recreated.prefs=c.prefs;check(Arc7PodcastLibrary.load(recreated).size()==2,"activity recreation reads serialized preferences");
  Locale old=Locale.getDefault();Locale.setDefault(new Locale("tr","TR"));
  try{check(Arc7PodcastLibrary.filter(saved,"SCIENCE cafe monde").get(0).feedUrl.equals(one.feedUrl),"accent/case-insensitive multiword title and publisher search independent of device locale");}finally{Locale.setDefault(old);}
  check(Arc7PodcastLibrary.filter(saved,"publisher TWO").size()==1&&Arc7PodcastLibrary.filter(saved,"  ").size()==2,"publisher and empty-query matches");
  check(Arc7PodcastLibrary.filter(saved,"science missing").isEmpty()&&Arc7PodcastLibrary.filter(saved,"[.*]").isEmpty(),"all tokens required, literal not regex");
  check(saved.size()==2&&Arc7PodcastLibrary.contains(saved,one),"filter does not mutate saved library");
  Arc7PodcastLibrary.setFavorite(c,one,false);check(Arc7PodcastLibrary.load(c).size()==1&&Arc7PodcastLibrary.load(c).get(0).title.equals(two.title),"remove only selected show");
  check(sp.getString("custom_rss_urls","").equals("retained"),"unrelated Radio/News preferences retained");
  for(String url:new String[]{"javascript:alert(1)","file:///sdcard/feed","https://user:password@example.com/feed","https://example.com/\nfeed","https://example.com:99999/feed","not a url"})reject(()->new Arc7PodcastLibrary.Show("Bad","",url),"unsafe feed URL");
  reject(()->new Arc7PodcastLibrary.Show("Bad\nname","","https://example.com/feed"),"control characters in show metadata");
  List<Arc7PodcastLibrary.Show> directory=Arc7PodcastLibrary.parseDirectory("{\"results\":[{\"collectionName\":\"One\",\"artistName\":\"Publisher\",\"feedUrl\":\"https://example.com/feed\"},{\"collectionName\":\"Duplicate\",\"feedUrl\":\"HTTPS://EXAMPLE.COM:443/feed\"},{\"feedUrl\":\"javascript:bad\"},null]}");
  check(directory.size()==1&&directory.get(0).publisher.equals("Publisher"),"directory entries validated/deduplicated before favourite actions");
  sp.fail=true;reject(()->Arc7PodcastLibrary.setFavorite(c,one,true),"failed preference commit reported as success");sp.fail=false;
  sp.edit().putString("favorite_podcasts_v1","not json").commit();reject(()->Arc7PodcastLibrary.setFavorite(c,one,true),"corrupt saved list silently replaced");
  check(sp.getString("favorite_podcasts_v1","").equals("not json"),"corrupt data retained for recovery");
  sp.edit().putString("favorite_podcasts_v1","[]").commit();for(int i=0;i<200;i++)Arc7PodcastLibrary.setFavorite(c,new Arc7PodcastLibrary.Show("Show "+i,"","https://example.com/"+i),true);
  reject(()->Arc7PodcastLibrary.setFavorite(c,one,true),"library capacity");check(Arc7PodcastLibrary.load(c).size()==200,"capacity failure leaves saved favourites intact");
  System.out.println("PASS podcast favourites: serialized persistence, stable feed identities/dedup/removal, local accent/case/token filtering, bad URLs/corruption/commit failures and 200-show bound");
 }
 static void headers(){
  for(String mode:new String[]{null,"Songs","Artists","Community"}){
   final int[] calls={0,0};Arc7MediaHeader h=new Arc7MediaHeader(new Context(),new Arc7Skin.Palette(),1,"YouTube Music","Search",mode,()->calls[0]++,()->calls[1]++);
   check(h.orientation==LinearLayout.VERTICAL&&h.getChildCount()==2,"search must have a dedicated row");
   LinearLayout brand=(LinearLayout)h.getChildAt(0),search=(LinearLayout)h.getChildAt(1);
   check(brand.getChildCount()==3&&search.getChildCount()==(mode==null?2:3),"only branding/More above input, optional filter and Go");
   TextView title=(TextView)brand.getChildAt(1);check(title.singleLine&&title.ellipsize!=null&&((LinearLayout.LayoutParams)title.params).weight==1,"title shrinks instead of crowding search/actions");
   for(int width:new int[]{320,360,600,1024}){
    h.measureForWidth(width);check(h.getSearchInput().measuredWidth>=(mode==null?240:148),"input collapsed at "+width+"dp");
    for(View row:new View[]{brand,search})for(View child:((LinearLayout)row).children)check(child.measuredWidth>=0,"negative child allocation");
    check(brand.getChildAt(2).measuredWidth>=48&&brand.getChildAt(2).params.height>=48,"overflow tap target");
    check(search.getChildAt(search.getChildCount()-1).measuredWidth>=48,"search tap target");
   }
   brand.getChildAt(2).performClick();check(calls[1]==1,"overflow callback lost");search.getChildAt(search.getChildCount()-1).performClick();
   EditText input=h.getSearchInput();check(input.editor.onEditorAction(input,EditorInfo.IME_ACTION_SEARCH,null),"IME search not handled");
   check(!input.editor.onEditorAction(input,0,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER)),"double-submit key down");
   check(input.editor.onEditorAction(input,0,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))&&calls[0]==3,"Go/IME/Enter callbacks");
   check((h.getModeButton()==null)==(mode==null),"optional search type");
  }
  System.out.println("PASS production media header: two-row structure, 320/360/600/1024dp recorded allocations, weighted/ellipsized title, 48dp actions and Go/IME/Enter/More callbacks");
 }
 public static void main(String[] args)throws Exception{podcasts();headers();}
}
'''


def integration_checks():
    podcasts = (SRC / 'Arc7RadioRssActivity.java').read_text()
    filtering = podcasts.split('private void renderPodcastFavorites()', 1)[1].split('private void setPodcastFavorite(', 1)[0]
    assert 'Arc7PodcastLibrary.filter(' in filtering
    assert 'fetchString' not in filtering and 'new Thread' not in filtering
    for hook in ('afterTextChanged', 'reloadPodcastFavorites()', 'setPodcastFavorite(show,', 'currentPodcastRequest(request)', 'podcastThread.interrupt()', 'podcastAlive = false'):
        assert hook in podcasts, hook
    video = (SRC / 'YouTubeActivity.java').read_text()
    music = (SRC / 'YouTubeMusicActivity.java').read_text()
    for activity in (video, music):
        assert 'new Arc7MediaHeader(' in activity and 'topBar.addView' not in activity
        assert 'getSearchInput()' in activity and 'showHeaderOptions()' in activity
    assert 'openYouTubeMusicApp(); else showMorphePatchesDialog();' in video
    for action in ('showEqualizerDialog()', 'showSleepTimerDialog()', 'showMorpheMusicSettings()'):
        assert action in music.split('private void showHeaderOptions()', 1)[1].split('private void performMusicSearch(', 1)[0]
    assert '"songs", "artists", "albums", "playlists", "community_playlists"' in music
    assert '"artists".equals(mode) ? null' in music  # An 11-letter artist name is not a video id.
    assert 'if(it.isArtist()) { openMusicArtist(it); return; }' in music
    assert 'if(!item.isBrowsable() &&' in music
    assert 'if (!alive || request != musicRequest) return;' in music
    print('PASS discovery integration: local favourites watcher, saved-show actions, stale/destroy guards, both compact headers/overflow actions and artist browse-vs-queue routing')


def main():
    tools = Path(os.environ.get('TOOLCHAIN_DIR') or ('/opt/toolchain' if Path('/opt/toolchain/jdk/bin/java').is_file() else ROOT / 'build/toolchain'))
    java = os.environ.get('JAVA') or (str(tools / 'jdk/bin/java') if (tools / 'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(tools / 'jdk/bin/javac') if (tools / 'jdk/bin/javac').is_file() else shutil.which('javac'))
    if not java or not javac:
        raise SystemExit('Run scripts/bootstrap-toolchain.sh or install a JDK')
    with tempfile.TemporaryDirectory(prefix='arc7-discovery-') as directory:
        temp = Path(directory); sources = []
        for name, text in STUBS.items():
            path = temp / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text); sources.append(path)
        driver = temp / 'DiscoveryHarness.java'; driver.write_text(HARNESS)
        sources += [driver, SRC / 'Arc7PodcastLibrary.java', SRC / 'Arc7MediaHeader.java']
        sources += list((ROOT / 'tests/support/org/json').glob('*.java'))
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(temp / 'classes'), *map(str, sources)], check=True)
        subprocess.run([java, '-cp', str(temp / 'classes'), 'ca.arc7freedom.app.DiscoveryHarness'], check=True, timeout=60)
    integration_checks()
    print('ALL MEDIA DISCOVERY REGRESSIONS PASSED (host preferences/layout fakes, not physical UI or live-provider validation)')


if __name__ == '__main__':
    main()
