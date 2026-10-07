"""JVM seams from actual config-cell methods; no Android/Gradle rendering test.
CONFIG_CELL_BASELINE=HEAD replays the same tests against baseline sources.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CELLS = Path('TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/cell')
JDK = Path(os.environ.get('SETTINGS_TEST_JDK', '/home/user/.hermes/cache/scratch/updater-jdk/usr/lib/jvm/java-21-openjdk-amd64/bin'))


def source(name):
    path = CELLS / (name + '.java')
    revision = os.environ.get('CONFIG_CELL_BASELINE')
    if revision:
        result = subprocess.run(['git', 'show', f'{revision}:{path}'], cwd=ROOT, capture_output=True, text=True)
        return result.stdout if result.returncode == 0 else ''
    return (ROOT / path).read_text() if (ROOT / path).exists() else ''


def method(text, signature):
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


class ConfigCellBehaviorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        text = source('ConfigCellTextCheck2')
        select = source('ConfigCellSelectBox')
        text_methods = '\n'.join(method(text, s) for s in [
            'public boolean toggleFullChecked()', 'public void onCheckClick()', 'public void onClick()'])
        select_methods = '\n'.join(method(select, s) for s in [
            'private void handleItemSelected(int index)', 'private int getSelectedValue(int index)'])
        helper = source('ConfigCellListUpdate')
        helper = '\n'.join(line for line in helper.splitlines() if not line.startswith(('package ', 'import ')))
        field = 'final ConfigCellListUpdate listUpdate = new ConfigCellListUpdate();' if helper else ''
        java = r'''import java.util.*;
class AbstractConfigCell {CellGroup cellGroup; void bindCellGroup(CellGroup g){cellGroup=g;} }
class ConfigItem {boolean b; int i; String getKey(){return "config";} boolean Bool(){return b;} void setConfigBool(boolean v){b=v;} void setConfigInt(int v){i=v;} }
class CheckView {boolean checked; void setChecked(boolean v,boolean animated){checked=v;} }
class ConfigCellCheckBox extends AbstractConfigCell {ConfigItem config=new ConfigItem(); CheckView cell=new CheckView(); ConfigItem getBindConfig(){return config;} }
class RecyclerListView {
 boolean busy; SelectionAdapter adapter; ArrayDeque<Runnable> posted=new ArrayDeque<>();
 boolean isComputingLayout(){return busy;} SelectionAdapter getAdapter(){return adapter;} boolean post(Runnable r){posted.add(r);return true;}
 void tick(){int count=posted.size();while(count-->0)posted.remove().run();}
 void drain(){for(int i=0;!posted.isEmpty() && i<20;i++)tick();if(!posted.isEmpty())throw new AssertionError("infinite retry");}
 static class SelectionAdapter {
  RecyclerListView view; List<String> events=new ArrayList<>(); SelectionAdapter(RecyclerListView v){view=v;}
  void notifyItemChanged(int p){event("change",p,1);} void notifyItemRangeInserted(int p,int n){event("insert",p,n);} void notifyItemRangeRemoved(int p,int n){event("remove",p,n);}
  void event(String k,int p,int n){if(view.busy)throw new AssertionError("notification while computing layout");if(p<0 || n<=0)throw new AssertionError("invalid notification");events.add(k+":"+p+":"+n);}
 }
}
class ParentLayout {int rebuilds; void rebuildFragments(int n){rebuilds++;} }
class BaseFragment {boolean isFinished; ParentLayout parent=new ParentLayout(); ParentLayout getParentLayout(){return parent;} }
interface Callback {void run(String key,Object value);}
class CellGroup {
 RecyclerListView listView=new RecyclerListView(); RecyclerListView.SelectionAdapter listAdapter=new RecyclerListView.SelectionAdapter(listView); BaseFragment thisFragment=new BaseFragment();
 ArrayList<AbstractConfigCell> rows=new ArrayList<>(); Callback callback; CellGroup(){listView.adapter=listAdapter;}
 RecyclerListView.SelectionAdapter getListAdapter(){return listAdapter;} void runCallback(String k,Object v){if(callback!=null)callback.run(k,v);}
}
HELPER
class ConfigCellTextCheck2 extends AbstractConfigCell {
 FIELD
 boolean enabled=true,collapsed=true; Runnable onCheckClick; ArrayList<ConfigCellCheckBox> checkBox=new ArrayList<>();
 String getKey(){return "toggle";} int getSelectedCount(){int n=0;for(ConfigCellCheckBox c:checkBox)if(c.config.b)n++;return n;}
 ArrayList<ConfigCellCheckBox> getCheckBox(){return checkBox;} void setCollapsed(boolean c){collapsed=c;}
 TEXT_METHODS
}
class ConfigCellSelectBox extends AbstractConfigCell {
 FIELD
 ConfigItem bindConfig=new ConfigItem(); int[] selectValues={10,20};
 SELECT_METHODS
 void select(int i){handleItemSelected(i);}
}
public class ConfigCellHarness {
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  CellGroup g=new CellGroup(); ConfigCellTextCheck2 t=new ConfigCellTextCheck2();t.bindCellGroup(g);
  t.checkBox.add(new ConfigCellCheckBox());t.checkBox.add(new ConfigCellCheckBox());g.rows.add(t);
  List<String> callbacks=new ArrayList<>();Map<AbstractConfigCell,Integer> map=new HashMap<>();
  g.callback=(k,v)->{require(!g.listView.busy,"callback while computing layout");callbacks.add(k+":"+v);map.clear();for(int i=0;i<g.rows.size();i++)map.put(g.rows.get(i),i);};
  String scenario=args[0];
  if(scenario.equals("structure")){
   g.listView.busy=true;t.onClick();require(t.collapsed && g.rows.size()==1,"rows/collapse mutated during layout");
   g.listView.tick();require(g.rows.size()==1,"retry mutated while still busy");
   g.rows.add(0,new AbstractConfigCell());g.listView.busy=false;g.listView.drain();
   require(!t.collapsed && g.rows.size()==4,"expansion missing");require(map.get(t.checkBox.get(1))==3,"callback map stale");
   require(g.listAdapter.events.equals(List.of("insert:2:2","change:1:1")),"wrong insertion notifications");
   for(ConfigCellCheckBox c:t.checkBox)require(c.cellGroup==g,"child not bound");
   t.onClick();require(t.collapsed && g.rows.size()==2 && map.size()==2,"collapse/map mismatch");
  } else if(scenario.equals("toggle")){
   g.listView.busy=true;require(t.toggleFullChecked(),"toggle return changed");
   require(t.getSelectedCount()==2 && t.checkBox.get(0).cell.checked,"config/visual toggle lost");
   require(g.listAdapter.events.isEmpty(),"toggle notified while busy");
   g.rows.add(0,new AbstractConfigCell());g.listView.busy=false;g.listView.drain();require(g.listAdapter.events.equals(List.of("change:1:1")),"toggle used stale position");
   t.onCheckClick();require(t.getSelectedCount()==0 && !t.checkBox.get(0).cell.checked,"reverse visual toggle");require(callbacks.equals(List.of("toggle_check:false")),"toggle callback incorrect");
  } else if(scenario.equals("select")){
   ConfigCellSelectBox s=new ConfigCellSelectBox();s.bindCellGroup(g);g.rows.add(s);g.listView.busy=true;s.select(0);
   require(g.listAdapter.events.isEmpty() && g.thisFragment.parent.rebuilds==0 && callbacks.isEmpty(),"selection UI/callback did not defer");
   g.listView.tick();g.listView.busy=false;s.select(1);g.listView.drain();
   require(s.bindConfig.i==20,"older queued selection overrode newer");require(callbacks.equals(List.of("config:10","config:20")),"selection ordering changed");
   require(g.thisFragment.parent.rebuilds==2,"rebuild lost");
  } else if(scenario.equals("lifecycle")){
   ConfigCellSelectBox s=new ConfigCellSelectBox();s.bindCellGroup(g);g.rows.add(s);RecyclerListView old=g.listView;old.busy=true;t.onClick();s.select(0);
   g.listView=new RecyclerListView();g.listAdapter=new RecyclerListView.SelectionAdapter(g.listView);g.listView.adapter=g.listAdapter;old.busy=false;old.drain();
   require(t.collapsed && g.rows.size()==2 && callbacks.isEmpty() && g.thisFragment.parent.rebuilds==0,"old view work survived replacement");
   g.listView.busy=true;t.onClick();s.select(1);g.thisFragment.isFinished=true;g.listView.busy=false;g.listView.drain();
   require(t.collapsed && callbacks.isEmpty(),"finished fragment work executed");
  } else if(scenario.equals("removed")){
   g.listView.busy=true;t.onClick();g.rows.remove(t);g.listView.busy=false;g.listView.drain();require(g.rows.isEmpty() && t.collapsed && callbacks.isEmpty(),"removed toggle mutated rows");
   ConfigCellSelectBox s=new ConfigCellSelectBox();s.bindCellGroup(g);g.rows.add(s);g.listView.busy=true;s.select(0);g.rows.remove(s);g.listView.busy=false;g.listView.drain();require(g.listAdapter.events.isEmpty(),"removed select notified invalid position");
  } else if(scenario.equals("empty")){
   t.checkBox.clear();t.onClick();t.onClick();require(g.rows.size()==1 && t.collapsed && callbacks.size()==2,"empty toggle behavior changed");
  } else if(scenario.equals("adapter")){
   g.listView.busy=true;t.onClick();g.listAdapter=new RecyclerListView.SelectionAdapter(g.listView);g.listView.adapter=g.listAdapter;g.listView.busy=false;g.listView.drain();require(t.collapsed && callbacks.isEmpty(),"old adapter work ran");
  } else if(scenario.equals("check")){
   g.listView.busy=true;t.onCheckClick();require(callbacks.isEmpty(),"check callback ran during layout");g.listView.busy=false;g.listView.drain();require(t.getSelectedCount()==2 && callbacks.equals(List.of("toggle_check:true")),"check transaction missing");
  }
  System.out.println("PASS "+scenario);
 }
}
'''
        java = java.replace('HELPER', helper).replace('FIELD', field).replace('TEXT_METHODS', text_methods).replace('SELECT_METHODS', select_methods)
        cls.temp = tempfile.TemporaryDirectory(prefix='config-cell-java-', dir='/home/user/.hermes/cache/scratch')
        path = Path(cls.temp.name) / 'ConfigCellHarness.java'
        path.write_text(java)
        result = subprocess.run([str(JDK / 'javac'), '-J-Xmx128m', '-d', cls.temp.name, str(path)], capture_output=True, text=True)
        if result.returncode:
            cls.temp.cleanup()
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_actual_cell_methods(self):
        for scenario in ['structure', 'toggle', 'select', 'lifecycle', 'removed', 'empty', 'adapter', 'check']:
            with self.subTest(scenario=scenario):
                result = subprocess.run([str(JDK / 'java'), '-Xmx64m', '-cp', self.temp.name, 'ConfigCellHarness', scenario], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
