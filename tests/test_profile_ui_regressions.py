"""Lightweight source-contract regressions; no Android SDK or Gradle required.
Run: python3 -m unittest discover -s tests -p 'test_profile_ui_regressions.py' -v
These guard concrete lifecycle/geometry seams, not Android rendering itself.
"""
from pathlib import Path
import re
import unittest
import os
import shutil
import subprocess
import tempfile

ROOT = Path(os.environ.get('PROFILE_UI_SOURCE_ROOT', Path(__file__).resolve().parents[1]))
JAVA = ROOT / 'TMessagesProj/src/main/java'
PROFILE = JAVA / 'org/telegram/ui/ProfileActivity.java'
CARD = JAVA / 'tw/nekomimi/nekogram/ui/cells/IntegrationCardView.java'


def body(source, signature):
    start = source.index('{', source.index(signature))
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start + 1:end - 1]


class ProfileUiRegressions(unittest.TestCase):
    def test_comments_callback_replay_on_jvm(self):
        home = os.environ.get('PROFILE_UI_JAVA_HOME')
        javac = str(Path(home) / 'bin/javac') if home else shutil.which('javac')
        java = str(Path(home) / 'bin/java') if home else shutil.which('java')
        if not javac or not java:
            self.skipTest('JDK required for callback replay; source contracts still run')
        source = PROFILE.read_text()
        methods = 'private void refreshProfileComments() {' + body(source, 'private void refreshProfileComments()') + '}\n'
        if 'private void applyProfileComments(' in source:
            methods += 'private void applyProfileComments(int request, @Nullable CustomProfileExtraRows.Block block) {' + body(source, 'private void applyProfileComments(') + '}\n'
        methods = methods.replace('tw.nekomimi.nekogram.helpers.SovietGramApiClient', 'Api')
        template = (Path(__file__).parent / 'profile_comments_harness.java.txt').read_text()
        with tempfile.TemporaryDirectory(prefix='profile-ui-') as directory:
            path = Path(directory) / 'ProfileCommentsHarness.java'
            path.write_text(template.replace('// PRODUCTION_METHODS', methods))
            compile_result = subprocess.run([javac, '-J-Xmx64m', '-d', directory, str(path)],
                                            capture_output=True, text=True, timeout=45)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            result = subprocess.run([java, '-Xmx32m', '-cp', directory, 'ProfileCommentsHarness'],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('PASS:', result.stdout)

    def test_card_geometry_is_not_overwritten_by_cover_or_progress(self):
        source = CARD.read_text()
        draw = body(source, 'protected void onDraw(Canvas canvas)')
        # RectF is mutable: passing the scratch rect as the card lets the cover/grid
        # change card.right and card.top before later text/legend drawing.
        for argument in re.findall(r'draw(?:Track|Graph)\(canvas, (\w+)\)', draw):
            track = body(source, 'private void drawTrack(')
            graph = body(source, 'private void drawGraph(')
            self.assertNotIn(argument + '.set(', track + graph,
                             'card bounds alias the drawing scratch rectangle')

    def test_id_separator_does_not_cross_custom_background(self):
        source = PROFILE.read_text()
        start = source.index('} else if (position == idDcRow) {', source.index('public void onBindViewHolder'))
        branch = source[start:source.index('} else if', start + 10)]
        self.assertIn('!CustomProfileHelper.hasBackground()', branch,
                      'the extra self-ID separator is visible over transparent profile rows')

    def test_comments_response_defers_whole_adapter_transaction(self):
        source = PROFILE.read_text()
        callback = body(source, 'private void refreshProfileComments()')
        self.assertNotIn('notifyDataSetChanged()', callback,
                         'network/cache callback must not notify during layout')
        self.assertNotIn('profileCommentsBlock =', callback,
                         'row source must not change before deferred transaction')
        self.assertRegex(callback, r'listView\.post\(')
        apply = body(source, 'private void applyProfileComments(')
        self.assertLess(apply.index('isComputingLayout()'), apply.index('profileCommentsBlock ='))
        self.assertLess(apply.index('isFinished'), apply.index('profileCommentsBlock ='))
        self.assertIn('request != profileCommentsRequest', apply)
        self.assertRegex(apply, re.compile(r'isComputingLayout\(\).*?listView\.post\(', re.S))
        self.assertLess(apply.index('profileCommentsBlock ='), apply.index('updateRowsIds()'))
        self.assertLess(apply.index('updateRowsIds()'), apply.index('notifyDataSetChanged()'))


if __name__ == '__main__':
    unittest.main()
