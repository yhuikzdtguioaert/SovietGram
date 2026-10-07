"""Production-method JVM probes, NOT Android rendering regressions.

These falsify simple arithmetic/stale-state explanations only. They cannot prove
PhotoViewer/GPU/RecyclerView behavior. Run with PROFILE_UI_JAVA_HOME set to a JDK.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java'


def member(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end].replace('@Nullable', '')


class RenderStateProbe(unittest.TestCase):
    def test_production_transform_gooey_and_card_height_replay(self):
        home = os.environ.get('PROFILE_UI_JAVA_HOME')
        javac = str(Path(home) / 'bin/javac') if home else shutil.which('javac')
        java = str(Path(home) / 'bin/java') if home else shutil.which('java')
        if not javac or not java:
            self.skipTest('JDK required')
        profile = (JAVA / 'org/telegram/ui/ProfileActivity.java').read_text()
        header = (JAVA / 'tw/nekomimi/nekogram/helpers/CustomProfileHeaderLayout.java').read_text()
        card = (JAVA / 'tw/nekomimi/nekogram/ui/cells/IntegrationCardView.java').read_text()
        methods = member(header, 'private static final class Transform')
        gooey = member(profile, 'private void updateGooey()')
        heights = member(card, 'private float pitch(int width)') + '\n' + member(card, 'public int heightFor(int width)')
        source = r'''
public class RenderStateProbeHarness {
  static class View {
    static final int VISIBLE=0, GONE=8;
    float x,y,r,sx=1,sy=1,alpha=1; int visibility=0;
    float getTranslationX(){return x;} float getTranslationY(){return y;}
    float getRotation(){return r;} float getScaleX(){return sx;} float getScaleY(){return sy;}
    void setTranslationX(float v){x=v;} void setTranslationY(float v){y=v;}
    void setRotation(float v){r=v;} void setScaleX(float v){sx=v;} void setScaleY(float v){sy=v;}
    void setAlpha(float v){alpha=v;} void setVisibility(int v){visibility=v;}
  }
  static class Element { float rotate,scaleX,scaleY;
    Element(float r,float x,float y){rotate=r;scaleX=x;scaleY=y;}
  }
  // TRANSFORM
  static class MathUtils { static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));} }
  static class Gooey extends View { float pull,blur; boolean enabled;
    void setPullProgress(float v){pull=v;} void setBlurIntensity(float v){blur=v;}
    void setGooeyEnabled(boolean v){enabled=v;}
  }
  static class Profile {
    float pullUpProgress; boolean isTopic; int playProfileAnimation;
    Gooey avatarGooey=new Gooey(); View storyView=new View();
    static float lerp(float a,float b,float p){return a+(b-a)*p;}
    static float ilerp(float p,float a,float b){return (p-a)/(b-a);}
    // GOOEY
  }
  static class Rich { Object graph,track; }
  static class Card {
    float density; int weekCount=53; Rich rich=new Rich();
    Card(float d){density=d;}
    int dp(float v){return (int)Math.ceil(v*density);}
    int weeks(){return weekCount;}
    // HEIGHTS
  }
  static void check(boolean good,String label){if(!good)throw new AssertionError(label);}
  static void near(float got,float expected,String label){check(Math.abs(got-expected)<.01f,label+" got="+got);}
  public static void main(String[] args){
    View avatar=new View(); avatar.sx=avatar.sy=.96f;
    Transform transform=new Transform(); Element custom=new Element(5,1.3f,1.2f);
    for(int i=0;i<1000;i++)transform.apply(avatar,custom,100,20,1);
    near(avatar.x,100,"no cumulative x"); near(avatar.sx,.96f*1.3f,"no cumulative scale");
    avatar.setScaleX(.24f); avatar.setScaleY(.24f); avatar.setTranslationX(0); avatar.setTranslationY(-29);
    transform.apply(avatar,custom,100,20,0);
    near(avatar.sx,.24f,"collapsed external base"); near(avatar.x,0,"collapsed x");
    avatar.setScaleX(.96f); avatar.setScaleY(.96f); avatar.setTranslationY(70);
    transform.apply(avatar,custom,100,20,1);
    near(avatar.sx,.96f*1.3f,"scroll return scale"); near(avatar.y,90,"scroll return y");
    transform.restore(); near(avatar.sx,.96f,"restored scale"); near(avatar.y,70,"restored y");
    Profile p=new Profile(); p.pullUpProgress=1;p.updateGooey();
    check(p.avatarGooey.visibility==View.GONE,"collapsed gooey gone");
    p.pullUpProgress=.5f;p.updateGooey();check(p.avatarGooey.enabled,"mid pull enabled");
    p.pullUpProgress=0;p.updateGooey();
    check(p.avatarGooey.visibility==View.VISIBLE&&!p.avatarGooey.enabled&&p.avatarGooey.blur==0,"return gooey normal");
    int cases=0;
    for(float density:new float[]{1,1.5f,2,2.625f,3,4})for(int widthDp:new int[]{240,360,412,600}){
      Card a=new Card(density),b=new Card(density);int width=a.dp(widthDp);
      int height=a.heightFor(width), nextTop=height;
      // Stack actual measured row heights; this is NOT a RecyclerView layout.
      int coverBottom=a.dp(6)+a.dp(14)+a.dp(16)+a.dp(10)+a.dp(72);
      check(coverBottom<=height-a.dp(6),"cover within first card");
      check(height-a.dp(6)<nextTop+b.dp(6),"two track backgrounds disjoint");
      a.rich.graph=new Object();a.weekCount=53;int gh=a.heightFor(width);
      int graphBottom=a.dp(6)+a.dp(12)+a.dp(20)+a.dp(8)+a.dp(14)+(int)(7*a.pitch(width));
      check(graphBottom<=gh-a.dp(6),"graph within row");cases++;
    }
    System.out.println("PASS: actual Transform/updateGooey methods; "+cases+" card height cases. Android drawing unverified.");
  }
}
'''.replace('// TRANSFORM', methods).replace('// GOOEY', gooey).replace('// HEIGHTS', heights)
        with tempfile.TemporaryDirectory(prefix='render-probe-') as directory:
            path = Path(directory) / 'RenderStateProbeHarness.java'
            path.write_text(source)
            compiled = subprocess.run([javac, '-J-Xmx64m', '-d', directory, str(path)], capture_output=True, text=True, timeout=45)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            ran = subprocess.run([java, '-Xmx32m', '-cp', directory, 'RenderStateProbeHarness'], capture_output=True, text=True, timeout=15)
            self.assertEqual(0, ran.returncode, ran.stdout + ran.stderr)
            self.assertIn('PASS:', ran.stdout)
            print(ran.stdout.strip())


if __name__ == '__main__':
    unittest.main()
