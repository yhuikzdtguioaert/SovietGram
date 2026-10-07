"""Real production JVM policy seams; no Android build or live-device claims."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HELPERS = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers'

class SoundCloudLiveRpc(unittest.TestCase):
    def run_policy(self, body):
        source = HELPERS / 'SoundCloudRpcPolicy.java'
        self.assertTrue(source.exists(), 'Missing genuine opt-in device publication policy')
        harness = '''import tw.nekomimi.nekogram.helpers.SoundCloudRpcPolicy;
public class RpcHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[]args){ BODY }
}'''.replace('BODY', body)
        with tempfile.TemporaryDirectory(prefix='sc-live-', dir=os.environ['TMPDIR']) as folder:
            java = Path(folder) / 'RpcHarness.java'
            java.write_text(harness)
            compile = subprocess.run(['javac','-J-Xmx96m','-d',folder,str(source),str(java)],capture_output=True,text=True)
            self.assertEqual(compile.returncode,0,compile.stderr)
            run = subprocess.run(['java','-Xmx64m','-cp',folder,'RpcHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_default_off_and_exact_package_filter(self):
        self.run_policy('''
 SoundCloudRpcPolicy p=new SoundCloudRpcPolicy();
 check(!p.configure(0,101,false,true),"default off");
 check(!p.accepts("com.spotify.music"),"other package rejected");
 check(!p.accepts("com.soundcloud.android"),"permission alone never opts in");
 check(p.configure(0,101,true,true),"explicit owner allowed");
 check(p.accepts("com.soundcloud.android"),"SoundCloud session allowed");
 check(!p.accepts("com.soundcloud.android.fake"),"exact package only");
 check(!p.configure(0,101,true,false),"revocation closes gate");
''')

    def test_serial_coalescing_heartbeat_and_owner_lifecycle(self):
        self.run_policy('''
 SoundCloudRpcPolicy p=new SoundCloudRpcPolicy();
 p.configure(0,101,true,true);
 long token=p.generation();
 p.offer(token,"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("Song","Artist","",100000,1000,"playing"));
 SoundCloudRpcPolicy.Request first=p.next(0);
 check(first!=null&&!first.clear&&first.owner==101,"real track publication");
 p.offer(token,"com.spotify.music",new SoundCloudRpcPolicy.Snapshot("Foreign","Other","",100000,0,"playing"));
 p.offer(token,"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("Song","Artist","",100000,5000,"paused"));
 check(p.next(2000)==null,"one request in flight");
 p.complete(first,true,100);
 check(p.next(1000)==null,"coalesce burst");
 SoundCloudRpcPolicy.Request paused=p.next(2000);
 check(paused!=null&&paused.snapshot.state.equals("paused")&&paused.snapshot.positionMs==5000,"latest real pause wins");
 p.complete(paused,true,2100);
 check(p.next(24000)==null,"no unnecessary heartbeat");
 SoundCloudRpcPolicy.Request heart=p.next(27000);
 check(heart!=null&&heart.snapshot.positionMs==5000,"paused heartbeat without extrapolation");
 p.configure(1,202,true,true);
 long second=p.generation();
 p.offer(token,"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("Leaked","Other","",100000,0,"playing"));
 p.offer(second,"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("New","New artist","",100000,0,"playing"));
 p.complete(heart,true,27001);
 SoundCloudRpcPolicy.Request clear=p.next(27002);
 check(clear!=null&&clear.clear&&clear.owner==101&&clear.account==0,"old owner delete precedes new post");
 p.complete(heart,true,27003);
 check(p.next(28000)==null,"stale callback cannot unlock new request");
 p.complete(clear,true,28001);
 SoundCloudRpcPolicy.Request newOwner=p.next(28002);
 check(newOwner!=null&&newOwner.owner==202&&newOwner.snapshot.title.equals("New"),"new owner isolated");
 p.configure(1,202,true,false);
 p.complete(newOwner,true,29000);
 SoundCloudRpcPolicy.Request revoke=p.next(29001);
 check(revoke!=null&&revoke.clear&&revoke.owner==202,"permission revoke deletes");
 p.complete(revoke,true,29002);
 check(p.next(90000)==null,"no refresh after revoke");
 p.configure(1,202,true,true);
 p.offer(p.generation(),"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("Again","A","",100000,0,"playing"));
 SoundCloudRpcPolicy.Request again=p.next(90001);p.complete(again,true,90002);
 p.configure(1,0,false,true);
 check(p.next(90003).clear,"logout deletes");
''')

    def test_android_snapshot_real_state_and_unknown_timing(self):
        from test_soundcloud_protocol import method
        source = HELPERS / 'SoundCloudMediaSessionService.java'
        self.assertTrue(source.exists(), 'Missing real Android media-session source')
        snapshot = method(source.read_text(), 'static SoundCloudRpcPolicy.Snapshot fromSession(')
        java = '''
import tw.nekomimi.nekogram.helpers.SoundCloudRpcPolicy;
public class SnapshotHarness {
 static class MediaMetadata {
  static final String METADATA_KEY_TITLE="title",METADATA_KEY_DISPLAY_TITLE="display",METADATA_KEY_ARTIST="artist",METADATA_KEY_ALBUM="album",METADATA_KEY_DURATION="duration",METADATA_KEY_MEDIA_URI="media",METADATA_KEY_ART_URI="art",METADATA_KEY_ALBUM_ART_URI="albumArt";
  java.util.Map<String,Object> values=new java.util.HashMap<>();
  MediaMetadata put(String key,Object value){values.put(key,value);return this;}
  String getString(String key){return (String)values.get(key);} long getLong(String key){return ((Number)values.getOrDefault(key,0L)).longValue();}
  boolean containsKey(String key){return values.containsKey(key);}
 }
 static class PlaybackState {
  static final int STATE_NONE=0,STATE_STOPPED=1,STATE_PAUSED=2,STATE_PLAYING=3,STATE_BUFFERING=6;
  long position=2000,update=1000;float speed=1;int state=STATE_PLAYING;
  long getPosition(){return position;}long getLastPositionUpdateTime(){return update;}float getPlaybackSpeed(){return speed;}int getState(){return state;}
 }
 METHOD
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[]args){
  MediaMetadata m=new MediaMetadata().put("title","Real song").put("artist","Actual artist").put("duration",100000L);
  PlaybackState s=new PlaybackState();
  SoundCloudRpcPolicy.Snapshot playing=fromSession(m,s,4000);
  check(playing.title.equals("Real song")&&playing.positionMs==5000&&playing.state.equals("playing"),"real state timestamp projection");
  check(playing.coverUrl.isEmpty()&&playing.trackUrl.isEmpty(),"never fabricate missing cover/URL");
  s.state=PlaybackState.STATE_PAUSED;
  check(fromSession(m,s,9000).positionMs==2000,"paused must not extrapolate");
  s.position=-1;
  SoundCloudRpcPolicy.Snapshot unknown=fromSession(m,s,9000);
  check(unknown.durationMs==0&&unknown.positionMs==0,"unknown position cannot create a progress bar");
  s.position=2000;s.state=PlaybackState.STATE_BUFFERING;
  check(fromSession(m,s,9000)==null,"unreported state cannot be guessed playing");
  check(fromSession(null,s,9000)==null,"missing metadata clears");
  s.state=PlaybackState.STATE_PLAYING;
  check(fromSession(new MediaMetadata(),s,9000)==null,"no guessed track title");
  m.put("media","https://soundcloud.com/real/song").put("art","https://i1.sndcdn.com/real.jpg");
  SoundCloudRpcPolicy.Snapshot urls=fromSession(m,s,9000);
  check(urls.trackUrl.equals("https://soundcloud.com/real/song")&&urls.coverUrl.equals("https://i1.sndcdn.com/real.jpg"),"only exposed URLs");
 }
}
'''.replace('METHOD',snapshot)
        with tempfile.TemporaryDirectory(prefix='sc-snapshot-',dir=os.environ['TMPDIR']) as folder:
            path=Path(folder)/'SnapshotHarness.java';path.write_text(java)
            result=subprocess.run(['javac','-J-Xmx96m','-d',folder,str(HELPERS/'SoundCloudRpcPolicy.java'),str(path)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'SnapshotHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_captured_signed_identity_and_cancelled_post(self):
        from test_soundcloud_protocol import method
        source=(HELPERS/'SovietGramApiClient.java').read_text()
        self.assertIn('public static final class MusicRpcAuth',source,'RPC writes need captured owner credentials across logout/slot reuse')
        capture=method(source,'public static MusicRpcAuth captureMusicRpcAuth(')
        auth=method(source,'public static final class MusicRpcAuth')
        code='''
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import tw.nekomimi.nekogram.helpers.SoundCloudRpcPolicy;
public class AuthHarness {
 static class TextUtils {static boolean isEmpty(String s){return s==null||s.isEmpty();}}
 static class SovietGramTokenStore {static long id=101;static String token="owner-A";static long ownId(int a){return id;}static String tokenForAccount(int a){return token;}}
 static class ApiServersHelper {static String base="https://api.example";static String baseUrl(){return base;}}
 static class JSONObject {static Map<String,Object> last;Map<String,Object> values=new HashMap<>();JSONObject put(String k,Object v){values.put(k,v);return this;}public String toString(){last=values;return values.toString();}}
 interface Callback {void onResult(JSONObject b,String e);}
 static List<Runnable> tasks=new ArrayList<>();static class Executor {void execute(Runnable r){tasks.add(r);}}static Executor EXECUTOR=new Executor();
 static class HttpClient {static HttpClient INSTANCE=new HttpClient();Object getInstance(){return this;}}
 static class FileLog {static void e(Throwable e){}}
 static class ApiError extends Exception {}
 static int writes;static String usedToken,usedBase,usedMethod;static boolean ok;
 static Object executeWithIdentity(int a,String method,String path,byte[] body,boolean authed,Object http,String base,String token){writes++;usedToken=token;usedBase=base;usedMethod=method;return null;}
 static JSONObject readJson(int account,Object response,boolean authed){return new JSONObject().put("ok",true);}
 static void deliver(Callback cb,JSONObject body,String error){cb.onResult(body,error);}
 CAPTURE
 AUTH
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[]args){
  check(captureMusicRpcAuth(0,202)==null,"wrong owner cannot bind");
  MusicRpcAuth auth=captureMusicRpcAuth(0,101);check(auth!=null,"capture real signed owner");
  SoundCloudRpcPolicy p=new SoundCloudRpcPolicy();p.configure(0,101,true,true);
  p.offer(p.generation(),"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("Song","Artist","",90000,5000,"playing"));
  SoundCloudRpcPolicy.Request post=p.next(0);
  auth.write(post,()->false,(body,error)->ok=error==null);
  tasks.remove(0).run();check(writes==0&&!ok,"stale/disabled POST cancelled before HTTP");
  auth.write(post,()->true,(body,error)->ok=error==null);
  SovietGramTokenStore.id=202;SovietGramTokenStore.token="owner-B";ApiServersHelper.base="https://new.example";
  tasks.remove(0).run();check(writes==1&&usedToken.equals("owner-A")&&usedBase.equals("https://api.example"),"never use replacement slot token or server");
  check(JSONObject.last.get("service").equals("soundcloud")&&JSONObject.last.get("position_ms").equals(5000L)&&!JSONObject.last.containsKey("cover_url")&&!JSONObject.last.containsKey("track_url"),"fixed backend schema without invented URLs");
  p.configure(0,0,false,true);p.complete(post,true,1);SoundCloudRpcPolicy.Request clear=p.next(2);
  auth.write(clear,()->false,(body,error)->ok=error==null);tasks.remove(0).run();
  check(ok&&writes==2&&usedMethod.equals("DELETE")&&usedToken.equals("owner-A"),"clear old owner even after logout");
 }
}
'''.replace('CAPTURE',capture).replace('AUTH',auth).replace('@Nullable ','')
        with tempfile.TemporaryDirectory(prefix='sc-auth-',dir=os.environ['TMPDIR']) as folder:
            path=Path(folder)/'AuthHarness.java';path.write_text(code)
            result=subprocess.run(['javac','-J-Xmx96m','-d',folder,str(HELPERS/'SoundCloudRpcPolicy.java'),str(path)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'AuthHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_device_coordinator_disable_switch_logout_and_revocation(self):
        source=HELPERS/'SoundCloudDeviceRpc.java'
        self.assertTrue(source.exists(),'Missing opt-in owner/lifecycle coordinator')
        production=source.read_text()
        production='\n'.join(line for line in production.splitlines() if not line.startswith(('package ','import ')))
        production=production.replace('public final class SoundCloudDeviceRpc','static final class SoundCloudDeviceRpc').replace('org.telegram.messenger.','')
        code='''
import java.util.*;
import tw.nekomimi.nekogram.helpers.SoundCloudRpcPolicy;
public class DeviceHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Context {static int MODE_PRIVATE=0;SharedPreferences preferences=new SharedPreferences();SharedPreferences getSharedPreferences(String n,int mode){return preferences;}Object getContentResolver(){return this;}}
 static class SharedPreferences {static Runnable disablingHook;long value;long getLong(String key,long fallback){return value;}Editor edit(){return new Editor();}class Editor {Editor putLong(String key,long v){value=v;return this;}void apply(){if(value==0&&disablingHook!=null)disablingHook.run();}}}
 static class ApplicationLoader {static Context applicationContext=new Context();}
 static class Settings {static class Secure {static boolean permission=true;static String getString(Object r,String key){return permission?"test/SoundCloudMediaSessionService":"";}}}
 static class ComponentName {ComponentName(Context c,Class<?> t){}String flattenToString(){return "test/SoundCloudMediaSessionService";}String flattenToShortString(){return flattenToString();}}
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class AndroidUtilities {static List<Runnable> scheduled=new ArrayList<>();static void runOnUIThread(Runnable r){r.run();}static void runOnUIThread(Runnable r,long delay){scheduled.add(r);}static void cancelRunOnUIThread(Runnable r){scheduled.removeIf(x->x==r);}}
 static class UserConfig {static int selectedAccount=0;static long[] ids={101,202};int account;UserConfig(int a){account=a;}static UserConfig getInstance(int a){return new UserConfig(a);}long getClientUserId(){return ids[account];}}
 static class SovietGramTokenStore {static long ownId(int a){return UserConfig.ids[a];}}
 static class JSONObject {boolean optBoolean(String key){return true;}}
 static class CustomProfileIntegrations {static void clearCache(){}}
 static class SovietGramApiClient {
  interface Callback {void onResult(JSONObject b,String error);}
  static boolean isReady(int a){return UserConfig.ids[a]>0;}
  static List<SoundCloudRpcPolicy.Request> writes=new ArrayList<>();static List<Callback> callbacks=new ArrayList<>();static List<java.util.function.BooleanSupplier> guards=new ArrayList<>();
  static MusicRpcAuth captureMusicRpcAuth(int a,long owner){return new MusicRpcAuth(a,owner);}
  static class MusicRpcAuth {int account;long owner;MusicRpcAuth(int a,long o){account=a;owner=o;}boolean isCurrentIdentity(){return UserConfig.ids[account]==owner;}
   void write(SoundCloudRpcPolicy.Request r,java.util.function.BooleanSupplier allowed,Callback cb){check(r.clear||allowed.getAsBoolean(),"coordinator authorizes only current post");writes.add(r);callbacks.add(cb);guards.add(allowed);}
  }
 }
 static class SoundCloudMediaSessionService {long token;int starts,stops;void startSource(long t){token=t;starts++;}void stopSource(){stops++;}void heartbeat(long now){}}
 PRODUCTION
 static void finish(){SovietGramApiClient.callbacks.remove(0).onResult(new JSONObject(),null);}
 public static void main(String[]args){
  SoundCloudMediaSessionService service=new SoundCloudMediaSessionService();SoundCloudDeviceRpc.attach(service);
  check(!SoundCloudDeviceRpc.isEnabled(0)&&service.starts==0,"permission/connection default off");
  check(SoundCloudDeviceRpc.setEnabled(0,true),"explicit consent for selected owner");
  SoundCloudDeviceRpc.offer(service.token,new SoundCloudRpcPolicy.Snapshot("A","Artist","",90000,5000,"playing"));
  check(SovietGramApiClient.writes.size()==1&&!SovietGramApiClient.writes.get(0).clear,"publishes only after opt-in");
  long stale=service.token;SoundCloudDeviceRpc.onAccountChanging(1);UserConfig.selectedAccount=1;
  check(!SoundCloudDeviceRpc.isEnabled(0)&&!SoundCloudDeviceRpc.isEnabled(1),"switch clears consent, never transfers");
  SoundCloudDeviceRpc.offer(stale,new SoundCloudRpcPolicy.Snapshot("Leak","Artist","",90000,0,"playing"));
  finish();check(SovietGramApiClient.writes.size()==2&&SovietGramApiClient.writes.get(1).clear&&SovietGramApiClient.writes.get(1).owner==101,"switch DELETE uses old owner after outstanding POST");finish();
  check(SoundCloudDeviceRpc.setEnabled(1,true),"new owner separately opts in");
  SoundCloudDeviceRpc.offer(service.token,new SoundCloudRpcPolicy.Snapshot("B","Artist","",90000,0,"playing"));finish();
  SoundCloudDeviceRpc.onLogout(1);UserConfig.ids[1]=0;
  check(!SoundCloudDeviceRpc.isEnabled(1)&&SovietGramApiClient.writes.get(3).clear,"logout deletes before identity cleared");finish();
  UserConfig.ids[1]=202;Settings.Secure.permission=false;
  check(!SoundCloudDeviceRpc.setEnabled(1,true),"no self permission grant");
  Settings.Secure.permission=true;check(SoundCloudDeviceRpc.setEnabled(1,true),"explicit re-enable");
  SoundCloudDeviceRpc.offer(service.token,new SoundCloudRpcPolicy.Snapshot("C","Artist","",90000,0,"playing"));finish();
  Settings.Secure.permission=false;SoundCloudDeviceRpc.refresh();
  check(!SoundCloudDeviceRpc.isEnabled(1)&&SovietGramApiClient.writes.get(5).clear,"revocation clears consent and publication");finish();
  Settings.Secure.permission=true;SoundCloudDeviceRpc.refresh();
  check(!SoundCloudDeviceRpc.isEnabled(1),"permission regrant must not silently resume");
  check(SoundCloudDeviceRpc.setEnabled(1,true),"explicit enable for disable replay");
  SoundCloudDeviceRpc.offer(service.token,new SoundCloudRpcPolicy.Snapshot("D","Artist","",90000,0,"playing"));
  SharedPreferences.disablingHook=()->check(!SovietGramApiClient.guards.get(6).getAsBoolean(),"off decision closes worker gate before persistence can race");
  check(SoundCloudDeviceRpc.setEnabled(1,false),"explicit disable");SharedPreferences.disablingHook=null;
  check(!SovietGramApiClient.guards.get(6).getAsBoolean(),"disable closes already-enqueued worker authorization");
  finish();check(SovietGramApiClient.writes.get(7).clear,"disable orders DELETE after outstanding POST");finish();
  check(SoundCloudDeviceRpc.setEnabled(1,true),"explicit enable for disconnect replay");
  SoundCloudDeviceRpc.offer(service.token,new SoundCloudRpcPolicy.Snapshot("E","Artist","",90000,0,"playing"));finish();
  SoundCloudDeviceRpc.detach(service);check(!SoundCloudDeviceRpc.isEnabled(1)&&SovietGramApiClient.writes.get(9).clear,"listener disconnect disables and deletes");finish();
  SoundCloudMediaSessionService replacement=new SoundCloudMediaSessionService();SoundCloudDeviceRpc.attach(replacement);
  check(replacement.starts==0,"listener reconnect cannot silently restart publishing");
 }
}
'''.replace('PRODUCTION',production)
        with tempfile.TemporaryDirectory(prefix='sc-device-',dir=os.environ['TMPDIR']) as folder:
            path=Path(folder)/'DeviceHarness.java';path.write_text(code)
            result=subprocess.run(['javac','-J-Xmx96m','-d',folder,str(HELPERS/'SoundCloudRpcPolicy.java'),str(path)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'DeviceHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_real_source_filters_packages_and_rejects_stale_callbacks(self):
        source=(HELPERS/'SoundCloudMediaSessionService.java').read_text()
        production='\n'.join(line for line in source.splitlines() if not line.startswith(('package ','import ')))
        production=production.replace('public final class SoundCloudMediaSessionService','static final class SoundCloudMediaSessionService').replace('org.telegram.messenger.','')
        code='''
import java.util.*;
import tw.nekomimi.nekogram.helpers.SoundCloudRpcPolicy;
public class SourceHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Handler {static List<Runnable> queued=new ArrayList<>();Handler(Looper l){}void post(Runnable r){queued.add(r);}static void drain(){while(!queued.isEmpty())queued.remove(0).run();}}static class Looper {static Looper getMainLooper(){return new Looper();}}
 static class SystemClock {static long elapsedRealtime(){return 1000;}}
 static class ComponentName {ComponentName(Object context,Class<?> c){}}
 static class StatusBarNotification {}
 static class NotificationListenerService {static String MEDIA_SESSION_SERVICE="media";Object getSystemService(String key){return currentManager;}public void onListenerConnected(){}public void onListenerDisconnected(){}public void onDestroy(){}public void onNotificationPosted(StatusBarNotification n){}public void onNotificationRemoved(StatusBarNotification n){}}
 static class ApplicationLoader {static void postInitApplication(){}}
 static class MediaMetadata {
  static final String METADATA_KEY_TITLE="title",METADATA_KEY_DISPLAY_TITLE="display",METADATA_KEY_ARTIST="artist",METADATA_KEY_ALBUM="album",METADATA_KEY_DURATION="duration",METADATA_KEY_MEDIA_URI="media",METADATA_KEY_ART_URI="art",METADATA_KEY_ALBUM_ART_URI="albumArt";
  String title;MediaMetadata(String t){title=t;}String getString(String k){return k.equals("title")?title:"";}long getLong(String k){return 90000;}
 }
 static class PlaybackState {static final int STATE_NONE=0,STATE_STOPPED=1,STATE_PAUSED=2,STATE_PLAYING=3;int state=STATE_PLAYING;long position=1000;int getState(){return state;}long getPosition(){return position;}long getLastPositionUpdateTime(){return 1000;}float getPlaybackSpeed(){return 1;}}
 static class MediaController {
  String pkg,id;int metadataReads,stateReads;Callback callback;MediaController(String pkg,String id){this.pkg=pkg;this.id=id;}
  String getPackageName(){return pkg;}Object getSessionToken(){return id;}PlaybackState getPlaybackState(){stateReads++;return new PlaybackState();}
  MediaMetadata getMetadata(){metadataReads++;return new MediaMetadata(id);}void registerCallback(Callback cb,Handler h){callback=cb;}void unregisterCallback(Callback cb){}
  static class Callback {public void onMetadataChanged(MediaMetadata m){}public void onPlaybackStateChanged(PlaybackState s){}public void onSessionDestroyed(){}}
 }
 static MediaSessionManager currentManager=new MediaSessionManager();
 static class MediaSessionManager {
  interface OnActiveSessionsChangedListener {void onActiveSessionsChanged(List<MediaController> controllers);}
  OnActiveSessionsChangedListener callback;List<MediaController> active=new ArrayList<>();
  void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,ComponentName c,Handler h){callback=l;}
  void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l){}List<MediaController> getActiveSessions(ComponentName c){return active;}
 }
 static class SoundCloudDeviceRpc {static boolean enabled=true;static long gen=1;static List<SoundCloudRpcPolicy.Snapshot> events=new ArrayList<>();static int attaches;static void attach(SoundCloudMediaSessionService s){attaches++;}static void detach(SoundCloudMediaSessionService s){enabled=false;}static void permissionLost(){enabled=false;}static boolean isCurrent(long g){return enabled&&g==gen;}static void offer(long g,SoundCloudRpcPolicy.Snapshot s){events.add(s);}}
 PRODUCTION
 public static void main(String[]args){
  MediaController foreign=new MediaController("com.telegram","Private messages");
  MediaController a=new MediaController("com.soundcloud.android","A");
  currentManager.active=Arrays.asList(foreign,a);
  SoundCloudMediaSessionService service=new SoundCloudMediaSessionService();service.onListenerConnected();
  check(SoundCloudDeviceRpc.attaches==0,"pre-N listener callback must be marshalled onto main handler");Handler.drain();service.startSource(1);
  check(foreign.metadataReads==0&&foreign.stateReads==0,"other packages are never processed");
  check(SoundCloudDeviceRpc.events.size()==1&&SoundCloudDeviceRpc.events.get(0).title.equals("A"),"real chosen source");
  MediaController.Callback stale=a.callback;MediaController b=new MediaController("com.soundcloud.android","B");
  currentManager.active=Arrays.asList(foreign,b);currentManager.callback.onActiveSessionsChanged(currentManager.active);
  check(SoundCloudDeviceRpc.events.size()==2&&SoundCloudDeviceRpc.events.get(1).title.equals("B"),"active-session listener remains usable after selecting controller");
  PlaybackState seek=new PlaybackState();seek.position=33000;seek.state=PlaybackState.STATE_PAUSED;b.callback.onPlaybackStateChanged(seek);
  SoundCloudRpcPolicy.Snapshot exact=SoundCloudDeviceRpc.events.get(2);
  check(exact.positionMs==33000&&exact.state.equals("paused"),"use actual seek/pause callback state, not a stale controller getter");
  b.callback.onMetadataChanged(new MediaMetadata("Changed title"));
  check(SoundCloudDeviceRpc.events.get(3).title.equals("Changed title"),"use actual metadata callback");
  stale.onMetadataChanged(new MediaMetadata("LEAK"));check(SoundCloudDeviceRpc.events.size()==4,"replaced callback rejected");
  service.onNotificationPosted(new StatusBarNotification());service.onNotificationRemoved(new StatusBarNotification());
  check(SoundCloudDeviceRpc.events.size()==4,"notifications ignored");
  service.stopSource();b.callback.onPlaybackStateChanged(new PlaybackState());
  check(SoundCloudDeviceRpc.events.size()==4,"callback after teardown rejected");
  int attaches=SoundCloudDeviceRpc.attaches;SoundCloudMediaSessionService destroyed=new SoundCloudMediaSessionService();
  destroyed.onListenerConnected();destroyed.onDestroy();Handler.drain();
  check(SoundCloudDeviceRpc.attaches==attaches,"queued listener connect cannot resurrect destroyed service");
 }
}
'''.replace('PRODUCTION',production)
        with tempfile.TemporaryDirectory(prefix='sc-source-',dir=os.environ['TMPDIR']) as folder:
            path=Path(folder)/'SourceHarness.java';path.write_text(code)
            result=subprocess.run(['javac','-J-Xmx96m','-d',folder,str(HELPERS/'SoundCloudRpcPolicy.java'),str(path)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'SourceHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_live_provider_owner_path_without_other_integration_binding(self):
        from test_soundcloud_protocol import method
        source=(HELPERS/'CustomProfileIntegrations.java').read_text()
        self.assertTrue('private static String requestPath(' in source,'Live source needs its own owner endpoint, not a second integration URL')
        path=method(source,'private static String requestPath(')
        refresh=method(source,'public static long refreshMs(')
        code='''
public class PathHarness {
 static class CustomProfileExtraRows {static class Block {int service=7,intRefresh;String id="live-card";}}
 static class UserConfig {static UserConfig getInstance(int a){return new UserConfig();}long getClientUserId(){return 101;}}
 static class Uri {static String encode(String s){return s;}}
 static boolean isConnected(int s){return s==3||s==4||s==6;}
 static String key(int s){return s==7?"soundcloud-live":s==4?"spotify":"soundcloud-me";}
 PATH
 REFRESH
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[]args){
  CustomProfileExtraRows.Block b=new CustomProfileExtraRows.Block();
  check(requestPath(0,101,b,"","0").equals("/v1/integrations/self?service=soundcloud-live&modes=0"),"own live source works without account binding or second card");
  check(requestPath(0,202,b,"legacy-private-id","0").equals("/v1/profile-integrations/202/live-card"),"other-owner card resolves published block only");
  check(refreshMs(b)==10000,"live default refresh");b.intRefresh=3600;check(refreshMs(b)<=30000,"cannot cache live TTL data for an hour");
  b.service=4;check(requestPath(0,101,b,"spotify-user","0").contains("service=spotify"),"existing provider unaffected");
 }
}
'''.replace('PATH',path).replace('REFRESH',refresh)
        with tempfile.TemporaryDirectory(prefix='sc-route-',dir=os.environ['TMPDIR']) as folder:
            file=Path(folder)/'PathHarness.java';file.write_text(code)
            compiled=subprocess.run(['javac','-J-Xmx96m',str(file)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'PathHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_manifest_opt_in_ui_and_lifecycle_contracts(self):
        import xml.etree.ElementTree as ET
        android='{http://schemas.android.com/apk/res/android}'
        main=ROOT/'TMessagesProj/src/main'
        tree=ET.parse(main/'AndroidManifest.xml')
        services=[e for e in tree.findall('application/service') if e.get(android+'name')=='tw.nekomimi.nekogram.helpers.SoundCloudMediaSessionService']
        self.assertEqual(len(services),1,'Notification listener must be declared')
        self.assertEqual(services[0].get(android+'permission'),'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE')
        self.assertEqual(services[0].get(android+'exported'),'true')
        self.assertEqual(services[0].find('intent-filter/action').get(android+'name'),'android.service.notification.NotificationListenerService')
        ui=(main/'java/tw/nekomimi/nekogram/settings/CustomProfileBlocksActivity.java').read_text()
        self.assertIn('0, 1, 2, 3, 4, 5, 6, 7',ui)
        self.assertIn('buildSoundcloudLiveSettings(block)',ui)
        self.assertIn('confirmSoundcloudLive',ui)
        self.assertIn('ACTION_NOTIFICATION_LISTENER_SETTINGS',ui)
        for lang in ['values','values-ru-rRU']:
            strings=ET.parse(main/'res'/lang/'strings_sovietgram.xml')
            names={n.get('name'):n.text for n in strings.findall('string')}
            self.assertIn('CustomProfileSoundcloudLiveDisclosure',names)
            self.assertIn('%1$s',names['CustomProfileSoundcloudLivePublishOwner'])
        messages=(main/'java/org/telegram/messenger/MessagesController.java').read_text()
        logout=messages[messages.index('public void performLogout(int type)'):]
        self.assertLess(logout.index('SoundCloudDeviceRpc.onLogout(currentAccount)'),logout.index('getUserConfig().clearConfig()'))
        scope=(HELPERS/'SovietGramAccountScope.java').read_text()
        self.assertIn('SoundCloudDeviceRpc.onAccountChanging(account)',scope)

    def test_stale_live_consent_cannot_enable_another_owner_or_changed_block(self):
        from test_soundcloud_protocol import method
        source=(ROOT/'TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/CustomProfileBlocksActivity.java').read_text()
        self.assertTrue('private void enableSoundcloudLive(' in source,'Missing guarded live opt-in UI confirmation')
        enable=method(source,'private void enableSoundcloudLive(')
        code='''
import java.util.*;
public class ConsentHarness {
 int currentAccount=0;boolean live=true;boolean sameOwner(long id){return live&&id==101;}void rebuild(){}
 static class CustomProfileExtraRows {static int TYPE_INTEGRATION=12;static class Block {String id="card";int type=12,service=7;}static List<Block> blocks=new ArrayList<>();static List<Block> stored(){return blocks;}}
 static class SoundCloudDeviceRpc {static int enabled;static boolean setEnabled(int a,boolean on){enabled++;return true;}}
 static class AlertDialog {static class Builder {Builder(Object a){}Builder setTitle(String s){return this;}Builder setMessage(String s){return this;}Builder setPositiveButton(String s,Object l){return this;}void show(){}}}
 static class R {static class string {static int CustomProfileSoundcloudLive=1,CustomProfileSoundcloudLiveUnavailable=2,OK=3;}}
 String getString(int r){return "";}Object getParentActivity(){return this;}
 ENABLE
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[]args){
  ConsentHarness ui=new ConsentHarness();CustomProfileExtraRows.Block block=new CustomProfileExtraRows.Block();CustomProfileExtraRows.blocks.add(block);
  ui.live=false;ui.enableSoundcloudLive("card",101);check(SoundCloudDeviceRpc.enabled==0,"stale owner callback rejected");
  ui.live=true;block.service=6;ui.enableSoundcloudLive("card",101);check(SoundCloudDeviceRpc.enabled==0,"switched provider callback rejected");
  block.service=7;ui.enableSoundcloudLive("deleted",101);check(SoundCloudDeviceRpc.enabled==0,"deleted initiating card rejected");
  ui.enableSoundcloudLive("card",101);check(SoundCloudDeviceRpc.enabled==1,"explicit live owner consent accepted");
 }
}
'''.replace('ENABLE',enable)
        with tempfile.TemporaryDirectory(prefix='sc-consent-',dir=os.environ['TMPDIR']) as folder:
            file=Path(folder)/'ConsentHarness.java';file.write_text(code)
            compiled=subprocess.run(['javac','-J-Xmx96m',str(file)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'ConsentHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_wire_schema_limits_and_only_actual_soundcloud_urls(self):
        self.run_policy('''
 SoundCloudRpcPolicy.Snapshot bad=new SoundCloudRpcPolicy.Snapshot("Song\\\\nTitle","Artist\\\\tName","",90000000L,90000001L,"playing","https://evil.example/track","https://evil.example/cover");
 check(bad.trackUrl.isEmpty()&&bad.coverUrl.isEmpty(),"omit media URLs outside fixed backend SoundCloud/sndcdn allowlists");
 check(bad.durationMs==0&&bad.positionMs==0,"out-of-schema timing is unknown, not fictional clamped progress");
 SoundCloudRpcPolicy.Snapshot good=new SoundCloudRpcPolicy.Snapshot(" Song\\nTitle ","Artist\\tName","",100000,120000,"playing","https://soundcloud.com/artist/track","https://i1.sndcdn.com/artworks-real.jpg");
 check(good.title.equals("SongTitle")&&good.artist.equals("ArtistName"),"no backend-rejected control characters");
 check(good.positionMs==100000&&good.coverUrl.endsWith(".jpg"),"valid timing bounded by actual duration and genuine URLs preserved");
 for(String url:new String[]{"https://user:pass@soundcloud.com/a/b","https://soundcloud.com:444/a/b","http://soundcloud.com/a/b","https://soundcloud.com/a b"}) {
  check(new SoundCloudRpcPolicy.Snapshot("Song","Artist","",1,0,"playing",url,url).trackUrl.isEmpty(),"unsafe track URL omitted");
 }
''')

    def test_failed_clear_retry_expires_after_server_ttl(self):
        self.run_policy('''
 SoundCloudRpcPolicy p=new SoundCloudRpcPolicy();p.configure(0,101,true,true);
 p.offer(p.generation(),"com.soundcloud.android",new SoundCloudRpcPolicy.Snapshot("Song","Artist","",90000,0,"playing"));
 SoundCloudRpcPolicy.Request post=p.next(0);p.complete(post,true,1);p.configure(0,101,false,true);
 SoundCloudRpcPolicy.Request clear=p.next(2);p.complete(clear,false,3);
 check(p.next(1000)==null,"failed cleanup retry bounded");
 SoundCloudRpcPolicy.Request retry=p.next(2003);check(retry!=null&&retry.clear,"best-effort cleanup retry");p.complete(retry,false,2004);
 check(p.next(90002)==null,"after 90-second server TTL no unbounded cleanup requests");
 check(!p.hasWork(),"disabled expired cleanup releases watchdog");
''')

    def test_actual_integration_loader_real_card_without_any_second_block(self):
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
 static class CustomProfileExtraRows {static class Block {String id="live",url="";JSONObject accounts=new JSONObject();JSONArray parts=new JSONArray().put(0);int service=7,mode,intRefresh;}}
 static class SovietGramApiClient {interface Callback {void onResult(JSONObject b,String e);}static List<String> calls=new ArrayList<>();static List<Callback> pending=new ArrayList<>();static boolean isReady(int a){return true;}static void get(int a,String path,Callback cb){calls.add(path);pending.add(cb);}}
 static class org {static class telegram {static class messenger {static class Utilities {static Queue globalQueue=new Queue();static class Queue {void postRunnable(Runnable r){r.run();}}}}}}
 PRODUCTION
 static JSONObject response(boolean playing,String url){return new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("mode",0).put("value","Actual Artist — Actual Song").put("track",new JSONObject().put("title","Actual Song").put("artist","Actual Artist").put("durationMs",90000L).put("progressMs",33000L).put("playing",playing).put("url",url))));}
 static void answer(JSONObject body){SovietGramApiClient.pending.remove(0).onResult(body,null);}
 public static void main(String[]args){
  CustomProfileExtraRows.Block b=new CustomProfileExtraRows.Block();List<CustomProfileIntegrations.Rich> results=new ArrayList<>();
  CustomProfileIntegrations.loadRich(0,101,b,results::add);
  check(SovietGramApiClient.calls.size()==1&&SovietGramApiClient.calls.get(0).contains("self?service=soundcloud-live"),"one empty-binding real-source card independently loads");
  answer(response(true,"https://soundcloud.com/owner-a/song"));
  CustomProfileIntegrations.Rich rich=results.get(0);
  check(rich.service==7&&rich.hasCard()&&!rich.empty&&rich.track.title.equals("Actual Song")&&rich.track.progressMs==33000&&rich.track.cover.isEmpty(),"actual live track payload renders without invented cover");
  SystemClock.now=4000;check(rich.track.positionNow()==36000,"moving bar follows real snapshot");
  CustomProfileIntegrations.clearCache();results.clear();CustomProfileIntegrations.loadRich(0,101,b,results::add);answer(response(false,"https://soundcloud.com/owner-a/song"));
  SystemClock.now=9000;check(!results.get(0).track.playing&&results.get(0).track.positionNow()==33000,"paused real source freezes progress");
  CustomProfileExtraRows.Block other=new CustomProfileExtraRows.Block();CustomProfileIntegrations.loadRich(0,202,other,results::add);answer(response(true,"https://soundcloud.com/owner-b/song"));
  check(CustomProfileIntegrations.openUrl(b).equals("https://soundcloud.com/owner-a/song"),"same block id in another owner cannot replace current card tap source");
  CustomProfileIntegrations.clearCache();results.clear();CustomProfileIntegrations.loadRich(0,101,b,results::add);
  CustomProfileIntegrations.clearCache();answer(response(true,"https://soundcloud.com/stale/song"));check(results.isEmpty(),"invalidated pending response cannot restore old track");
  CustomProfileIntegrations.loadRich(0,101,b,results::add);answer(new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("mode",0))));
  check(results.get(0).empty&&!results.get(0).hasCard(),"server TTL/no-source result collapses card, never public/history fallback");
 }
}
'''.replace('FIELDS',fields).replace('PRODUCTION',production)
        with tempfile.TemporaryDirectory(prefix='sc-loader-',dir=os.environ['TMPDIR']) as folder:
            file=Path(folder)/'LoaderHarness.java';file.write_text(code)
            compiled=subprocess.run(['javac','-J-Xmx96m',str(file)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            run=subprocess.run(['java','-Xmx64m','-cp',folder,'LoaderHarness'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

if __name__ == '__main__':
    unittest.main()
