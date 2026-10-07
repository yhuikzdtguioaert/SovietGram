"""Bounded JVM parser seam plus UI contracts; no provider requests/Android build."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from test_soundcloud_protocol import method

ROOT = Path(__file__).resolve().parents[1]
HELPERS = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers'
UI = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/CustomProfileBlocksActivity.java'

class SoundCloudPublicFallback(unittest.TestCase):
    def test_public_profile_survives_read_without_becoming_authenticated(self):
        source = (HELPERS / 'CustomProfileExtraRows.java').read_text()
        read = method(source, 'private static Block read(JSONObject o)').replace('org.json.JSONException', 'RuntimeException')
        old_mapping = method(source, 'private static int soundcloudAccountMode(int mode)') if 'private static int soundcloudAccountMode' in source else ''
        code = r'''
import java.util.*;
public class PublicProfileSeam {
 static class JSONObject {
  Map<String,Object> m=new HashMap<>();
  JSONObject put(String k,Object v){m.put(k,v);return this;}
  int optInt(String k){return optInt(k,0);} int optInt(String k,int d){return ((Number)m.getOrDefault(k,d)).intValue();}
  long optLong(String k){return ((Number)m.getOrDefault(k,0)).longValue();}
  String optString(String k){return optString(k,"");} String optString(String k,String d){return (String)m.getOrDefault(k,d);}
  boolean optBoolean(String k,boolean d){return (Boolean)m.getOrDefault(k,d);}
  JSONObject optJSONObject(String k){return (JSONObject)m.get(k);} JSONArray optJSONArray(String k){return (JSONArray)m.get(k);}
 }
 static class JSONArray {
  List<Integer> a=new ArrayList<>(); JSONArray put(int v){a.add(v);return this;}
  int length(){return a.size();} int optInt(int i,int d){return i>=0&&i<a.size()?a.get(i):d;}
 }
 static class Block {
  String id,title,url,text,icon,mediaPath,media;
  int type,iconColor,iconBackground,titleColor,valueColor,action,longAction,radius,mediaHeight,viewX,viewY,viewSpan,service,intStyle,intRefresh,mode;
  boolean divider,ownOnly; long emoji; JSONArray parts=new JSONArray(); JSONObject accounts=new JSONObject();
 }
 static final int TYPE_LINK=0,TYPE_INTEGRATION=12,MAX_TITLE=64,MAX_URL=512,MAX_TEXT=1024,ACTION_OPEN=1,ACTION_NONE=0,RADIUS_DEFAULT=12,MEDIA_HEIGHT_DEFAULT=160,MEDIA_HEIGHT_MIN=60,MEDIA_HEIGHT_MAX=400;
 static class CustomProfileIntegrations {
  static String key(int i){return new String[]{"lastfm","github","steam","yamusic","spotify","soundcloud","soundcloud-me"}[i];}
  static int modeCount(int i){return i==5?5:4;}
 }
 static String trim(String v,int max){return v==null?"":v.trim().substring(0,Math.min(max,v.trim().length()));}
 static int color(JSONObject o,String k){return o.optInt(k);}
 static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
 READ_METHOD
 OLD_MAPPING
 public static void main(String[] args){
  JSONObject accounts=new JSONObject().put("soundcloud","public-user").put("soundcloud-me","existing-private-id");
  JSONObject input=new JSONObject().put("type",12).put("service",5).put("url","https://soundcloud.com/public-user").put("mode",4).put("parts",new JSONArray().put(4).put(2)).put("accounts",accounts);
  Block b=read(input);
  if(b.service!=5 || !b.url.equals("https://soundcloud.com/public-user") || b.mode!=4 || b.parts.optInt(0,-1)!=4 || b.parts.optInt(1,-1)!=2)
   throw new AssertionError("Public profile identity/modes must survive storage; never force login or substitute private account ID");
  input.put("service",6).put("url","existing-private-id").put("mode",1).put("parts",new JSONArray().put(1));
  b=read(input);if(b.service!=6 || !b.url.equals("existing-private-id"))throw new AssertionError("Existing signed-in block must remain intact");
  input.put("service",7);if(read(input)!=null)throw new AssertionError("Unknown provider must still be rejected");
 }
}
'''.replace('READ_METHOD',read).replace('OLD_MAPPING',old_mapping)
        with tempfile.TemporaryDirectory(prefix='public-sc-', dir=os.environ.get('TMPDIR','/home/user/.hermes/cache/scratch')) as folder:
            java=Path(folder)/'PublicProfileSeam.java';java.write_text(code)
            compilation=subprocess.run(['javac','-J-Xmx96m',str(java)],capture_output=True,text=True)
            self.assertEqual(compilation.returncode,0,compilation.stderr)
            result=subprocess.run(['java','-Xmx64m','-cp',folder,'PublicProfileSeam'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_public_switch_preserves_connection_and_checks_owner_and_target(self):
        source = UI.read_text()
        signature = 'private void usePublicSoundcloud(String id, long owner)'
        self.assertIn(signature, source, 'Public fallback must be actionable, not just advice')
        switch = method(source, signature).replace('org.json.JSONException', 'RuntimeException')
        code = r'''
import java.util.*;
public class PublicSwitchSeam {
 static class JSONObject {Map<String,String> m=new HashMap<>();void put(String k,String v){m.put(k,v);}String optString(String k){return m.getOrDefault(k,"");}}
 static class org {static class json {static class JSONArray {List<Integer> a=new ArrayList<>();void put(int v){a.add(v);}}}}
 static class CustomProfileExtraRows {
  static final int TYPE_INTEGRATION=12;static int stores;static List<Block> blocks=new ArrayList<>();
  static class Block {String id="target",url="private-id";int type=12,service=6,mode=3;JSONObject accounts=new JSONObject();org.json.JSONArray parts=new org.json.JSONArray();}
  static List<Block> stored(){return blocks;}static void store(List<Block> b){stores++;}
 }
 static class CustomProfileIntegrations {static String key(int s){return s==5?"soundcloud":"soundcloud-me";}static void clearCache(){}}
 boolean live=true;boolean sameOwner(long owner){return live&&owner==123;}void rebuild(){}
 SWITCH_METHOD
 public static void main(String[]args){
  PublicSwitchSeam ui=new PublicSwitchSeam();CustomProfileExtraRows.Block b=new CustomProfileExtraRows.Block();
  b.accounts.put("soundcloud","public-user");CustomProfileExtraRows.blocks.add(b);
  ui.live=false;ui.usePublicSoundcloud("target",123);if(b.service!=6)throw new AssertionError("stale owner switched");
  ui.live=true;ui.usePublicSoundcloud("deleted",123);if(CustomProfileExtraRows.stores!=0)throw new AssertionError("missing target wrote");
  ui.usePublicSoundcloud("target",123);
  if(b.service!=5||!b.url.equals("public-user")||!b.accounts.optString("soundcloud-me").equals("private-id")||b.mode!=0||b.parts.a.size()!=1||b.parts.a.get(0)!=0)throw new AssertionError("fallback must preserve saved connection and reset public modes");
  ui.usePublicSoundcloud("target",123);if(CustomProfileExtraRows.stores!=1)throw new AssertionError("stale repeated action wrote");
 }
}
'''.replace('SWITCH_METHOD', switch)
        with tempfile.TemporaryDirectory(prefix='public-switch-',dir=os.environ.get('TMPDIR','/home/user/.hermes/cache/scratch')) as folder:
            java=Path(folder)/'PublicSwitchSeam.java';java.write_text(code)
            result=subprocess.run(['javac','-J-Xmx96m',str(java)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-Xmx64m','-cp',folder,'PublicSwitchSeam'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_public_account_rejects_signin_redirects_and_accepts_only_profile_identity(self):
        source = (HELPERS / 'CustomProfileIntegrations.java').read_text()
        signature = 'public static String publicSoundcloudAccount(String value)'
        self.assertTrue(signature in source, 'Public profile input needs an auth-redirect rejection boundary')
        parse = method(source, signature)
        code = 'public class PublicAccountSeam {\n' + parse + r'''
 public static void main(String[]args){
  for(String value:new String[]{"public-user","https://soundcloud.com/public-user","https://m.soundcloud.com/public-user?si=public-share"})
   if(!publicSoundcloudAccount(value).equals("public-user"))throw new AssertionError("valid public profile rejected");
  for(String value:new String[]{"https://m.soundcloud.com/discover?code=not-a-real-code&state=not-a-real-state","https://soundcloud.com/public-user?code=not-a-real-code","https://soundcloud.com/signin","discover","https://soundcloud.com/public-user/track","https://evil.invalid/public-user","https://user:pass@soundcloud.com/public-user","https://soundcloud.com/public-user#access_token=not-a-real-token"})
   if(!publicSoundcloudAccount(value).isEmpty())throw new AssertionError("non-profile or auth redirect accepted");
 }
}
'''
        with tempfile.TemporaryDirectory(prefix='public-account-',dir=os.environ.get('TMPDIR','/home/user/.hermes/cache/scratch')) as folder:
            java=Path(folder)/'PublicAccountSeam.java';java.write_text(code)
            result=subprocess.run(['javac','-J-Xmx96m',str(java)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-Xmx64m','-cp',folder,'PublicAccountSeam'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_public_alternative_is_selectable_and_unconfigured_login_explains_limitation(self):
        source=UI.read_text()
        self.assertIn('CustomProfileIntegrations.publicSoundcloudAccount(value)', source)
        self.assertIn('private static final int[] SERVICES = {0, 1, 2, 3, 4, 5, 6};',source)
        connect=method(source,'private void connect(CustomProfileExtraRows.Block block)')
        self.assertLess(connect.index('if (service == 6)'),connect.index('SovietGramApiClient.isReady'))
        self.assertIn('CustomProfileIntegrationSoundcloudUnavailable',connect)
        self.assertIn('CustomProfileIntegrationSoundcloudPublic',connect)
        self.assertIn('usePublicSoundcloud(id, owner)',connect)

if __name__=='__main__':unittest.main()
