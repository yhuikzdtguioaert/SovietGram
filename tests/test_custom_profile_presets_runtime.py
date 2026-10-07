"""Exercise the real Java helper with small Android/config boundary doubles, not Gradle.
Run with JAVA_HOME pointing at a JDK and JSON_JAR at an org.json Java jar.
No network/install/build is performed by this test.
"""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/CustomProfilePresets.java'
PROFILE_HELPER = SOURCE.with_name('CustomProfileHelper.java')
JAVA_HOME = os.environ.get('JAVA_HOME', '')
JAVAC = str(pathlib.Path(JAVA_HOME) / 'bin/javac') if JAVA_HOME else shutil.which('javac')
JAVA = str(pathlib.Path(JAVA_HOME) / 'bin/java') if JAVA_HOME else shutil.which('java')
JSON_JAR = os.environ.get('JSON_JAR')

STUBS = {
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 boolean contains(String k); String getString(String k,String d); Editor edit();
 interface Editor { Editor putString(String k,String v); Editor remove(String k); boolean commit(); void apply(); }
}''',
'android/content/Context.java': '''package android.content;
import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0; public static boolean failCommit;
 private final Map<String,String> data=new HashMap<>();
 public SharedPreferences getSharedPreferences(String name,int mode) { return new SharedPreferences() {
 public boolean contains(String k){return data.containsKey(k);}
 public String getString(String k,String d){return data.getOrDefault(k,d);}
 public Editor edit(){return new Editor(){ Map<String,String> updates=new HashMap<>();
 public Editor putString(String k,String v){updates.put(k,v);return this;}
 public Editor remove(String k){updates.put(k,null);return this;}
 public boolean commit(){for(var e:updates.entrySet()) {if(e.getValue()==null)data.remove(e.getKey());else data.put(e.getKey(),e.getValue());}return !failCommit;}
 public void apply(){commit();}};}
 }; }
}''',
'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger;
public class ApplicationLoader {
 public static android.content.Context applicationContext=new android.content.Context();
 public static java.io.File files; public static java.io.File getFilesDirFixed(){return files;}
}''',
'org/telegram/messenger/FileLog.java': '''package org.telegram.messenger;
public class FileLog {public static void e(Throwable t){} public static void e(String s){} }''',
'org/telegram/messenger/AndroidUtilities.java': '''package org.telegram.messenger;
public class AndroidUtilities {
 private static final java.util.concurrent.BlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();
 public static boolean defer;
 public static void runOnUIThread(Runnable r){if(defer)queue.add(r);else r.run();}
 public static Runnable awaitPending()throws Exception {Runnable r=queue.poll(10,java.util.concurrent.TimeUnit.SECONDS);if(r==null)throw new AssertionError("No UI completion");return r;}
}''',
'tw/nekomimi/nekogram/config/ConfigItem.java': '''package tw.nekomimi.nekogram.config;
public class ConfigItem {
 public static final int configTypeBool=0,configTypeInt=1,configTypeString=2,configTypeLong=5,configTypeFloat=6;
 public final String key;public final int type;public final Object defaultValue;public Object value;
 public ConfigItem(String k,int t,Object d){key=k;type=t;defaultValue=d;value=d;}
 public String getKey(){return key;} public boolean Bool(){return (Boolean)value;} public int Int(){return (Integer)value;}
 public String String(){return (String)value;} public void setConfigString(String s){value=s;}
 public void changed(Object o){value=o;} public void saveConfig(){}
 public void setConfigBool(boolean b){value=b;} public void setConfigInt(int i){value=i;}
}''',
'tw/nekomimi/nekogram/NekoConfig.java': '''package tw.nekomimi.nekogram;
import tw.nekomimi.nekogram.config.ConfigItem;
public class NekoConfig {
 public static final Object sync=new Object();
 public static final ConfigItem customProfileBannerPath=new ConfigItem("customProfileBannerPath",2,"");
 public static final ConfigItem customProfileBackgroundPath=new ConfigItem("customProfileBackgroundPath",2,"");
 public static final ConfigItem customProfileNameFontPath=new ConfigItem("customProfileNameFontPath",2,"");
 public static final ConfigItem customProfileThoughtFontPath=new ConfigItem("customProfileThoughtFontPath",2,"");
 public static final ConfigItem customProfileExtraBlocks=new ConfigItem("customProfileExtraBlocks",2,"");
 public static final ConfigItem color=new ConfigItem("customProfileBannerColor",1,7);
 public static final ConfigItem enabled=new ConfigItem("customProfileEnabled",0,false);
 public static final ConfigItem token=new ConfigItem("integrationToken",2,"secret");
}''',
'tw/nekomimi/nekogram/helpers/CustomProfileHelper.java': '''package tw.nekomimi.nekogram.helpers;
import tw.nekomimi.nekogram.NekoConfig;import tw.nekomimi.nekogram.config.ConfigItem;
public class CustomProfileHelper {
 public static int changes,releases;
 private static final ConfigItem[] EXPORTED={NekoConfig.color,NekoConfig.enabled,NekoConfig.customProfileExtraBlocks};
 private static boolean apply(ConfigItem item,String text){
  if(item.type==ConfigItem.configTypeInt)item.setConfigInt(Integer.parseInt(text));
  else if(item.type==ConfigItem.configTypeBool)item.setConfigBool(Boolean.parseBoolean(text));
  else item.setConfigString(text);
  return true;
 }
 public static ConfigItem[] portableItems(){return EXPORTED.clone();}
 public static void releaseVideo(){releases++;} public static void onSettingsChanged(){changes++;}
}''',
'tw/nekomimi/nekogram/helpers/SovietGramAccountScope.java': '''package tw.nekomimi.nekogram.helpers;
public class SovietGramAccountScope {public static int live=0;public static boolean isLive(int account){return live==account;} }''',
'tw/nekomimi/nekogram/helpers/SovietGramTokenStore.java': '''package tw.nekomimi.nekogram.helpers;
public class SovietGramTokenStore { public static long ownId(int account){return account<0?0:100+account;} }''',
'tw/nekomimi/nekogram/helpers/CustomProfileExtraRows.java': '''package tw.nekomimi.nekogram.helpers;
public class CustomProfileExtraRows {public static final int TYPE_INTEGRATION=12;public static void releaseIntegrationAccounts(){} }''',
}

HARNESS = '''import java.io.*;import java.nio.file.*;import org.json.*;
import tw.nekomimi.nekogram.helpers.*;import tw.nekomimi.nekogram.NekoConfig;
import org.telegram.messenger.ApplicationLoader;
public class PresetHarness {
 static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static Object call(String name,int account,int slot)throws Exception {
  java.lang.reflect.Method m;
  try {m=CustomProfilePresets.class.getMethod(name,int.class,int.class);}
  catch(NoSuchMethodException e){throw new AssertionError("Missing preset behavior: "+name,e);}
  return m.invoke(null,account,slot);
 }
 public static void main(String[] args)throws Exception {
  ApplicationLoader.files=new File(args[0]);ApplicationLoader.files.mkdirs();
  require(CustomProfilePresets.SLOT_COUNT==3,"exactly three slots");
  for(int i=0;i<3;i++)require(!CustomProfilePresets.has(0,i),"empty slot");
  boolean invalid=false;try{CustomProfilePresets.has(0,3);}catch(IllegalArgumentException e){invalid=true;}
  require(invalid,"slot bound");
  invalid=false;try{CustomProfilePresets.has(1,0);}catch(IllegalStateException e){invalid=true;}
  require(invalid,"non-live capture refused");
  if(!args[1].equals("empty")) {
   Path original=ApplicationLoader.files.toPath().resolve("banner");Files.writeString(original,"MY BANNER");
   NekoConfig.customProfileBannerPath.setConfigString(original.toString());NekoConfig.color.setConfigInt(42);
   NekoConfig.customProfileExtraBlocks.setConfigString("[{\\"id\\":\\"integrated\\",\\"type\\":12,\\"service\\":6,\\"accounts\\":{\\"soundcloud\\":\\"private-id\\"},\\"url\\":\\"identity\\",\\"int_style\\":3}]");
   require(Boolean.TRUE.equals(call("save",0,0)),"save succeeds");
   require(CustomProfilePresets.has(0,0),"slot stored");
   String raw=ApplicationLoader.applicationContext.getSharedPreferences("custom_profile_visual_presets",0).getString("slot_100_0","");
   require(!raw.contains("private-id")&&!raw.contains("identity")&&!raw.contains("secret"),"no integration identities or credentials captured");
   JSONObject saved=new JSONObject(raw);JSONObject values=saved.getJSONObject("values");
   Path copy=Paths.get(values.getString("customProfileBannerPath"));
   require(!copy.equals(original),"immutable asset copy");Files.writeString(original,"WORKSHOP BANNER");
   require(Files.readString(copy).equals("MY BANNER"),"snapshot survives source overwrite");
   SovietGramAccountScope.live=1;require(!CustomProfilePresets.has(1,0),"owner isolation");
   SovietGramAccountScope.live=0;
   if(args[1].equals("workshop-import")) {
    require(CustomProfileHelper.importProfileJson(new JSONObject().put("customProfileBannerColor",99).put("customProfileEnabled",true)),"real workshop importer applies visual");
    require(NekoConfig.customProfileBannerPath.String().isEmpty(),"workshop importer clears previous device-local path");
    require(Files.readString(copy).equals("MY BANNER"),"workshop importer preserves personal slot media");
    require(Boolean.TRUE.equals(call("save",0,1)),"save installed workshop in second slot");
    for(int round=0;round<3;round++) {
     require(Boolean.TRUE.equals(call("apply",0,0))&&NekoConfig.color.Int()==42,"return from installed workshop to personal look");
     require(Files.readString(Paths.get(NekoConfig.customProfileBannerPath.String())).equals("MY BANNER"),"personal media preserved through actual workshop install");
     require(Boolean.TRUE.equals(call("apply",0,1))&&NekoConfig.color.Int()==99,"return later to saved workshop look");
     require(NekoConfig.customProfileBannerPath.String().isEmpty(),"workshop slot restores descriptor-only media state");
    }
   }
   if(args[1].equals("all-slots")) {
    for(int slot=1;slot<3;slot++) {
     NekoConfig.color.setConfigInt(42+slot);
     Files.writeString(original,"BANNER "+slot);
     require(Boolean.TRUE.equals(call("save",0,slot)),"save each of exactly three slots");
    }
    for(int slot=0;slot<3;slot++) {
     require(Boolean.TRUE.equals(call("apply",0,slot))&&NekoConfig.color.Int()==42+slot,"each slot retains its own styling");
     String expected=slot==0?"MY BANNER":"BANNER "+slot;
     require(Files.readString(Paths.get(NekoConfig.customProfileBannerPath.String())).equals(expected),"each slot retains independent media");
    }
    NekoConfig.color.setConfigInt(500);
    require(Boolean.TRUE.equals(call("save",0,0)),"replace one slot");
    for(int slot=1;slot<3;slot++)require(Boolean.TRUE.equals(call("apply",0,slot))&&NekoConfig.color.Int()==42+slot,"replacing one slot preserves both other slots");
    require(Boolean.TRUE.equals(call("clear",0,1)),"clear middle slot");
    require(Boolean.TRUE.equals(call("apply",0,0))&&NekoConfig.color.Int()==500,"first slot survives middle clear");
    require(Boolean.TRUE.equals(call("apply",0,2))&&NekoConfig.color.Int()==44,"third slot survives middle clear");
   }
   if(args[1].equals("corrupt-live")) {
    NekoConfig.color.setConfigInt(99);
    NekoConfig.customProfileExtraBlocks.setConfigString("broken current block JSON");
    require(Boolean.TRUE.equals(call("apply",0,0)),"valid saved appearance can recover malformed current blocks");
    require(NekoConfig.color.Int()==42,"saved visual restored despite corrupt live blocks");
    require(NekoConfig.customProfileExtraBlocks.String().contains("int_style"),"saved blocks restored");
    require(NekoConfig.token.String().equals("secret"),"recovery never modifies credentials");
   }
   if(args[1].equals("failure")) {
    NekoConfig.color.setConfigInt(99);
    android.content.Context.failCommit=true;
    require(Boolean.FALSE.equals(call("save",0,0)),"failed preference commit is reported");
    require(ApplicationLoader.applicationContext.getSharedPreferences("custom_profile_visual_presets",0).getString("slot_100_0","").equals(raw),"failed save retains previous slot even if preferences updated memory");
    require(Files.exists(copy),"failed save keeps previous media");
    require(Boolean.FALSE.equals(call("clear",0,0))&&CustomProfilePresets.has(0,0),"failed clear retains slot");
    android.content.Context.failCommit=false;
    JSONObject corrupt=new JSONObject(raw);corrupt.getJSONObject("values").put("customProfileBannerColor","bad");
    ApplicationLoader.applicationContext.getSharedPreferences("custom_profile_visual_presets",0).edit().putString("slot_100_0",corrupt.toString()).commit();
    require(Boolean.FALSE.equals(call("apply",0,0))&&NekoConfig.color.Int()==99&&CustomProfileHelper.changes==0,"corrupt types never partially apply");
    ApplicationLoader.applicationContext.getSharedPreferences("custom_profile_visual_presets",0).edit().putString("slot_100_0",raw).commit();
    Files.delete(copy);
    require(Boolean.FALSE.equals(call("apply",0,0))&&NekoConfig.color.Int()==99&&CustomProfileHelper.changes==0,"missing saved media never partially apply");
   }
   if(args[1].equals("async")) {
    org.telegram.messenger.AndroidUtilities.defer=true;
    java.util.concurrent.atomic.AtomicReference<Boolean> outcome=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.function.Consumer<Boolean> completed=outcome::set;
    java.lang.reflect.Method saveAsync;
    try {saveAsync=CustomProfilePresets.class.getMethod("saveAsync",int.class,int.class,java.util.function.Consumer.class);}
    catch(NoSuchMethodException e){throw new AssertionError("Missing async preset save",e);}
    NekoConfig.color.setConfigInt(55);saveAsync.invoke(null,0,2,completed);
    NekoConfig.color.setConfigInt(66);
    org.telegram.messenger.AndroidUtilities.awaitPending().run();
    require(Boolean.TRUE.equals(outcome.get()),"async save completes");
    require(Boolean.TRUE.equals(call("apply",0,2))&&NekoConfig.color.Int()==55,"save captures initiating UI appearance before worker runs");
    java.lang.reflect.Method applyAsync;
    try {applyAsync=CustomProfilePresets.class.getMethod("applyAsync",int.class,int.class,java.util.function.Consumer.class);}
    catch(NoSuchMethodException e){throw new AssertionError("Missing async preset apply",e);}
    outcome.set(null);NekoConfig.color.setConfigInt(88);applyAsync.invoke(null,0,0,completed);
    Runnable finish=org.telegram.messenger.AndroidUtilities.awaitPending();
    SovietGramAccountScope.live=1;finish.run();
    require(Boolean.FALSE.equals(outcome.get())&&NekoConfig.color.Int()==88,"switching account during restore never applies to another owner");
    SovietGramAccountScope.live=0;
   }
   if(args[1].equals("apply")) {
    NekoConfig.color.setConfigInt(99);NekoConfig.enabled.setConfigBool(true);
    NekoConfig.customProfileExtraBlocks.setConfigString("[{\\"id\\":\\"workshop\\",\\"type\\":12,\\"service\\":6,\\"accounts\\":{\\"soundcloud\\":\\"current-owner\\"},\\"url\\":\\"current-url\\",\\"int_style\\":1}]");
    require(Boolean.TRUE.equals(call("save",0,1)),"workshop saved separately");
    for(int n=0;n<3;n++) {
     require(Boolean.TRUE.equals(call("apply",0,0)),"own slot applies");
     require(NekoConfig.color.Int()==42&&!NekoConfig.enabled.Bool(),"complete own visual state restored");
     require(Files.readString(Paths.get(NekoConfig.customProfileBannerPath.String())).equals("MY BANNER"),"own media restored");
     String extras=NekoConfig.customProfileExtraBlocks.String();
     require(extras.contains("current-owner")&&!extras.contains("private-id"),"live connection retained, never restored from preset");
     require(extras.contains("\\"int_style\\":3"),"integration styling restored");
     require(Boolean.TRUE.equals(call("apply",0,1)),"workshop slot applies");
     require(NekoConfig.color.Int()==99&&NekoConfig.enabled.Bool(),"workshop visual state restored");
     require(Files.readString(Paths.get(NekoConfig.customProfileBannerPath.String())).equals("WORKSHOP BANNER"),"workshop media restored");
    }
    require(CustomProfileHelper.changes==6&&CustomProfileHelper.releases==6,"every apply refreshes profile and media once");
    require(NekoConfig.token.String().equals("secret"),"auth token untouched");
    Path active=Paths.get(NekoConfig.customProfileBannerPath.String());
    require(!active.equals(copy),"live assets independent of slot storage");
    require(Boolean.TRUE.equals(call("clear",0,1)),"clear saved slot");
    require(!CustomProfilePresets.has(0,1)&&Files.exists(active),"clear does not destroy active appearance");
    require(Boolean.FALSE.equals(call("apply",0,1))&&NekoConfig.color.Int()==99,"empty apply is non-destructive");
   }
  }
  System.out.println("PASS "+args[1]);
 }
}'''

@unittest.skipUnless(JAVAC and JAVA and JSON_JAR, 'Set JAVA_HOME and JSON_JAR for lightweight JVM tests')
class CustomProfilePresetRuntime(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compilation = tempfile.TemporaryDirectory(prefix='profile-preset-javac-', dir=os.environ.get('TMPDIR'))
        cls.addClassCleanup(cls.compilation.cleanup)
        cls.classes = pathlib.Path(cls.compilation.name)
        files = []
        helper_source = PROFILE_HELPER.read_text()
        start = helper_source.index('    public static boolean importProfileJson(')
        end = helper_source.index('\n    private static boolean apply(', start)
        workshop_import = helper_source[start:end].replace('@Nullable ', '')
        workshop_import = workshop_import.replace('JSONObject', 'org.json.JSONObject')
        for relative, text in STUBS.items():
            if relative.endswith('/CustomProfileHelper.java'):
                # Compile the actual production workshop importer at its Android/config seam.
                text = text.rstrip()[:-1] + workshop_import + '\n}'
            dest = cls.classes / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(text)
            files.append(str(dest))
        harness = cls.classes / 'PresetHarness.java'
        harness.write_text(HARNESS)
        compiled = subprocess.run([JAVAC, '-J-Xmx96m', '-cp', JSON_JAR, '-d', str(cls.classes),
                                   *files, str(SOURCE), str(harness)], text=True, capture_output=True)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)

    def run_harness(self, mode):
        with tempfile.TemporaryDirectory(prefix='profile-preset-test-', dir=os.environ.get('TMPDIR')) as folder:
            result = subprocess.run([JAVA, '-Xmx64m', '-cp', str(self.classes) + os.pathsep + JSON_JAR,
                                     'PresetHarness', folder, mode], text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS ' + mode, result.stdout)

    def test_three_empty_slots_and_owner_guards(self):
        self.run_harness('empty')

    def test_failed_storage_and_invalid_media_do_not_destroy_presets_or_live_appearance(self):
        self.run_harness('failure')

    def test_async_capture_and_account_switch_guard(self):
        self.run_harness('async')

    def test_own_and_workshop_appearance_switch_repeatedly_without_identity_changes(self):
        self.run_harness('apply')

    def test_actual_workshop_import_preserves_personal_slot_and_round_trips(self):
        self.run_harness('workshop-import')

    def test_all_three_slots_have_independent_visuals_media_and_management(self):
        self.run_harness('all-slots')

    def test_valid_slot_can_recover_malformed_current_blocks(self):
        self.run_harness('corrupt-live')

    def test_save_preserves_visual_media_without_capturing_credentials(self):
        self.run_harness('save')

if __name__ == '__main__':
    unittest.main()
