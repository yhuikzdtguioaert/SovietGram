"""Replay the actual production Transform on a bounded JVM, not Android rendering."""
from pathlib import Path
import os, subprocess, tempfile, unittest
ROOT = Path(os.environ.get('PROFILE_UI_SOURCE_ROOT', Path(__file__).resolve().parents[1]))
SOURCE = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/CustomProfileHeaderLayout.java'
JDK = Path(os.environ.get('PROFILE_UI_JAVA_HOME', '/home/user/.hermes/cache/scratch/preset-test-jdk/jdk-17.0.17-lite'))

def extract_class(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

class TransformReplay(unittest.TestCase):
    def test_subthreshold_transition_preserves_native_scale_and_restores(self):
        transform = extract_class(SOURCE.read_text(), 'private static final class Transform')
        template = '''
public class TransformHarness {
    @interface Nullable {}
    static class View {
        float x, y, r, sx = 1f, sy = 1f;
        float getTranslationX(){return x;} float getTranslationY(){return y;}
        float getRotation(){return r;} float getScaleX(){return sx;} float getScaleY(){return sy;}
        void setTranslationX(float v){x=v;} void setTranslationY(float v){y=v;}
        void setRotation(float v){r=v;} void setScaleX(float v){sx=v;} void setScaleY(float v){sy=v;}
    }
    static class Element { float rotate=0f, scaleX=.5f, scaleY=.5f; }
    // TRANSFORM
    static void close(float expected,float actual,String message){
        if(Math.abs(expected-actual)>.002f)throw new AssertionError(message+": "+actual);
    }
    public static void main(String[] args){
        View avatar=new View(); Transform t=new Transform(); Element half=new Element();
        t.apply(avatar,half,0,0,1f);
        close(.5f,avatar.sx,"initial scale");
        // .0015 is smaller than the setter threshold (.002), but larger than
        // the native-base threshold (.001): no setter must mean no new output.
        t.apply(avatar,half,0,0,.997f);
        for(int i=0;i<20;i++)t.apply(avatar,half,0,0,.997f);
        close(.5f,avatar.sx,"skipped X update must not recapture custom scale as native base");
        close(.5f,avatar.sy,"skipped Y update must not recapture custom scale as native base");
        t.restore(); close(1f,avatar.sx,"restore original X scale"); close(1f,avatar.sy,"restore original Y scale");
        t.apply(avatar,half,0,0,1f); t.apply(avatar,half,0,0,.997f);
        t.restore(); close(1f,avatar.sx,"restore immediately after skipped update");
        // A real native animation update still becomes the base and is preserved.
        t.apply(avatar,half,0,0,1f); avatar.sx=.8f; avatar.sy=.9f;
        t.apply(avatar,half,0,0,1f); t.restore();
        close(.8f,avatar.sx,"native X update retained"); close(.9f,avatar.sy,"native Y update retained");
        System.out.println("PASS: skipped X/Y writes, repeated frames, immediate restore, native updates");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='transform-replay-', dir=os.environ.get('TMPDIR')) as directory:
            java = Path(directory)/'TransformHarness.java'
            java.write_text(template.replace('// TRANSFORM',transform))
            result=subprocess.run([str(JDK/'bin/javac'),'-J-Xmx64m','-d',directory,str(java)],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,result.returncode,result.stderr)
            result=subprocess.run([str(JDK/'bin/java'),'-Xmx32m','-cp',directory,'TransformHarness'],capture_output=True,text=True,timeout=10)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            self.assertIn('PASS:',result.stdout)

if __name__=='__main__': unittest.main()
