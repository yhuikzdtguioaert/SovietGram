"""Replay production JSON cleanup with real org.json; no Android build."""
from pathlib import Path
import os, subprocess, tempfile, unittest
from test_soundcloud_protocol import method
ROOT=Path(__file__).resolve().parents[1]
H=ROOT/'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers'
class RemovalMigration(unittest.TestCase):
    def test_raw_cleanup_preserves_unrelated_fields_and_is_idempotent(self):
        source=(H/'CustomProfileExtraRows.java').read_text()
        signature='public static String removeRetiredIntegrations(String raw)'
        self.assertIn(signature,source,'Saved raw configuration requires lossless targeted migration')
        body=method(source,signature)
        code='import org.json.*; public class RemovalSeam {static final int TYPE_INTEGRATION=12;'+body+r'''
 public static void main(String[]args)throws Exception {
  JSONArray original=new JSONArray();
  for(int service=0;service<8;service++)original.put(new JSONObject().put("type",12).put("service",service).put("id","id"+service).put("custom_extension","preserved").put("accounts",new JSONObject().put("soundcloud","legacy").put("soundcloud-me","private").put("soundcloud-live","device").put("spotify","supported")));
  JSONObject link=new JSONObject().put("type",0).put("service",7).put("url","https://soundcloud.com/normal").put("extra",42);original.put(link);
  JSONObject unknown=new JSONObject().put("type",11).put("custom","keep");original.put(unknown);
  String clean=removeRetiredIntegrations(original.toString());JSONArray migrated=new JSONArray(clean);
  if(migrated.length()!=7)throw new AssertionError("drop exactly three integration blocks");
  for(int i=0;i<5;i++) {
   JSONObject b=migrated.getJSONObject(i);if(b.getInt("service")!=i||!b.getString("custom_extension").equals("preserved"))throw new AssertionError("supported identity/extension lost");
   JSONObject a=b.getJSONObject("accounts");if(a.length()!=1||!a.getString("spotify").equals("supported"))throw new AssertionError("remove only retired provider bindings");
  }
  if(!migrated.getJSONObject(5).toString().equals(link.toString())||!migrated.getJSONObject(6).toString().equals(unknown.toString()))throw new AssertionError("ordinary blocks modified");
  if(!clean.equals(removeRetiredIntegrations(clean)))throw new AssertionError("migration not idempotent");
  if(!"malformed".equals(removeRetiredIntegrations("malformed")))throw new AssertionError("must not erase unreadable unrelated data");
  if(!"".equals(removeRetiredIntegrations("")))throw new AssertionError("empty default changed");
 }
}'''
        jar=os.environ['JSON_JAR']
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'],prefix='sc-removal-json-') as d:
            p=Path(d)/'RemovalSeam.java';p.write_text(code)
            c=subprocess.run(['javac','-J-Xmx96m','-cp',jar,str(p)],capture_output=True,text=True)
            self.assertEqual(c.returncode,0,c.stderr)
            c=subprocess.run(['java','-Xmx64m','-cp',d+os.pathsep+jar,'RemovalSeam'],capture_output=True,text=True)
            self.assertEqual(c.returncode,0,c.stdout+c.stderr)
    def test_all_stored_owners_and_device_consent_cleanup(self):
        extra=(H/'CustomProfileExtraRows.java').read_text()
        scope=(H/'SovietGramAccountScope.java').read_text()
        cleanup=method(extra,'public static String removeRetiredIntegrations(String raw)')
        migration=method(extra,'public static void migrateRemovedIntegrations()')
        migration=migration.replace('org.telegram.messenger.ApplicationLoader','ApplicationLoader').replace('android.content.Context','Context')
        root=method(scope,'private static JSONObject root()')
        code='''import org.json.*;import java.util.*;
public class OwnerCleanupSeam {
 static class Item {String value="";String String(){return value;}String getKey(){return "customProfileExtraBlocks";}void setConfigString(String s){value=s;}}
 static class NekoConfig {static Item customProfileExtraBlocks=new Item();}
 static class NaConfig {static NaConfig INSTANCE=new NaConfig();Item scopes=new Item();Item getSovietGramAccountScopes(){return scopes;}}
 static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
 static class Context {static final int MODE_PRIVATE=0;Map<String,Prefs> stores=new HashMap<>();Prefs getSharedPreferences(String key,int mode){return stores.computeIfAbsent(key,k->new Prefs());}}
 static class Prefs {Map<String,Object> data=new HashMap<>();Prefs edit(){return this;}Prefs clear(){data.clear();return this;}void apply(){}}
 static class ApplicationLoader {static Context applicationContext=new Context();}
 static class CustomProfileExtraRows {static final int TYPE_INTEGRATION=12;
 CLEANUP
 MIGRATION
 }
 static JSONObject parse(String s){return new JSONObject(s);}
 ROOT
 public static void main(String[]args)throws Exception {
  String blocks="[{\\\"type\\\":12,\\\"service\\\":7},{\\\"type\\\":0,\\\"url\\\":\\\"https://soundcloud.com/normal\\\",\\\"extra\\\":42}]";
  JSONObject before=new JSONObject().put("@",101).put("101",new JSONObject().put("customProfileExtraBlocks",blocks).put("premium",true)).put("202",new JSONObject().put("customProfileExtraBlocks",blocks).put("token","unchanged"));
  NaConfig.INSTANCE.scopes.value=before.toString();NekoConfig.customProfileExtraBlocks.value=blocks;
  Prefs consent=ApplicationLoader.applicationContext.getSharedPreferences("soundcloud_device_rpc",0);consent.data.put("publishing_owner",101L);consent.data.put("owner_202",true);
  Prefs other=ApplicationLoader.applicationContext.getSharedPreferences("other",0);other.data.put("token","keep");
  CustomProfileExtraRows.migrateRemovedIntegrations();
  if(!consent.data.isEmpty()||!other.data.get("token").equals("keep"))throw new AssertionError("cleanup scope must be the old feature preference file only");
  if(new JSONArray(NekoConfig.customProfileExtraBlocks.value).length()!=1)throw new AssertionError("live block migration");
  JSONObject after=root();for(String owner:new String[]{"101","202"})if(new JSONArray(after.getJSONObject(owner).getString("customProfileExtraBlocks")).length()!=1)throw new AssertionError("all saved owners migrated");
  if(after.getLong("@")!=101||!after.getJSONObject("101").getBoolean("premium")||!after.getJSONObject("202").getString("token").equals("unchanged"))throw new AssertionError("unrelated identity data changed");
 }
}'''.replace('CLEANUP',cleanup).replace('MIGRATION',migration).replace('ROOT',root)
        jar=os.environ['JSON_JAR']
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'],prefix='sc-owner-cleanup-') as d:
            p=Path(d)/'OwnerCleanupSeam.java';p.write_text(code)
            result=subprocess.run(['javac','-J-Xmx96m','-cp',jar,str(p)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-Xmx64m','-cp',d+os.pathsep+jar,'OwnerCleanupSeam'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        sync=method(scope,'public static synchronized void syncTo(int account)')
        self.assertLess(sync.index('migrateRemovedIntegrations()'),sync.index('if (incoming == outgoing)'))
        self.assertLess(sync.index('write(migrated)'),sync.index('if (incoming == outgoing)'))

    def test_scope_cloud_and_export_paths_use_cleanup(self):
        scope=(H/'SovietGramAccountScope.java').read_text()
        self.assertIn('removeRetiredIntegrations',scope)
        helper=(H/'CustomProfileHelper.java').read_text()
        self.assertIn('removeRetiredIntegrations',helper)
        extra=(H/'CustomProfileExtraRows.java').read_text()
        self.assertIn('if (block.type == TYPE_INTEGRATION && !CustomProfileIntegrations.isSupported(block.service))',extra)
