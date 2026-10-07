"""Bounded JVM replay of production row insertion and DiffCallback identities.

Native row allocation is a fixture, not an Android/UI emulation. Workshop-visible
fixtures exercise the insertion seam; this client has no native workshop row.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest
from test_profile_transform_replay import extract_class, JDK

ROOT = Path(os.environ.get('PROFILE_UI_SOURCE_ROOT', Path(__file__).resolve().parents[1]))
SOURCE = ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'


def harness():
    source = SOURCE.read_text()
    update = extract_class(source, 'private void updateRowsIds()')
    resets = update[:update.index('boolean hasMedia = false;')]
    fields = sorted(set(re.findall(r'^        (\w+) = -1;', resets, re.MULTILINE)))
    fields += ['customBlocksStartRow', 'customBlocksEndRow']
    if 'private void insertCustomProfileRows(int workshopRow)' in source:
        # The extracted implementation must actually be wired into production numbering,
        # before bottom padding and the existing native-row permutation.
        assert update.index('insertCustomProfileRows(-1);') < update.index('bottomPaddingRow = rowCount++;')
        assert update.index('insertCustomProfileRows(-1);') < update.index('CustomProfileRows.apply(')
        insert = extract_class(source, 'private void insertCustomProfileRows(int workshopRow)')
        insert += '\n' + extract_class(source, 'private static int shiftCustomProfileRow(')
    else:
        body = update[update.index('        customBlocksStartRow = -1;'):update.index('        if (sharedMediaRow == -1)')]
        insert = 'private void insertCustomProfileRows(int workshopRow) {\n' + body + '\n}'
    identities = extract_class(source, 'public void fillPositions(SparseIntArray sparseIntArray)')
    identities += '\n' + extract_class(source, 'private void put(int id, int position, SparseIntArray sparseIntArray)')
    declarations = '\n'.join('int ' + f + ' = -1;' for f in fields)
    return '''
import java.util.*;
import java.lang.reflect.*;
public class RowOrderHarness {
    // DECLARATIONS
    int rowCount, blockCount;
    static class CustomProfileExtraRows {static int MAX_BLOCKS=64;}
    static class SparseIntArray extends HashMap<Integer,Integer> {
        void put(int p,int id){super.put(p,id);}
    }
    List<Integer> customProfileBlocks(){return Collections.nCopies(blockCount,12);}
    // INSERT
    // IDENTITIES
    static List<Field> fields() throws Exception {
        List<Field> result=new ArrayList<>();
        for(Field f:RowOrderHarness.class.getDeclaredFields())
            if(f.getType()==int.class && !f.getName().equals("rowCount") && !f.getName().equals("blockCount")
                && !f.getName().startsWith("customBlocks")) result.add(f);
        return result;
    }
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    int add(){return rowCount++;}
    static RowOrderHarness fixture(boolean self,boolean username,boolean media) {
        RowOrderHarness h=new RowOrderHarness();
        h.emptyRow=h.add();
        h.infoStartRow=h.rowCount;
        if(self){h.numberRow=h.add();h.setUsernameRow=h.add();h.bioRow=h.add();}
        else {h.phoneRow=h.add();h.userInfoRow=h.add();}
        if(username)h.usernameRow=h.add();
        h.idDcRow=h.add();h.birthdayRow=h.add();h.bizHoursRow=h.add();h.bizLocationRow=h.add();h.noteRow=h.add();
        h.infoEndRow=h.rowCount-1;
        h.infoSectionRow=h.add();h.notificationsRow=h.add();h.settingsTimerRow=h.add();
        h.membersStartRow=h.rowCount;h.rowCount+=2;h.membersEndRow=h.rowCount;h.membersSectionRow=h.add();
        if(media)h.sharedMediaRow=h.add();else {h.sendMessageRow=h.add();h.lastSectionRow=h.add();}
        return h;
    }
    static int fallback(RowOrderHarness h) {
        return Math.max(Math.max(Math.max(h.usernameRow,h.setUsernameRow),Math.max(h.bioRow,h.phoneRow)),
            Math.max(Math.max(h.numberRow,h.birthdayRow),Math.max(Math.max(h.userInfoRow,h.noteRow),
            Math.max(Math.max(h.bizHoursRow,h.bizLocationRow),h.locationRow))))+1;
    }
    static void matrix() throws Exception {
        int cases=0;List<String> failures=new ArrayList<>();
        for(boolean self:new boolean[]{false,true})for(boolean username:new boolean[]{false,true})
        for(boolean workshop:new boolean[]{false,true})for(int integrations=0;integrations<=2;integrations++)
        for(int comments=0;comments<=1;comments++)for(boolean media:new boolean[]{false,true}) {
            cases++;RowOrderHarness h=fixture(self,username,media);
            int workshopRow=-1;
            if(workshop){workshopRow=h.usernameRow>=0?h.usernameRow+1:fallback(h);
                for(Field f:fields())if(f.getInt(h)>=workshopRow)f.setInt(h,f.getInt(h)+1);h.rowCount++;}
            int anchor=workshop?workshopRow+1:h.usernameRow>=0?h.usernameRow+1:fallback(h);
            int originalCount=h.rowCount;Map<Field,Integer> before=new HashMap<>();
            for(Field f:fields())before.put(f,f.getInt(h));
            h.blockCount=integrations+comments;h.insertCustomProfileRows(workshopRow);
            try {
                check(h.rowCount==originalCount+h.blockCount,"rowCount preserved");
                check(h.customBlocksStartRow==(h.blockCount==0?-1:anchor),"custom rows must follow workshop/username/fallback, not shared media: expected "+anchor+" got "+h.customBlocksStartRow);
                check(h.customBlocksEndRow==(h.blockCount==0?-1:anchor+h.blockCount),"exclusive custom end");
                for(Field f:fields()) {
                    int old=before.get(f),expected=old>=anchor?old+h.blockCount:old;
                    check(f.getInt(h)==expected,"native shift/absent sentinel "+f.getName()+" expected "+expected+" got "+f.getInt(h));
                }
                if(media && h.blockCount>0)check(h.customBlocksEndRow<=h.sharedMediaRow,"custom rows reachable before media");
                check(h.membersEndRow-h.membersStartRow==2,"exclusive member range preserved");
            } catch(AssertionError e){failures.add("self="+self+" username="+username+" workshop="+workshop+" integrations="+integrations+" comments="+comments+" media="+media+": "+e.getMessage());}
        }
        check(failures.isEmpty(),"row-order matrix "+failures.size()+"/"+cases+" failures; first: "+(failures.isEmpty()?"":failures.get(0)));
        // Every native field, including bot permissions/headers/dividers and range boundaries.
        for(Field target:fields()) {
            RowOrderHarness h=new RowOrderHarness();h.rowCount=20;h.usernameRow=2;target.setInt(h,8);
            int anchor=h.usernameRow+1;h.blockCount=2;int old=target.getInt(h);
            h.insertCustomProfileRows(-1);check(target.getInt(h)==(old>=anchor?old+2:old),"complete shift coverage: "+target.getName());
        }
        for(String name:new String[]{"setUsernameRow","bioRow","phoneRow","numberRow","birthdayRow",
                "userInfoRow","bizHoursRow","bizLocationRow","locationRow","noteRow"}) {
            RowOrderHarness h=new RowOrderHarness();h.rowCount=20;h.blockCount=1;
            RowOrderHarness.class.getDeclaredField(name).setInt(h,8);h.insertCustomProfileRows(-1);
            check(h.customBlocksStartRow==9,"fallback must include native information field: "+name);
        }
        RowOrderHarness empty=new RowOrderHarness();empty.rowCount=2;empty.blockCount=1;empty.insertCustomProfileRows(-1);
        check(empty.customBlocksStartRow==2 && empty.rowCount==3,"no native information appends at rowCount");
        System.out.println("PASS: "+cases+" row-order cases and every native field");
    }
    static void stableIds() throws Exception {
        RowOrderHarness baseline=fixture(false,true,true);SparseIntArray old=new SparseIntArray();baseline.fillPositions(old);
        int mediaId=old.get(baseline.sharedMediaRow);
        for(int count:new int[]{0,1,2,3,64,65}) {
            RowOrderHarness h=fixture(false,true,true);h.blockCount=count;h.insertCustomProfileRows(-1);
            SparseIntArray ids=new SparseIntArray();h.fillPositions(ids);
            check(ids.get(h.sharedMediaRow)==mediaId,"shared media stable identity changes with custom count="+count);
            for(Field f:fields()) {
                int oldPosition=f.getInt(baseline),newPosition=f.getInt(h);
                if(oldPosition>=0 && old.containsKey(oldPosition) && ids.containsKey(newPosition))
                    check(old.get(oldPosition).equals(ids.get(newPosition)),"native identity changed: "+f.getName());
            }
            if(count>0) {
                Set<Integer> customIds=new HashSet<>();
                for(int p=h.customBlocksStartRow;p<h.customBlocksEndRow;p++) {
                    check(ids.containsKey(p),"custom adapter identity missing");customIds.add(ids.get(p));
                }
                check(customIds.size()==count,"custom adapter identities distinct");
                check(!customIds.contains(mediaId),"custom/native identity collision");
            }
        }
        System.out.println("PASS: stable native/custom identities at 0/1/2/3/64/65 rows");
    }
    public static void main(String[] args) throws Exception {if(args[0].equals("matrix"))matrix();else stableIds();}
}
'''.replace('// DECLARATIONS', declarations).replace('// INSERT', insert).replace('// IDENTITIES', identities)


class ProfileRowOrderReplay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='profile-rows-', dir=os.environ.get('TMPDIR'))
        path = Path(cls.directory.name) / 'RowOrderHarness.java'
        path.write_text(harness())
        result = subprocess.run([str(JDK / 'bin/javac'), '-J-Xmx64m', '-d', cls.directory.name, str(path)], capture_output=True, text=True, timeout=30)
        if result.returncode:
            cls.directory.cleanup()
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def replay(self, mode):
        result = subprocess.run([str(JDK / 'bin/java'), '-Xmx32m', '-cp', self.directory.name, 'RowOrderHarness', mode], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('PASS:', result.stdout)

    def test_workshop_username_fallback_and_all_native_indices(self):
        self.replay('matrix')

    def test_diff_ids_survive_integration_and_comments_count_changes(self):
        self.replay('ids')


if __name__ == '__main__':
    unittest.main()
