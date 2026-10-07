"""Replay actual profile rebuild and base scheduling methods on a bounded JVM.
Android DiffUtil/view boundaries are fakes; no rendering or Android build claim.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_settings_deferred_behavior import method

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings'
JDK = Path(os.environ.get('SETTINGS_TEST_JDK', '/home/user/.hermes/cache/scratch/updater-jdk/usr/lib/jvm/java-21-openjdk-amd64/bin'))


class CustomProfileRebuildBehaviorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rebuild = method((SETTINGS / 'CustomProfileActivity.java').read_text(), 'private void rebuild()')
        # Substitute boundary type names, never the production method logic.
        rebuild = rebuild.replace('tw.nekomimi.nekogram.config.cell.AbstractConfigCell', 'AbstractConfigCell')
        rebuild = rebuild.replace('androidx.recyclerview.widget.DiffUtil', 'DiffUtil')
        idle = method((SETTINGS / 'BaseNekoXSettingsActivity.java').read_text(), 'protected void runWhenListIdle(Runnable update)')
        java = r'''import java.util.*;
class AbstractConfigCell {}
class ConfigCellDivider extends AbstractConfigCell {}
class BlurredRecyclerView {
 boolean busy; int children=1; ArrayDeque<Runnable> queue=new ArrayDeque<>();
 boolean isComputingLayout(){return busy;} int getChildCount(){return children;}
 void post(Runnable r){queue.add(r);} void tick(){int n=queue.size();while(n-->0)queue.remove().run();}
 void drain(){for(int i=0;!queue.isEmpty() && i<20;i++)tick();if(!queue.isEmpty())throw new AssertionError("infinite retry");}
}
class CellGroup {List<AbstractConfigCell> rows=new ArrayList<>();}
class DiffUtil {
 abstract static class Callback {
  public abstract int getOldListSize(); public abstract int getNewListSize();
  public abstract boolean areItemsTheSame(int a,int b); public abstract boolean areContentsTheSame(int a,int b);
 }
 static DiffUtil calculateDiff(Callback c,boolean moves){return new DiffUtil();}
 void dispatchUpdatesTo(CustomProfileHarness.Adapter a){a.event("diff");}
}
public class CustomProfileHarness {
 BlurredRecyclerView listView=new BlurredRecyclerView(); boolean isFinished;
 CellGroup cellGroup=new CellGroup(); Adapter listAdapter=new Adapter();
 int builds; AbstractConfigCell current=new AbstractConfigCell();
 class Adapter {
  List<String> events=new ArrayList<>();
  void event(String e){require(!listView.busy,"notification during layout");require(cellGroup.rows.equals(List.of(current)),"notification saw stale rows");events.add(e);}
  void notifyDataSetChanged(){event("all");} void notifyItemRangeChanged(int p,int n){event("range:"+p+":"+n);}
 }
 void buildRows(){require(!listView.busy,"backing rows mutated during layout");builds++;cellGroup.rows.clear();cellGroup.rows.add(current);}
 IDLE
 REBUILD
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  CustomProfileHarness a=new CustomProfileHarness(); AbstractConfigCell oldRow=new AbstractConfigCell();a.cellGroup.rows.add(oldRow);
  BlurredRecyclerView old=a.listView;old.busy=true;a.rebuild();
  require(a.builds==0 && a.cellGroup.rows.equals(List.of(oldRow)) && a.listAdapter.events.isEmpty(),"whole transaction must defer");
  old.tick();require(a.builds==0 && a.listAdapter.events.isEmpty(),"busy retry must not mutate or notify");
  if(args[0].equals("replacement")){
   a.listView=new BlurredRecyclerView();a.listAdapter=a.new Adapter();old.busy=false;old.drain();
   require(a.builds==0 && a.listAdapter.events.isEmpty(),"old-view rebuild mutated replacement rows or notified new adapter");
   a.rebuild();require(a.builds==1 && a.listAdapter.events.equals(List.of("diff","range:0:1")),"new-view rebuild missing");
  }else if(args[0].equals("finished")){
   a.isFinished=true;old.busy=false;old.drain();
   require(a.builds==0 && a.listAdapter.events.isEmpty(),"finished fragment rebuild mutated or notified");
   a.rebuild();require(a.builds==0,"finished fragment accepted immediate rebuild");
  }else{
   a.current=new AbstractConfigCell();old.busy=false;old.drain();
   require(a.builds==1 && a.cellGroup.rows.equals(List.of(a.current)),"deferred rebuild did not use latest model");
   require(a.listAdapter.events.equals(List.of("diff","range:0:1")),"diff and rebind transaction incomplete");
   a.listAdapter.events.clear();old.children=0;a.rebuild();
   require(a.builds==2 && a.listAdapter.events.equals(List.of("all")),"empty-view refresh changed");
   a.listAdapter=null;a.rebuild();require(a.builds==3,"no-adapter rebuild must still update model");
  }
  System.out.println("PASS "+args[0]);
 }
}
'''.replace('IDLE', idle).replace('REBUILD', rebuild)
        cls.temp = tempfile.TemporaryDirectory(prefix='profile-rebuild-java-', dir='/home/user/.hermes/cache/scratch')
        path = Path(cls.temp.name) / 'CustomProfileHarness.java'
        path.write_text(java)
        result = subprocess.run([str(JDK / 'javac'), '-J-Xmx128m', '-d', cls.temp.name, str(path)], capture_output=True, text=True)
        if result.returncode:
            cls.temp.cleanup()
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def scenario(self, name):
        result = subprocess.run([str(JDK / 'java'), '-Xmx64m', '-cp', self.temp.name, 'CustomProfileHarness', name], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_whole_mutation_diff_and_notification_defer_until_idle(self):
        self.scenario('transaction')

    def test_old_view_rebuild_cannot_mutate_or_notify_replacement(self):
        self.scenario('replacement')

    def test_finished_fragment_rejects_queued_and_immediate_rebuild(self):
        self.scenario('finished')
