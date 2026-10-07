"""Replay the profile's Bio draw hook and actual fade helper on a small JVM."""
from pathlib import Path
import os, subprocess, tempfile, unittest
from test_profile_transform_replay import extract_class, JDK
ROOT=Path(os.environ.get('PROFILE_UI_SOURCE_ROOT',Path(__file__).resolve().parents[1]))
JAVA=ROOT/'TMessagesProj/src/main/java'

class BioFadeReplay(unittest.TestCase):
    def test_translucent_bio_removes_only_gradient_and_restores_native_background(self):
        profile=(JAVA/'org/telegram/ui/ProfileActivity.java').read_text()
        start=profile.index('view = aboutLinkCell = new AboutLinkCell(')
        branch=profile[start:profile.index('case VIEW_TYPE_TEXT:',start)]
        hook='public void draw(Canvas canvas) { super.draw(canvas); }'
        if 'public void draw(Canvas canvas)' in branch:
            hook=extract_class(branch,'public void draw(Canvas canvas)')
        helper=(JAVA/'tw/nekomimi/nekogram/helpers/CustomProfileHelper.java').read_text()
        section=''
        if '// BEGIN BIO FADE' in helper:
            section=helper.split('// BEGIN BIO FADE',1)[1].split('// END BIO FADE',1)[0]
        section=section.replace('org.telegram.ui.Cells.AboutLinkCell','AboutLinkCell').replace('android.graphics.drawable.Drawable','Drawable').replace('android.graphics.Color.alpha','colorAlpha')
        template='''
import java.util.WeakHashMap;
public class BioFadeHarness {
    @interface Nullable {}
    static class Canvas {int gradientDraws, textDraws;}
    static class Drawable {}
    static class View {Drawable bg; Drawable getBackground(){return bg;} void setBackground(Drawable d){bg=d;}}
    static class AboutLinkCell extends View {
        private View showMoreTextBackgroundView=new View();
        AboutLinkCell(){showMoreTextBackgroundView.bg=new Drawable();}
        public void draw(Canvas c){if(showMoreTextBackgroundView.bg!=null)c.gradientDraws++; c.textDraws++;}
    }
    static class Theme {static int key_windowBackgroundWhite=1; static int getColor(int k){return 0xff000000;}}
    static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
    static class CustomProfileHelper {
        static boolean enabled=true; static int surface=0;
        static boolean isEnabled(){return enabled;} static int themedColor(int k,int fallback){return enabled?surface:fallback;}
        static int colorAlpha(int color){return color>>>24;}
        // HELPERS
    }
    static class ProfileBio extends AboutLinkCell {
        // DRAW_HOOK
    }
    static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    public static void main(String[] args){
        ProfileBio bio=new ProfileBio(); View fade=((AboutLinkCell)bio).showMoreTextBackgroundView;
        Drawable nativeGradient=fade.bg;
        Canvas c=new Canvas(); bio.draw(c);
        check(c.gradientDraws==0,"transparent Bio must not paint the native show-more gradient");
        check(c.textDraws==1,"Bio/more label still draws");
        bio.draw(new Canvas()); check(fade.bg==null,"repeated draw remains transparent");
        CustomProfileHelper.surface=0x80000000; bio.draw(new Canvas()); check(fade.bg==null,"translucent row stays clear");
        CustomProfileHelper.surface=0xff000000; bio.draw(new Canvas()); check(fade.bg==nativeGradient,"opaque row restores original drawable");
        CustomProfileHelper.surface=0; bio.draw(new Canvas()); CustomProfileHelper.enabled=false;
        bio.draw(new Canvas()); check(fade.bg==nativeGradient,"disabled look restores original drawable");
        CustomProfileHelper.enabled=true; bio.draw(new Canvas());
        Drawable replacement=new Drawable(); fade.bg=replacement;
        CustomProfileHelper.enabled=false; bio.draw(new Canvas());
        check(fade.bg==replacement,"native replacement is not overwritten on restore");
        System.out.println("PASS: transparent/translucent/opaque/disabled Bio and native replacement");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bio-fade-',dir=os.environ.get('TMPDIR')) as directory:
            path=Path(directory)/'BioFadeHarness.java'
            path.write_text(template.replace('// HELPERS',section).replace('// DRAW_HOOK',hook))
            r=subprocess.run([str(JDK/'bin/javac'),'-J-Xmx64m','-d',directory,str(path)],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,r.returncode,r.stderr)
            r=subprocess.run([str(JDK/'bin/java'),'-Xmx32m','-cp',directory,'BioFadeHarness'],capture_output=True,text=True,timeout=10)
            self.assertEqual(0,r.returncode,r.stdout+r.stderr)
            self.assertIn('PASS:',r.stdout)

if __name__=='__main__':unittest.main()
