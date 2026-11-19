#!/usr/bin/env python3
"""Exercise the production glass drawable with recording Android graphics fakes.

Asserts translucent layers, two-tone rims, pressed/alpha behavior, bounded wallpaper
frost and integration. Not a GPU screenshot or physical Kobo visual test.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT/'shared/java/ca/arc7freedom/glass/LiquidGlassDrawable.java'
STUBS = {
    'android/R.java': 'package android;public class R{public static class attr{public static final int state_pressed=1,state_focused=2;}}',
    'android/content/Context.java': 'package android.content;public class Context{}',
    'android/util/DisplayMetrics.java': 'package android.util;public class DisplayMetrics{public int widthPixels=640,heightPixels=480;}',
    'android/graphics/ColorFilter.java': 'package android.graphics;public class ColorFilter{}',
    'android/graphics/PixelFormat.java': 'package android.graphics;public class PixelFormat{public static final int OPAQUE=-1,TRANSLUCENT=-3;}',
    'android/graphics/Color.java': '''package android.graphics;public class Color{public static int red(int c){return(c>>>16)&255;}public static int green(int c){return(c>>>8)&255;}public static int blue(int c){return c&255;}public static int rgb(int r,int g,int b){return 0xff000000|(r<<16)|(g<<8)|b;}}''',
    'android/graphics/Rect.java': '''package android.graphics;public class Rect{public int left,top,right,bottom;public Rect(){}public Rect(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}public int width(){return right-left;}public int height(){return bottom-top;}}''',
    'android/graphics/RectF.java': '''package android.graphics;public class RectF{public float left,top,right,bottom;public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public void set(RectF r){set(r.left,r.top,r.right,r.bottom);}public void offset(float x,float y){left+=x;right+=x;top+=y;bottom+=y;}public void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}public float width(){return right-left;}public float height(){return bottom-top;}}''',
    'android/graphics/Matrix.java': '''package android.graphics;public class Matrix{public float scaleX,scaleY,x,y;public void setScale(float x,float y){scaleX=x;scaleY=y;this.x=this.y=0;}public void postTranslate(float x,float y){this.x+=x;this.y+=y;}}''',
    'android/graphics/Shader.java': 'package android.graphics;public class Shader{public enum TileMode{CLAMP}}',
    'android/graphics/LinearGradient.java': '''package android.graphics;public class LinearGradient extends Shader{public final int[] colors;public LinearGradient(float x,float y,float x2,float y2,int[] c,float[] p,TileMode t){colors=c;}}''',
    'android/graphics/RadialGradient.java': '''package android.graphics;public class RadialGradient extends Shader{public final int[] colors;public RadialGradient(float x,float y,float r,int[] c,float[] p,TileMode t){if(r<=0)throw new IllegalArgumentException();colors=c;}}''',
    'android/graphics/BitmapShader.java': '''package android.graphics;public class BitmapShader extends Shader{public Matrix matrix;public BitmapShader(Bitmap b,TileMode x,TileMode y){}public void setLocalMatrix(Matrix m){matrix=m;}}''',
    'android/graphics/Paint.java': '''package android.graphics;public class Paint{public enum Style{STROKE,FILL}public int color,alpha=255;public Shader shader;public ColorFilter filter;public Style style;public float width;public Paint(int f){}public Paint(Paint p){color=p.color;alpha=p.alpha;shader=p.shader;style=p.style;width=p.width;filter=p.filter;}public void setStyle(Style s){style=s;}public void setStrokeWidth(float f){width=f;}public void setColor(int c){color=c;alpha=c>>>24;}public void setShader(Shader s){shader=s;}public void setAlpha(int a){alpha=a;}public void setColorFilter(ColorFilter f){filter=f;}}''',
    'android/graphics/Bitmap.java': '''package android.graphics;public class Bitmap{public enum Config{ARGB_8888}private int w,h;private int[] p;public static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.w=w;b.h=h;b.p=new int[w*h];return b;}public int getWidth(){return w;}public int getHeight(){return h;}public boolean isRecycled(){return false;}public void getPixels(int[] out,int o,int stride,int x,int y,int w,int h){System.arraycopy(p,0,out,0,p.length);}public void setPixels(int[] in,int o,int stride,int x,int y,int w,int h){System.arraycopy(in,0,p,0,p.length);}}''',
    'android/graphics/Canvas.java': '''package android.graphics;import java.util.*;public class Canvas{public static final int ALL_SAVE_FLAG=31;public static class Draw{public Paint paint;public float radius;Draw(Paint p,float r){paint=new Paint(p);radius=r;}}public List<Draw> draws=new ArrayList<Draw>();public int layerAlpha=255,restores;public Canvas(){}public Canvas(Bitmap b){}public int saveLayerAlpha(float l,float t,float r,float b,int a,int flags){layerAlpha=a;return 1;}public void restoreToCount(int n){restores++;}public void drawRoundRect(RectF r,float x,float y,Paint p){draws.add(new Draw(p,x));}public void drawRect(Rect r,Paint p){draws.add(new Draw(p,0));}}''',
    'android/graphics/drawable/Drawable.java': '''package android.graphics.drawable;import android.graphics.*;public abstract class Drawable{public interface Callback{}private Rect bounds=new Rect();private Callback callback;public abstract void draw(Canvas c);public abstract void setAlpha(int a);public abstract void setColorFilter(ColorFilter f);public abstract int getOpacity();protected void onBoundsChange(Rect r){}protected boolean onStateChange(int[] s){return false;}public boolean setState(int[] s){return onStateChange(s);}public boolean isStateful(){return false;}public void invalidateSelf(){}public Rect getBounds(){return bounds;}public void setBounds(int l,int t,int r,int b){bounds.left=l;bounds.top=t;bounds.right=r;bounds.bottom=b;onBoundsChange(bounds);}public void setBounds(Rect r){setBounds(r.left,r.top,r.right,r.bottom);}public int getIntrinsicWidth(){return 2000;}public int getIntrinsicHeight(){return 1000;}public void setCallback(Callback c){callback=c;}public Callback getCallback(){return callback;}}''',
    'android/graphics/drawable/GradientDrawable.java': '''package android.graphics.drawable;import android.graphics.*;public class GradientDrawable extends Drawable{public void draw(Canvas c){}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return -1;}public void setCornerRadius(float r){}}''',
    'android/app/WallpaperManager.java': '''package android.app;import android.content.*;import android.graphics.drawable.*;public class WallpaperManager{public static int reads;public static Drawable wallpaper=new GradientDrawable();public static WallpaperManager getInstance(Context c){return new WallpaperManager();}public Drawable getDrawable(){reads++;return wallpaper;}}''',
    'android/view/View.java': '''package android.view;public class View implements android.graphics.drawable.Drawable.Callback{public int x=31,y=47;public void getLocationOnScreen(int[] p){p[0]=x;p[1]=y;}}''',
}
HARNESS = '''import ca.arc7freedom.glass.LiquidGlassDrawable;import android.graphics.*;import android.graphics.drawable.*;import android.util.*;
public class GlassHarness{static void check(boolean b,String m){if(!b)throw new AssertionError(m);}public static void main(String[] args){
 LiquidGlassDrawable g=new LiquidGlassDrawable(0xff132638,0xff22d3ee,60,2,108);g.setBounds(0,0,400,200);Canvas c=new Canvas();g.draw(c);
 check(c.draws.size()==6,"Expected lift/body/sheen/rim/inner lens layers");check(c.draws.get(2).paint.alpha==108,"Glass body became opaque");
 check(((LinearGradient)c.draws.get(3).paint.shader).colors[0]>>>24<100,"Sheen overwhelms transmitted background");
 int[] rim=((LinearGradient)c.draws.get(4).paint.shader).colors;check((rim[0]>>>24)>(rim[1]>>>24)&&rim[0]!=rim[3],"No directional specular rim");
 check(g.getOpacity()==PixelFormat.TRANSLUCENT&&g.isStateful(),"Material flags");g.setState(new int[]{android.R.attr.state_pressed});c=new Canvas();g.draw(c);check(c.draws.size()==7,"No pressed lens highlight");
 g.setState(new int[0]);g.setAlpha(64);c=new Canvas();g.draw(c);check(c.layerAlpha==64&&c.restores==1,"Drawable alpha/layer restore broken");
 Bitmap frost=LiquidGlassDrawable.frostedWallpaper(new android.content.Context());check(frost.getWidth()==160&&frost.getHeight()==80,"Wallpaper blur is not bounded");
 int captures=android.app.WallpaperManager.reads;g=new LiquidGlassDrawable(0xff102030,0xff00bbdd,90,1,110).withBackdrop(frost,new DisplayMetrics());g.setCallback(new android.view.View());g.setBounds(0,0,300,60);
 for(int i=0;i<100;i++){c=new Canvas();g.draw(c);}check(android.app.WallpaperManager.reads==captures,"Wallpaper captured during draw");check(c.draws.size()==7&&c.draws.get(2).paint.shader instanceof BitmapShader,"Cached frost not rendered");
 for(Canvas.Draw d:c.draws)check(d.radius<=30,"Capsule radius exceeds bounds");
 BitmapShader shader=(BitmapShader)c.draws.get(2).paint.shader;check(shader.matrix.x==-191&&shader.matrix.y==-47,"Backdrop is not screen-aligned");
 LiquidGlassDrawable clear=new LiquidGlassDrawable(0xff102030,0xff00bbdd,30,1,0).withBackdrop(frost,new DisplayMetrics());clear.setBounds(0,0,200,70);c=new Canvas();clear.draw(c);check(c.draws.get(2).paint.alpha==0&&c.draws.get(3).paint.alpha==0,"Crystal-clear opacity override ignored");
 Drawable ambient=LiquidGlassDrawable.ambient(0xff090e16,0xff22d3ee);ambient.setBounds(0,0,600,1000);c=new Canvas();ambient.draw(c);check(c.draws.get(0).paint.shader instanceof RadialGradient,"No ambient backdrop");
 android.app.WallpaperManager.wallpaper=null;check(LiquidGlassDrawable.frostedWallpaper(new android.content.Context())==null,"Missing wallpaper fallback");
 System.out.println("PASS: production liquid-glass translucent body, specular lens rims, pressed/alpha/clear states, bounded cached wallpaper frost and screen alignment (recording graphics fakes)");}}
'''

def main():
    default_tools = Path('/opt/toolchain') if Path('/opt/toolchain/jdk/bin/java').is_file() else ROOT/'build/toolchain'
    toolchain = Path(os.environ.get('TOOLCHAIN_DIR') or default_tools)
    java = os.environ.get('JAVA') or (str(toolchain/'jdk/bin/java') if (toolchain/'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(toolchain/'jdk/bin/javac') if (toolchain/'jdk/bin/javac').is_file() else shutil.which('javac'))
    if not java or not javac:
        raise SystemExit('Run scripts/bootstrap-toolchain.sh or install a JDK')
    with tempfile.TemporaryDirectory(prefix='arc7-glass-') as directory:
        temp = Path(directory); sources = []
        for name,text in STUBS.items():
            path = temp/name; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(text); sources.append(path)
        harness = temp/'GlassHarness.java'; harness.write_text(HARNESS); sources += [SOURCE,harness]
        subprocess.run(list(map(str,[javac,'-nowarn','-d',temp/'classes',*sources])),check=True)
        subprocess.run(list(map(str,[java,'-cp',temp/'classes','GlassHarness'])),check=True)
    source = SOURCE.read_text()
    for unsupported in ('RenderEffect', 'clipPath(', 'setElevation(', 'postInvalidateDelayed(', 'java.nio.file'):
        assert unsupported not in source.replace("RenderEffect,", ""), unsupported
    shared = (ROOT/'app/src/main/java/ca/arc7freedom/app/Arc7Skin.java').read_text()
    assert 'glassTint' not in shared and 'new LiquidGlassDrawable' in shared
    for name in ('cardDrawable', 'elevatedDrawable', 'headerDrawable', 'buttonDrawable'):
        body = shared.split(' GradientDrawable '+name,1)[1].split('\n    }',1)[0]
        assert 'new LiquidGlassDrawable' in body,name
    launcher = (ROOT/'launcher-android4/src/main/java/ca/arc7freedom/launcher/VanillaHomeActivity.java').read_text()
    assert 'return liquidGlass(t.dockBottomColor' in launcher and 'liquidGlass(t.topCapsuleColor' in launcher
    assert 'ACTION_WALLPAPER_CHANGED' in launcher and 'if (glassWallpaper == null)' in launcher
    for gradle in ('app/build.gradle','launcher-android4/build.gradle'):
        assert '/shared/java' in (ROOT/gradle).read_text()
    print('ALL LIQUID-GLASS REGRESSIONS PASSED (Android graphics fakes, not GPU/device visual validation)')

if __name__ == '__main__':
    main()
