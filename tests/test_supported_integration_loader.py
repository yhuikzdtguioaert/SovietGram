"""Retain real loader regressions for supported providers after RPC removal."""
from pathlib import Path
import os, subprocess, tempfile, unittest
ROOT=Path(__file__).resolve().parents[1]
HELPERS=ROOT/'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers'
class SupportedIntegrationLoader(unittest.TestCase):
    def test_supported_provider_loader_cards_cache_and_owner_isolation(self):
        import re
        source=(HELPERS/'CustomProfileIntegrations.java').read_text()
        resources=sorted(set(re.findall(r'R\.string\.(\w+)',source)))
        fields=';'.join('static int '+name+'='+str(i+1) for i,name in enumerate(resources))+';'
        production='\n'.join(line for line in source.splitlines() if not line.startswith(('package ','import ')))
        production=production.replace('public final class CustomProfileIntegrations','static final class CustomProfileIntegrations').replace('android.os.SystemClock','SystemClock')
        code='''
import java.util.*;
import java.util.function.Consumer;
public class LoaderHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class JSONObject {
  Map<String,Object> m=new HashMap<>();JSONObject put(String k,Object v){m.put(k,v);return this;}
  String optString(String k){return optString(k,"");}String optString(String k,String d){return m.get(k) instanceof String?(String)m.get(k):d;}
  int optInt(String k){return optInt(k,0);}int optInt(String k,int d){return m.get(k) instanceof Number?((Number)m.get(k)).intValue():d;}
  long optLong(String k){return m.get(k) instanceof Number?((Number)m.get(k)).longValue():0;}
  boolean optBoolean(String k){return Boolean.TRUE.equals(m.get(k));}JSONObject optJSONObject(String k){return (JSONObject)m.get(k);}JSONArray optJSONArray(String k){return (JSONArray)m.get(k);}boolean isNull(String k){return m.get(k)==null;}
 }
 static class JSONArray {List<Object> a=new ArrayList<>();JSONArray put(Object v){a.add(v);return this;}int length(){return a.size();}int optInt(int i){return optInt(i,0);}int optInt(int i,int d){return i>=0&&i<a.size()&&a.get(i) instanceof Number?((Number)a.get(i)).intValue():d;}JSONObject optJSONObject(int i){return a.get(i) instanceof JSONObject?(JSONObject)a.get(i):null;}}
 static class R {static class string {FIELDS}}
 static class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();}}
 static class SystemClock {static long now=1000;static long elapsedRealtime(){return now;}}
 static class Uri {java.net.URI uri;Uri(String s){uri=java.net.URI.create(s);}static Uri parse(String s){return new Uri(s);}String getHost(){return uri.getHost();}List<String> getPathSegments(){return Arrays.asList(uri.getPath().substring(1).split("/"));}static String encode(String s){return s;}}
 static class LocaleController {static LocaleController getInstance(){return new LocaleController();}static String getString(int r){return "mode";}static class Info {String shortName="en";}Info getCurrentLocaleInfo(){return new Info();}}
 static class UserConfig {static int selectedAccount;static long[] ids={101,202};int a;UserConfig(int a){this.a=a;}static UserConfig getInstance(int a){return new UserConfig(a);}long getClientUserId(){return ids[a];}}
 static class CustomProfileExtraRows {static class Block {String id="supported",url="supported-user";JSONObject accounts=new JSONObject();JSONArray parts=new JSONArray().put(0);int service=4,mode,intRefresh;}}
 static class SovietGramApiClient {interface Callback {void onResult(JSONObject b,String e);}static List<String> calls=new ArrayList<>();static List<Callback> pending=new ArrayList<>();static boolean isReady(int a){return true;}static void get(int a,String path,Callback cb){calls.add(path);pending.add(cb);}}
 static class org {static class telegram {static class messenger {static class Utilities {static Queue globalQueue=new Queue();static class Queue {void postRunnable(Runnable r){r.run();}}}}}}
 PRODUCTION
 static JSONObject response(boolean playing,String url){return new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("mode",0).put("value","Actual Artist — Actual Song").put("track",new JSONObject().put("title","Actual Song").put("artist","Actual Artist").put("durationMs",90000L).put("progressMs",33000L).put("playing",playing).put("url",url))));}
 static void answer(JSONObject body){SovietGramApiClient.pending.remove(0).onResult(body,null);}
 public static void main(String[]args){
  for(int id:new int[]{-1,5,6,7,8}) {
   CustomProfileExtraRows.Block removed=new CustomProfileExtraRows.Block();removed.service=id;
   List<CustomProfileIntegrations.Rich> rejected=new ArrayList<>();CustomProfileIntegrations.loadRich(0,101,removed,rejected::add);
   check(SovietGramApiClient.calls.isEmpty()&&rejected.size()==1&&!rejected.get(0).hasCard(),"removed provider never requests or draws a card");
   check(CustomProfileIntegrations.key(id).isEmpty()&&CustomProfileIntegrations.modeCount(id)==0&&!CustomProfileIntegrations.isConnected(id),"invalid provider never clamps into supported provider");
  }
  for(int id=0;id<5;id++)check(CustomProfileIntegrations.key(id).equals(new String[]{"lastfm","github","steam","yamusic","spotify"}[id]),"stable provider id");
  CustomProfileExtraRows.Block b=new CustomProfileExtraRows.Block();List<CustomProfileIntegrations.Rich> results=new ArrayList<>();
  CustomProfileIntegrations.loadRich(0,101,b,results::add);
  check(SovietGramApiClient.calls.size()==1&&SovietGramApiClient.calls.get(0).contains("self?service=spotify"),"supported signed-in provider loads independently");
  answer(response(true,"https://open.spotify.com/owner-a/song"));
  CustomProfileIntegrations.Rich rich=results.get(0);
  check(rich.service==4&&rich.hasCard()&&!rich.empty&&rich.track.title.equals("Actual Song")&&rich.track.progressMs==33000&&rich.track.cover.isEmpty(),"supported track payload renders without invented cover");
  SystemClock.now=4000;check(rich.track.positionNow()==36000,"moving bar follows real snapshot");
  CustomProfileIntegrations.clearCache();results.clear();CustomProfileIntegrations.loadRich(0,101,b,results::add);answer(response(false,"https://open.spotify.com/owner-a/song"));
  SystemClock.now=9000;check(!results.get(0).track.playing&&results.get(0).track.positionNow()==33000,"paused real source freezes progress");
  CustomProfileExtraRows.Block other=new CustomProfileExtraRows.Block();CustomProfileIntegrations.loadRich(0,202,other,results::add);answer(response(true,"https://open.spotify.com/owner-b/song"));
  check(CustomProfileIntegrations.openUrl(b).equals("https://open.spotify.com/owner-a/song"),"same block id in another owner cannot replace current card tap source");
  CustomProfileIntegrations.clearCache();results.clear();CustomProfileIntegrations.loadRich(0,101,b,results::add);
  CustomProfileIntegrations.clearCache();answer(response(true,"https://open.spotify.com/stale/song"));check(results.isEmpty(),"invalidated pending response cannot restore old track");
  CustomProfileIntegrations.loadRich(0,101,b,results::add);answer(new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("mode",0))));
  check(results.get(0).empty&&!results.get(0).hasCard(),"empty supported response collapses card");
 }
}
'''.replace('FIELDS',fields).replace('PRODUCTION',production)
        with tempfile.TemporaryDirectory(prefix='supported-loader-',dir=os.environ['TMPDIR']) as folder:
            file=Path(folder)/'LoaderHarness.java';file.write_text(code)
            compiled=subprocess.run(['javac','-J-Xmx96m',str(file)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'LoaderHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
