"""Removed-feature source contracts, not on-device acceptance."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
MAIN=ROOT/'TMessagesProj/src/main'
H=MAIN/'java/tw/nekomimi/nekogram/helpers'
class RemovedLiveRpc(unittest.TestCase):
    def test_live_classes_removed(self):
        for name in ('SoundCloudDeviceRpc','SoundCloudMediaSessionService','SoundCloudRpcPolicy'):
            self.assertFalse((H/(name+'.java')).exists(),name)
    def test_manifest_listener_removed(self):
        s=(MAIN/'AndroidManifest.xml').read_text()
        self.assertNotIn('SoundCloud',s)
    def test_account_and_logout_no_rpc_hooks(self):
        for p in (H/'SovietGramAccountScope.java',MAIN/'java/org/telegram/messenger/MessagesController.java'):
            self.assertNotIn('SoundCloudDeviceRpc',p.read_text())
    def test_signed_transport_no_live_endpoint_or_captured_credentials(self):
        s=(H/'SovietGramApiClient.java').read_text()
        for text in ('MusicRpcAuth','music-rpc','SoundCloudRpcPolicy'):self.assertNotIn(text,s)
        for text in ('postSigned','deleteSigned','executeWithIdentity'):self.assertIn(text,s)
    def test_editor_only_original_provider_ids(self):
        s=(MAIN/'java/tw/nekomimi/nekogram/settings/CustomProfileBlocksActivity.java').read_text()
        self.assertIn('int[] SERVICES = {0, 1, 2, 3, 4}',s)
        self.assertNotIn('soundcloud',s.lower())
    def test_no_removed_provider_read_or_redirect_path(self):
        s=(H/'CustomProfileIntegrations.java').read_text()
        self.assertNotIn('soundcloud',s.lower())
        self.assertIn('"lastfm", "github", "steam", "yamusic", "spotify"',s)
        self.assertIn('if (!isSupported(service))',s)
    def test_removed_resources_gone_supported_oauth_resources_retained(self):
        for p in (MAIN/'res').glob('values*/strings*.xml'):
            self.assertNotIn('soundcloud',p.read_text().lower(),str(p))
        s=(MAIN/'res/values/strings_sovietgram.xml').read_text()
        self.assertIn('CustomProfileIntegrationPasteToken',s)
        self.assertIn('CustomProfileIntegrationWaiting',s)
        for name in ('CopyScript','EnterManually','LatestTrack','LatestOn','Tracks','Likes'):
            self.assertNotIn('name="CustomProfileIntegration'+name+'"',s)
    def test_consent_storage_only_cleared_never_read_or_enabled(self):
        sources=[p.read_text() for p in (MAIN/'java').rglob('*.java') if 'soundcloud_device_rpc' in p.read_text()]
        self.assertEqual(len(sources),1)
        self.assertIn('.edit().clear().apply()',sources[0])
        self.assertNotIn('publishing_owner',sources[0])
        scope=(H/'SovietGramAccountScope.java').read_text()
        self.assertIn('CustomProfileExtraRows.migrateRemovedIntegrations();',scope)
    def test_supported_cards_and_telegram_music_retained(self):
        card=(MAIN/'java/tw/nekomimi/nekogram/ui/cells/IntegrationCardView.java').read_text()
        self.assertNotIn('soundcloud',card.lower())
        self.assertIn('drawGraph',card);self.assertIn('drawTrack',card)
        music=(H/'ProfileMusicHelper.java').read_text()
        self.assertIn('public static void upload',music)
        self.assertIn('public static void removeCurrent',music)
