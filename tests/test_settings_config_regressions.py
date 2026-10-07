"""Lightweight source-contract checks; no Android SDK or Gradle required.
These are not device/runtime tests. Run: python3 -m unittest discover -s tests -p 'test_settings_config_regressions.py' -v
"""
from pathlib import Path
import re
import unittest

JAVA = Path(__file__).resolve().parents[1] / 'TMessagesProj/src/main/java'
BASE = JAVA / 'tw/nekomimi/nekogram/settings/BaseNekoXSettingsActivity.java'
CHAT = JAVA / 'tw/nekomimi/nekogram/settings/NekoChatSettingsActivity.java'

class SettingsConfigRegressionTests(unittest.TestCase):
    def test_chat_notifications_use_layout_safe_helpers(self):
        source = CHAT.read_text()
        # Only the main settings adapter, not the separate drag-sort dialog adapter.
        self.assertIsNone(re.search(r'listAdapter\.notify(?:DataSetChanged|Item\w*)\(', source),
                          'main chat settings adapter still notifies synchronously during layout callbacks')

    def test_layout_retry_does_not_swallow_unrelated_illegal_state(self):
        source = BASE.read_text()
        method = source.split('protected void notifyAllRowsChanged()', 1)[1].split('@Override', 1)[0]
        self.assertNotIn('catch (IllegalStateException', method)
        self.assertIn('isComputingLayout()', method)
        self.assertIn('rowsRefreshPosted', method, 'reentrant layout callbacks must coalesce queued refreshes')

    def test_recreated_view_does_not_inherit_pending_refresh(self):
        source = BASE.read_text()
        create_view = source.split('public View createView(Context context)', 1)[1].split('protected void onActionBarItemClick', 1)[0]
        self.assertIn('rowsRefreshPosted = false;', create_view)

    def test_missing_translation_already_has_non_null_fallback(self):
        source = (JAVA / 'tw/nekomimi/nekogram/config/cell/ConfigCellTextCheck.java').read_text()
        self.assertIn('resolvedTitle == null ? bindConfig.getKey() : resolvedTitle', source)
        self.assertIn('shown.toString()', source)
        self.assertNotIn('title.toString()', source)

    def test_supported_settings_rows_have_a_factory(self):
        # Characterization: current row declarations are covered. The export omits
        # the actual failing screen/viewType, so this cannot explain the old NPE.
        settings = JAVA / 'tw/nekomimi/nekogram/settings'
        base = BASE.read_text()
        group = (JAVA / 'tw/nekomimi/nekogram/config/CellGroup.java').read_text()
        declared = set(re.findall(r'int (ITEM_TYPE_\w+)\s*=', group))
        cases = set(re.findall(r'case CellGroup\.(ITEM_TYPE_\w+)', base))
        self.assertTrue(declared <= cases, declared - cases)
        for path in settings.glob('*.java'):
            source = path.read_text()
            custom_rows = set(re.findall(r'new ConfigCellCustom\([^;]*?ConfigCellCustom\.(CUSTOM_ITEM_\w+)', source))
            if not custom_rows:
                continue
            factory = source.split('protected View onCreateCustomViewHolder', 1)[-1].split('return view;', 1)[0]
            handled = set(re.findall(r'ConfigCellCustom\.(CUSTOM_ITEM_\w+)', factory))
            self.assertTrue(custom_rows <= handled, (path.name, custom_rows - handled))

    def test_config_save_paths_keep_existing_memory_visibility_and_locks(self):
        for name, start, end in [('SharedConfig', 'public static void saveConfig()', 'public static int getLastLocalId()'),
                                 ('UserConfig', 'public void saveConfig(boolean withFile)', 'public static boolean isValidAccount')]:
            source = (JAVA / ('org/telegram/messenger/' + name + '.java')).read_text()
            save = source.split(start, 1)[1].split(end, 1)[0]
            self.assertIn('synchronized (sync)', save)
            self.assertIn('editor.apply()', save)
            self.assertNotIn('editor.commit()', save)
            self.assertNotIn('postRunnable', save, 'do not defer reads of mutable state to a background queue')

if __name__ == '__main__':
    unittest.main()
