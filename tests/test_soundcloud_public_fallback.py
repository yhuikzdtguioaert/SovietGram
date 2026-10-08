"""Removal migration executes the actual production parser in a bounded JVM."""
from pathlib import Path
import os, subprocess, tempfile, unittest
from test_soundcloud_protocol import method
ROOT=Path(__file__).resolve().parents[1]
HELPERS=ROOT/'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers'
class SoundCloudRemovalParser(unittest.TestCase):
    def test_removed_providers_drop_supported_ids_bindings_and_normal_links_survive(self):
        source=(HELPERS/'CustomProfileExtraRows.java').read_text()
        read=method(source,'private static Block read(JSONObject o)').replace('org.json.JSONException','RuntimeException')
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
  static String key(int i){return new String[]{"lastfm","github","steam","yamusic","spotify","soundcloud","soundcloud-me","soundcloud-live"}[i];}
  static int modeCount(int i){return i==7?1:i==5?5:4;}
 }
 static String trim(String v,int max){return v==null?"":v.trim().substring(0,Math.min(max,v.trim().length()));}
 static int color(JSONObject o,String k){return o.optInt(k);}
 static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
 READ_METHOD

 public static void main(String[] args){
  JSONObject accounts=new JSONObject().put("soundcloud","legacy").put("soundcloud-me","legacy-private").put("spotify","spotify-user");
  JSONObject input=new JSONObject().put("type",12).put("url","identity").put("mode",1).put("parts",new JSONArray().put(1).put(0)).put("accounts",accounts);
  for(int service:new int[]{5,6,7,8,-1}) {
   input.put("service",service);if(read(input)!=null)throw new AssertionError("Removed/invalid provider survives: "+service);
  }
  for(int service=0;service<5;service++) {
   input.put("service",service);Block b=read(input);
   if(b==null||b.service!=service||!b.url.equals("identity")||b.parts.optInt(0,-1)!=1)throw new AssertionError("Supported provider changed: "+service);
   if(b.accounts.m.containsKey("soundcloud")||b.accounts.m.containsKey("soundcloud-me"))throw new AssertionError("retained removed provider account");
   if(!b.accounts.optString("spotify").equals("spotify-user"))throw new AssertionError("lost supported provider binding");
  }
  input.put("type",0).put("service",7).put("url","https://soundcloud.com/normal-link");
  Block link=read(input);if(link==null||!link.url.equals("https://soundcloud.com/normal-link"))throw new AssertionError("ordinary link must survive");
 }

}
'''.replace('READ_METHOD',read)
        with tempfile.TemporaryDirectory(prefix='public-sc-', dir=os.environ.get('TMPDIR','/home/user/.hermes/cache/scratch')) as folder:
            java=Path(folder)/'PublicProfileSeam.java';java.write_text(code)
            compilation=subprocess.run(['javac','-J-Xmx96m',str(java)],capture_output=True,text=True)
            self.assertEqual(compilation.returncode,0,compilation.stderr)
            result=subprocess.run(['java','-Xmx64m','-cp',folder,'PublicProfileSeam'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
