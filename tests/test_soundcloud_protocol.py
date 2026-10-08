"""Execute the actual OAuth start method in a bounded JVM seam (not an Android build)."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/CustomProfileIntegrationOAuth.java'


def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class SoundCloudProtocol(unittest.TestCase):
    def test_removed_provider_rejected_other_providers_still_work(self):
        start = method(SOURCE.read_text(), 'private void start()')
        code = r'''
import java.util.*;
public class OAuthStartSeam {
 static class JSONObject {
  Map<String,Object> values = new HashMap<>();
  void put(String k, Object v) { values.put(k,v); }
  String optString(String k) { return optString(k, ""); }
  String optString(String k, String fallback) { return (String)values.getOrDefault(k,fallback); }
  boolean optBoolean(String k) { return Boolean.TRUE.equals(values.get(k)); }
 }
 interface Callback { void call(JSONObject response,String error); }
 static class SovietGramApiClient {
  static JSONObject response;
  static int deletes, posts; static String deleted;
  static void postSigned(int account,String path,JSONObject body,Callback cb) { posts++; cb.call(response,null); }
  static void deleteSigned(int account,String path,Callback cb) { deletes++; deleted=path; }
 }
 static class ApplicationLoader {
  static ApplicationLoader applicationContext = new ApplicationLoader();
  String getPackageName() { return "sovietgram.com"; }
 }
 static class R { static class string {
  static int CustomProfileIntegrationNotConfigured=1,CustomProfileIntegrationUnavailable=2,CustomProfileIntegrationNoBrowser=3,CustomProfileIntegrationSoundcloudUnavailable=4;
 }}
 static class AndroidUtilities { static int polls; static void runOnUIThread(Runnable r,long delay) { polls++; } }
 static String getString(int id) { return Integer.toString(id); }
 static final long POLL_MS=2000;
 int account=0, service, generation, tokenCalls, failures, waiting, browsers;
 String state;
 long startedAt;
 Runnable poll=()->{};
 boolean live=true;
 boolean alive() { return live; }
 String path() { return "/v1/integration-accounts/test"; }
 void fail(String problem) { failures++; }
 boolean openBrowser(String url) { browsers++; return true; }
 void askToken(String problem) { tokenCalls++; }
 void showWaiting() { waiting++; }
 START_METHOD
 public static void main(String[] args) {
  for (int service : new int[]{3,5,6,7,4}) {
   OAuthStartSeam test = new OAuthStartSeam(); test.service=service;
   JSONObject reply = new JSONObject(); reply.put("url", "https://example.invalid/authorize");
   reply.put("manual", service!=4);
   if (service==4) reply.put("state", "a".repeat(43));
   SovietGramApiClient.response=reply; AndroidUtilities.polls=0; SovietGramApiClient.posts=0; test.start();
   if (service!=3 && service!=4) {
    if (test.failures!=1 || test.browsers!=0 || test.tokenCalls!=0 || SovietGramApiClient.posts!=0 || AndroidUtilities.polls!=0)
     throw new AssertionError("Removed providers must fail without transport, browser or token prompt");
    continue;
   }
   if (test.failures!=0) throw new AssertionError("service "+service+" rejected its supported contract");
   if (service!=4 && (test.tokenCalls!=1 || AndroidUtilities.polls!=0)) throw new AssertionError("manual service must preview a token, not poll a missing state");
   if (service==4 && (test.waiting!=1 || AndroidUtilities.polls!=1)) throw new AssertionError("Spotify must poll OAuth state");
  }
  OAuthStartSeam abandoned=new OAuthStartSeam(); abandoned.service=4; abandoned.live=false;
  SovietGramApiClient.deletes=0; abandoned.start();
  if (SovietGramApiClient.deletes!=1 || !("/v1/integration-accounts/oauth/"+"a".repeat(43)).equals(SovietGramApiClient.deleted))
   throw new AssertionError("state returned after cancellation must be revoked, not silently orphaned");
  System.out.println("Removed providers blocked; Yandex manual + Spotify browser OAuth passed");
 }
}
'''.replace('START_METHOD', start).replace('android.os.SystemClock.elapsedRealtime()', '1000L')
        scratch = Path(os.environ.get('TMPDIR', '/home/user/.hermes/cache/scratch'))
        with tempfile.TemporaryDirectory(prefix='oauth-seam-', dir=scratch) as folder:
            java = Path(folder) / 'OAuthStartSeam.java'
            java.write_text(code)
            subprocess.run(['javac', '-J-Xmx96m', str(java)], check=True, capture_output=True, text=True)
            result = subprocess.run(['java', '-Xmx64m', '-cp', folder, 'OAuthStartSeam'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_cancel_revokes_server_state_without_disconnecting_saved_account(self):
        source = SOURCE.read_text()
        cancel = method(source, 'private void cancel()')
        # Execute the actual cancel method, with only its Android/API dependencies stubbed.
        code = r'''
public class OAuthCancelSeam {
 static class SovietGramApiClient {
  interface Callback { void call(Object body, String error); }
  static int calls; static String target;
  static void deleteSigned(int account, String path, Callback cb) { calls++; target=path; }
 }
 static class AndroidUtilities { static void cancelRunOnUIThread(Runnable r) {} }
 static final java.util.Map<Integer,OAuthCancelSeam> ACTIVE = new java.util.HashMap<>();
 int account=0, generation; boolean finished; String state;
 Runnable poll=()->{}, watchClipboard=()->{};
 void dismiss() {}
 CANCEL_METHOD
 public static void main(String[] args) {
  OAuthCancelSeam attempt=new OAuthCancelSeam(); attempt.state="a".repeat(43); attempt.cancel();
  if (SovietGramApiClient.calls!=1 || !("/v1/integration-accounts/oauth/"+"a".repeat(43)).equals(SovietGramApiClient.target))
   throw new AssertionError("cancel must revoke its exact server state, never the saved provider account");
  attempt.cancel();
  if (SovietGramApiClient.calls!=1) throw new AssertionError("cancellation must be idempotent");
  OAuthCancelSeam manual=new OAuthCancelSeam(); manual.state=""; manual.cancel();
  if (SovietGramApiClient.calls!=1) throw new AssertionError("manual sign-in has no state to revoke");
 }
}
'''.replace('CANCEL_METHOD', cancel)
        scratch = Path(os.environ.get('TMPDIR', '/home/user/.hermes/cache/scratch'))
        with tempfile.TemporaryDirectory(prefix='oauth-cancel-seam-', dir=scratch) as folder:
            java = Path(folder) / 'OAuthCancelSeam.java'
            java.write_text(code)
            subprocess.run(['javac', '-J-Xmx96m', str(java)], check=True, capture_output=True, text=True)
            result = subprocess.run(['java', '-Xmx64m', '-cp', folder, 'OAuthCancelSeam'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_manual_token_preserves_backend_accepted_padding(self):
        source = SOURCE.read_text()
        extract = method(source, 'private static String extractToken(String pasted)')
        pattern = next(line.strip() for line in source.splitlines() if 'Pattern PASTED_TOKEN =' in line)
        code = ('import java.util.regex.*; public class TokenSeam {\n' + pattern + '\n' + extract + r'''
 public static void main(String[] args) {
  String token="session-0123456789abcdef==";
  if (!token.equals(extractToken(token))) throw new AssertionError("raw padded token rejected");
  if (!token.equals(extractToken("https://example.invalid/#access_token="+token+"&expires_in=60")))
   throw new AssertionError("fragment token padding truncated");
 }
}
''')
        scratch = Path(os.environ.get('TMPDIR', '/home/user/.hermes/cache/scratch'))
        with tempfile.TemporaryDirectory(prefix='oauth-token-seam-', dir=scratch) as folder:
            java = Path(folder) / 'TokenSeam.java'
            java.write_text(code)
            subprocess.run(['javac', '-J-Xmx96m', str(java)], check=True, capture_output=True, text=True)
            result = subprocess.run(['java', '-Xmx64m', '-cp', folder, 'TokenSeam'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_failed_attempt_uses_the_same_server_state_cancellation(self):
        self.assertIn('cancel();', method(SOURCE.read_text(), 'private void fail(String message)'))

    def test_only_supported_oauth_entries_without_cookie_scraping(self):
        source=SOURCE.read_text()
        self.assertNotIn('soundcloud',source.lower())
        self.assertIn('if (service != 3 && service != 4)',method(source,'private void start()'))
        self.assertNotIn('CookieManager',source)

if __name__ == '__main__':
    unittest.main()
