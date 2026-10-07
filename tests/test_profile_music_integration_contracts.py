"""Fast source-contract checks; not a substitute for Android/device tests.
Run: python3 -m unittest discover -s tests -p 'test_profile_music_integration_contracts.py' -v
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java'
HELPERS = JAVA / 'tw/nekomimi/nekogram/helpers'

class ProfileMusicContracts(unittest.TestCase):
    def test_copy_uses_sendable_sharing_directory_not_private_files(self):
        source = (HELPERS / 'ProfileMusicHelper.java').read_text()
        core = (JAVA / 'org/telegram/messenger/SendMessagesHelper.java').read_text()
        self.assertIn('AndroidUtilities.isInternalUri(Uri.fromFile(new File(path)))', core)
        self.assertIn('AndroidUtilities.getSharingDirectory()', source,
                      'Private files/cache can be rejected by the document sender before upload')
        self.assertNotIn('new File(ApplicationLoader.getFilesDirFixed(), "cache")', source)

    def test_upload_matches_its_file_not_any_recent_saved_message(self):
        source = (HELPERS / 'ProfileMusicHelper.java').read_text()
        self.assertTrue('path.equals(message.attachPath)' in source,
                        'Unrelated Saved Messages audio must not be saved as profile music')
        self.assertTrue('private final Runnable timeout = this::fail;' in source,
                        'Upload timeout must show a failure, not silently stop')
        self.assertTrue('attribute.voice' in source,
                        'Voice documents are not profile songs')

    def test_soundcloud_uses_backend_oauth_not_website_cookie_registration(self):
        source = (HELPERS / 'CustomProfileIntegrationOAuth.java').read_text()
        self.assertFalse('startSoundcloud();' in source,
                         'Website registration is not an app OAuth authorization')
        self.assertTrue('path() + "/oauth/start"' in source)
        self.assertTrue('error.contains("bad_request")' in source,
                        'Unsupported SoundCloud backend must surface configuration failure')
        self.assertFalse('SOUNDCLOUD_SCRIPT' in source,
                         'Do not ask users to scrape a website session cookie')

    def test_replaced_oauth_attempt_cannot_publish_or_poll(self):
        source = (HELPERS / 'CustomProfileIntegrationOAuth.java').read_text()
        self.assertTrue('previous.cancel();' in source,
                        'Repeated connect attempts must cancel the previous UI session')
        self.assertTrue('ACTIVE.get(account) == this' in source,
                        'Late callbacks from replaced attempts must be ignored')
        self.assertTrue('UserConfig.selectedAccount == account' in source)
        self.assertTrue('state.matches("[A-Za-z0-9_-]{32,64}")' in source,
                        'Do not poll indefinitely with a missing/invalid backend state')

    def test_invalidated_integration_requests_cannot_restore_stale_card_state(self):
        source = (HELPERS / 'CustomProfileIntegrations.java').read_text()
        self.assertTrue('PENDING.clear(); PENDING_SINCE.clear(); LATEST.clear();' in source,
                        'Disconnect/reconnect must drop pending and tappable-track state')
        callback = source[source.index('SovietGramApiClient.get(account, path,'):]
        self.assertTrue('if (requestedGeneration != cacheGeneration || PENDING.get(cacheKey) != mine) return;' in callback,
                        'A superseded request must not notify listeners or overwrite newer data')

    def test_music_copy_does_not_cross_telegram_identity_changes(self):
        source = (HELPERS / 'ProfileMusicHelper.java').read_text()
        upload = source[source.index('public static void upload'):source.index('private static void send')]
        self.assertTrue('final long owner =' in upload)
        self.assertTrue('getClientUserId() != owner' in upload,
                        'A queued copy must not be sent on a newly logged-in account')
        save = source[source.index('private static void save'):source.index('public static void removeCurrent')]
        self.assertTrue('getClientUserId() != owner' in save,
                        'Save callback must not change another Telegram identity')

    def test_linking_updates_all_blocks_of_the_account_wide_provider(self):
        source = (JAVA / 'tw/nekomimi/nekogram/settings/CustomProfileBlocksActivity.java').read_text()
        linked = source[source.index('private void linkedBlock'):source.index('private void connect')]
        self.assertTrue('targetExists' in linked,
                        'Ignore a connection result when its initiating block no longer exists')
        self.assertTrue('block.service == service' in linked,
                        'Provider credentials are account-wide; existing provider blocks must agree')
        disconnect = source[source.index('private void disconnect'):source.index('private void colorRow')]
        self.assertTrue('CustomProfileIntegrationOAuth.cancelPending(currentAccount)' in disconnect,
                        'Disconnect must invalidate a pending client sign-in callback')

if __name__ == '__main__':
    unittest.main()
