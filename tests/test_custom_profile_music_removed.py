"""Source contracts for removal ONLY from the custom editor; not device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java'


class CustomEditorMusicRemoval(unittest.TestCase):
    def test_custom_editor_has_no_profile_music_navigation_or_picker(self):
        source = (JAVA / 'tw/nekomimi/nekogram/settings/CustomProfileActivity.java').read_text()
        for obsolete in ('profileMusicRow', 'showMusicMenu', 'REQUEST_PROFILE_MUSIC',
                         'ProfileMusicHelper', '"CustomProfileMusic"'):
            self.assertNotIn(obsolete, source,
                             f'Custom profile editor still exposes music: {obsolete}')
        for preserved in ('cellGroup.appendCell(presetsRow)', 'cellGroup.appendCell(headerFrame)',
                          'REQUEST_BANNER', 'REQUEST_BACKGROUND', 'REQUEST_NAME_FONT',
                          'REQUEST_THOUGHT_FONT', 'CustomProfileBlocksActivity'):
            self.assertIn(preserved, source)

    def test_standard_telegram_profile_music_is_preserved(self):
        profile = (JAVA / 'org/telegram/ui/ProfileActivity.java').read_text()
        player = (JAVA / 'org/telegram/ui/Components/AudioPlayerAlert.java').read_text()
        self.assertIn('musicView.setMusicDocument(userInfo.saved_music)', profile)
        self.assertIn('TLRPC.TL_account_saveMusic', player)
        self.assertTrue((JAVA / 'tw/nekomimi/nekogram/helpers/ProfileMusicHelper.java').is_file())

    def test_music_integrations_and_video_sound_controls_are_preserved(self):
        blocks = (JAVA / 'tw/nekomimi/nekogram/settings/CustomProfileBlocksActivity.java').read_text()
        self.assertIn('private void connect', blocks)
        self.assertIn('private void disconnect', blocks)
        source = (JAVA / 'tw/nekomimi/nekogram/settings/CustomProfileActivity.java').read_text()
        self.assertIn('cellGroup.appendCell(bannerSoundRow)', source)
        self.assertIn('cellGroup.appendCell(backgroundSoundRow)', source)
        self.assertTrue((JAVA / 'tw/nekomimi/nekogram/ui/cells/IntegrationCardView.java').is_file())


if __name__ == '__main__':
    unittest.main()
