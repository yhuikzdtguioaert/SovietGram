"""Lightweight source contracts; Android runtime behavior remains a CI/device check."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram"
HELPER = JAVA / "helpers/CustomProfilePresets.java"


class CustomProfilePresetContracts(unittest.TestCase):
    def test_exactly_three_slots_are_persisted_per_owner_outside_live_config(self):
        self.assertTrue(HELPER.exists(), "Three-slot preset storage is missing")
        source = HELPER.read_text()
        self.assertRegex(source, r"SLOT_COUNT\s*=\s*3\s*;")
        self.assertIn('getSharedPreferences("custom_profile_visual_presets"', source)
        self.assertIn("SovietGramTokenStore.ownId(account)", source)
        self.assertIn("SovietGramAccountScope.isLive(account)", source)
        self.assertIn('"slot_" + owner + "_" + slot', source)
        self.assertIn("slot < 0 || slot >= SLOT_COUNT", source)

    def test_preset_ui_string_resources_exist_with_matching_russian_keys(self):
        import xml.etree.ElementTree as ET
        activity = JAVA / 'settings/CustomProfilePresetsActivity.java'
        source = activity.read_text() if activity.exists() else ''
        res = ROOT / 'TMessagesProj/src/main/res'
        names = set()
        for xml in (res / 'values').glob('*.xml'):
            names.update(element.attrib.get('name') for element in ET.parse(xml).getroot())
        referenced = set(re.findall(r'R\.string\.(\w+)', source))
        self.assertFalse(referenced - names, f'Missing Android resources: {referenced - names}')
        english = res / 'values/custom_profile_presets.xml'
        russian = res / 'values-ru/custom_profile_presets.xml'
        self.assertTrue(english.exists() and russian.exists(), 'Preset localization is missing')
        english_keys = {s.attrib['name'] for s in ET.parse(english).getroot()}
        russian_keys = {s.attrib['name'] for s in ET.parse(russian).getroot()}
        self.assertEqual(english_keys, russian_keys)

    def test_change_appearance_row_reaches_presets_from_profile_editor(self):
        import xml.etree.ElementTree as ET
        source = (JAVA / 'settings/CustomProfileActivity.java').read_text()
        self.assertRegex(source, r'private final AbstractConfigCell presetsRow\s*=\s*'
                         r'new ConfigCellText\("CustomProfilePresets",\s*null,\s*'
                         r'\(\) -> presentFragment\(new CustomProfilePresetsActivity\(\)\)\);')
        build_rows = source.split('private void buildRows() {', 1)[1]
        self.assertRegex(build_rows, r'^\s*cellGroup\.rows\.clear\(\);\s*'
                         r'cellGroup\.appendCell\(presetsRow\);\s*'
                         r'cellGroup\.appendCell\(new ConfigCellDivider\(\)\);')
        resources = ET.parse(ROOT / 'TMessagesProj/src/main/res/values/custom_profile_presets.xml')
        label = resources.getroot().find("string[@name='CustomProfilePresets']")
        self.assertIsNotNone(label)
        self.assertEqual(label.text, 'Change appearance')

    def test_profile_editor_rebuilds_conditional_rows_on_return_from_presets(self):
        source = (JAVA / 'settings/CustomProfileActivity.java').read_text()
        resumes = re.findall(r'@Override\s+public void onResume\(\)\s*\{([^{}]*)\}', source)
        self.assertEqual(len(resumes), 1, 'Editor needs exactly one resume refresh override')
        self.assertRegex(resumes[0], r'super\.onResume\(\);\s*rebuild\(\);')

    def test_visual_change_screen_exposes_three_slots_and_safe_management(self):
        activity = JAVA / 'settings/CustomProfilePresetsActivity.java'
        self.assertTrue(activity.exists(), 'Visual-change UI is missing')
        source = activity.read_text()
        self.assertIn('slot < CustomProfilePresets.SLOT_COUNT', source)
        self.assertIn('row.onLongClick', source)
        self.assertIn('CustomProfilePresets.saveAsync', source)
        self.assertIn('CustomProfilePresets.applyAsync', source)
        self.assertIn('CustomProfilePresets.clearAsync', source)
        self.assertIn('new AlertDialog.Builder', source)
        self.assertIn('if (busy', source)
        self.assertIn('WorkshopHelper.KIND_PROFILE', source)
        self.assertIn('CustomProfilePresetOverwriteInfo', source)


if __name__ == "__main__":
    unittest.main()
