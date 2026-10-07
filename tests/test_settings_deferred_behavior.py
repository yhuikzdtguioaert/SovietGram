"""Compile/execute actual settings method bodies against small UI boundary fakes.
No Android build: scheduling, config reads, row membership and lifecycle wiring are
real source slices; RecyclerView/View rendering and persistence are not exercised.
Config-cell structural transactions and selection ordering are covered separately
by test_config_cell_deferred_behavior.py. Opening-time popup indices in
NekoChatSettingsActivity remain a separate risk outside these source slices.
Run with SETTINGS_TEST_JDK=/path/to/jdk/bin python3 -m unittest discover -s tests
-p test_settings_deferred_behavior.py -v
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings'
JDK = Path(os.environ.get('SETTINGS_TEST_JDK', '/home/user/.hermes/cache/scratch/updater-jdk/usr/lib/jvm/java-21-openjdk-amd64/bin'))


def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class DeferredSettingsBehaviorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        chat = (SETTINGS / 'NekoChatSettingsActivity.java').read_text()
        base = (SETTINGS / 'BaseNekoXSettingsActivity.java').read_text()
        methods = [method(chat, s) for s in [
            'public NekoChatSettingsActivity()', 'public View createView(Context context)',
            'private void checkSkipOpenLinkConfirmRows()', 'private void checkConfirmAVRows()']]
        # Include newly introduced reconciliation methods without substituting test logic.
        for signature in ['private void reconcileConditionalRows()', 'private void setConditionalRow(']:
            if signature in chat:
                methods.append(method(chat, signature))
        body = '\n'.join(methods)
        getters = sorted(set(re.findall(r'NaConfig.INSTANCE.(get\w+)\(\)', body)))
        configs = sorted(set(re.findall(r'NekoConfig\.(\w+)', body)))
        rows = re.findall(r'private final AbstractConfigCell (\w+) = cellGroup.appendCell', chat)
        fields = '\n'.join(f'final AbstractConfigCell {r} = cellGroup.appendCell(new AbstractConfigCell("{r}"));' for r in rows)
        source = '''import java.util.*;
class Config { String key; boolean b; int i; Config(String k){key=k;} boolean Bool(){return b;} int Int(){return i;} float Float(){return 14f;} String getKey(){return key;} }
class NaConfig { static final NaConfig INSTANCE = new NaConfig(); GETTERS }
class NekoConfig { CONFIGS }
class AbstractConfigCell { String key; AbstractConfigCell(String k){key=k;} }
interface Callback {void changed(String key,Object value);}
class CellGroup { ArrayList<AbstractConfigCell> rows = new ArrayList<>(); Callback callBackSettingsChanged; AbstractConfigCell appendCell(AbstractConfigCell c){rows.add(c);return c;} }
class Context {}
class View {static final int VISIBLE=0,GONE=8; void invalidate(){} }
class BlurredRecyclerView extends View { boolean busy; Object adapter; ArrayDeque<Runnable> queue = new ArrayDeque<>(); boolean isComputingLayout(){return busy;} void post(Runnable r){queue.add(r);} void setAdapter(Object a){adapter=a;} void drain(){while(!queue.isEmpty())queue.remove().run();} }
class ListAdapter {ListAdapter(Context c){} }
class ActionBarMenuItem {void setContentDescription(String s){} void addSubItem(int a,int b,String c){} void setVisibility(int i){} }
class ActionBarMenu {ActionBarMenuItem addItem(int a,int b){return new ActionBarMenuItem();} }
class ActionBar {ActionBarMenu createMenu(){return new ActionBarMenu();} }
class R {static class drawable {static int ic_ab_other,msg_reset;} static class string {static int AccDescrMoreOptions,ResetStickerSize;} }
class BuildVars {static boolean LOGS_ENABLED=false;}
class TranscribeHelper {static final int TRANSCRIBE_OPENAI=4;}
class MediaController {static final MediaController INSTANCE=new MediaController(); int wakeLockUpdates; static MediaController getInstance(){return INSTANCE;} void recreateProximityWakeLock(){wakeLockUpdates++;} }
class UndoView {static int ACTION_NEED_RESTART; int shows; void showWithAction(int a,int b,Object c,Object d){shows++;} }
class InputBarPreviewCell {void updateInputBarState(){} }
class BaseStub {
 BlurredRecyclerView listView; boolean isFinished; ActionBar actionBar=new ActionBar(); UndoView tooltip=new UndoView();
 Map<String,Integer> rowMap=new HashMap<>(); int notifications;
 View createView(Context c){listView=new BlurredRecyclerView();return listView;}
 void setupDefaultListeners(){} String getString(int i){return "";}
 void addRowsToMap(CellGroup g){rowMap.clear();for(int i=0;i<g.rows.size();i++)rowMap.put(g.rows.get(i).key,i);}
 void notifyRowInserted(int i){notifications++;} void notifyRowRemoved(int i){notifications++;} void notifyAllRowsChanged(){notifications++;}
 RUN_IDLE
}
public class NekoChatSettingsActivity extends BaseStub {
 final CellGroup cellGroup = new CellGroup(); FIELDS
 ListAdapter listAdapter; ActionBarMenuItem menuItem; View stickerSizeCell=new View(); InputBarPreviewCell inputBarPreviewCell;
 METHODS
 static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
 void change(Config c,Object v){if(v instanceof Boolean)c.b=(boolean)v;else c.i=(int)v;cellGroup.callBackSettingsChanged.changed(c.key,v);}
 public static void main(String[] args){
  NekoChatSettingsActivity a=new NekoChatSettingsActivity(); a.createView(new Context());
  if(args[0].equals("proximity")){
   BlurredRecyclerView old=a.listView; old.busy=true;
   a.change(NekoConfig.disableProximityEvents,true);
   require(MediaController.getInstance().wakeLockUpdates==1,"proximity wake-lock update must run immediately during layout");
   a.change(NekoConfig.disableProximityEvents,false);
   require(MediaController.getInstance().wakeLockUpdates==2,"each proximity change must update the wake lock exactly once");
   require(old.queue.isEmpty() && a.notifications==0 && a.tooltip.shows==0,"proximity branch must not enqueue or execute view work");
   a.change(NekoConfig.showSeconds,true);
   require(a.tooltip.shows==0 && !old.queue.isEmpty(),"non-proximity view effects must still defer");
   a.createView(new Context());
   old.busy=false; old.drain();
   require(MediaController.getInstance().wakeLockUpdates==2,"view replacement must neither lose nor replay wake-lock updates");
   require(a.tooltip.shows==0,"replacement must still discard old-view tooltip work");
   a.change(NekoConfig.showSeconds,false);
   require(a.tooltip.shows==1 && MediaController.getInstance().wakeLockUpdates==2,"other settings must not update the proximity wake lock");
   a.change(NekoConfig.disableProximityEvents,true);
   require(MediaController.getInstance().wakeLockUpdates==3 && a.tooltip.shows==1,"idle proximity update must preserve branch exclusivity");
  } else if(args[0].equals("ordering")){
   a.listView.busy=true;
   a.change(NaConfig.INSTANCE.getUseEditedIcon(),true);
   a.change(NaConfig.INSTANCE.getTranscribeProvider(),4);
   require(a.cellGroup.rows.contains(a.customEditedMessageRow),"mutation must wait while computing layout");
   require(!a.cellGroup.rows.contains(a.transcribeProviderOpenAiRow),"provider mutation must wait");
   a.listView.busy=false;
   a.change(NaConfig.INSTANCE.getUseEditedIcon(),false);
   a.change(NaConfig.INSTANCE.getTranscribeProvider(),0);
   a.listView.drain();
   require(a.cellGroup.rows.contains(a.customEditedMessageRow),"queued old edited-icon value overrode newer config");
   require(!a.cellGroup.rows.contains(a.transcribeProviderOpenAiRow),"queued old provider value overrode newer config");
  } else {
   BlurredRecyclerView old=a.listView; old.busy=true;
   a.change(NaConfig.INSTANCE.getUseEditedIcon(),true);
   a.change(NaConfig.INSTANCE.getTranscribeProvider(),4);
   a.change(NaConfig.INSTANCE.getIosInputAppearance(),true);
   a.change(NaConfig.INSTANCE.getConfirmAllLinks(),true);
   a.change(NekoConfig.useChatAttachMediaMenu,true);
   a.createView(new Context());
   require(!a.cellGroup.rows.contains(a.customEditedMessageRow),"recreation lost queued edited-icon mutation");
   require(a.cellGroup.rows.contains(a.transcribeProviderOpenAiRow),"recreation lost provider insertion");
   require(a.cellGroup.rows.contains(a.compactInputSizeRow),"recreation lost compact input insertion");
   require(!a.cellGroup.rows.contains(a.skipOpenLinkConfirmRow),"recreation lost link confirmation removal");
   require(!a.cellGroup.rows.contains(a.confirmAVRow),"recreation lost AV confirmation removal");
   require(a.rowMap.containsKey("transcribeProviderOpenAiRow") && !a.rowMap.containsKey("customEditedMessageRow"),"recreated row maps stale");
   int count=a.notifications; old.busy=false; old.drain();
   require(a.notifications==count,"old view performed UI work after replacement");
   a.change(NaConfig.INSTANCE.getUseEditedIcon(),false);
   a.change(NaConfig.INSTANCE.getTranscribeProvider(),0);
   a.change(NaConfig.INSTANCE.getIosInputAppearance(),false);
   a.change(NaConfig.INSTANCE.getConfirmAllLinks(),false);
   a.change(NekoConfig.useChatAttachMediaMenu,false);
   a.createView(new Context());
   require(a.cellGroup.rows.contains(a.customEditedMessageRow) && !a.cellGroup.rows.contains(a.transcribeProviderOpenAiRow),"reverse recreation membership");
   require(!a.cellGroup.rows.contains(a.compactInputSizeRow) && a.cellGroup.rows.contains(a.skipOpenLinkConfirmRow) && a.cellGroup.rows.contains(a.confirmAVRow),"reverse conditional membership");
   require(new HashSet<>(a.cellGroup.rows).size()==a.cellGroup.rows.size(),"duplicate rows after reconciliation");
  }
  System.out.println("PASS " + args[0]);
 }
}
'''
        source = source.replace('GETTERS', '\n'.join(f'Config {g}=new Config("{g}"); Config {g}(){{return {g};}}' for g in getters))
        source = source.replace('CONFIGS', '\n'.join(f'static Config {c}=new Config("{c}");' for c in configs))
        source = source.replace('RUN_IDLE', method(base, 'protected void runWhenListIdle(Runnable update)'))
        source = source.replace('FIELDS', fields).replace('METHODS', body)
        cls.temp = tempfile.TemporaryDirectory(prefix='settings-java-', dir='/home/user/.hermes/cache/scratch')
        path = Path(cls.temp.name) / 'NekoChatSettingsActivity.java'
        path.write_text(source)
        subprocess.run([str(JDK / 'javac'), '-d', cls.temp.name, str(path)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_scenario(self, scenario):
        result = subprocess.run([str(JDK / 'java'), '-cp', self.temp.name, 'NekoChatSettingsActivity', scenario], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_proximity_side_effect_is_immediate_and_survives_view_replacement(self):
        self.run_scenario('proximity')

    def test_queued_old_immediate_new_reads_current_configuration(self):
        self.run_scenario('ordering')

    def test_view_recreation_reconciles_membership_and_rejects_old_work(self):
        self.run_scenario('recreation')


if __name__ == '__main__':
    unittest.main()
