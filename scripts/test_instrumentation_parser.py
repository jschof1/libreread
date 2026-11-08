import pathlib
import subprocess
import sys
import tempfile
import unittest

CHECKER = pathlib.Path(__file__).with_name('check-instrumentation.py')

class InstrumentationProtocolTests(unittest.TestCase):
    def run_fixture(self, live=True, missing=False, failed=False, duplicate=False, unexpected_skip=False, missing_end=False):
        lines = []
        count = 21 if missing else 22
        for i in range(count):
            cls = 'com.github.libretube.reader.FixtureTest'
            name = 'case' + str(i)
            code = 0
            if i == 21 and not live:
                cls = 'com.github.libretube.reader.LiveArticleInstrumentedTest'
                name = 'realVideoBecomesASavedArticle'
                code = -4
            if i == 0 and failed:
                code = -2
            if i == 0 and unexpected_skip:
                code = -3
            if i == 1 and duplicate:
                name = 'case0'
            lines += ['INSTRUMENTATION_STATUS: class=' + cls,
                      'INSTRUMENTATION_STATUS: test=' + name,
                      'INSTRUMENTATION_STATUS: numtests=22',
                      'INSTRUMENTATION_STATUS_CODE: ' + str(code)]
        if not missing_end:
            lines.append('INSTRUMENTATION_CODE: -1')
        with tempfile.TemporaryDirectory() as folder:
            log = pathlib.Path(folder) / 'instrumentation.txt'
            log.write_text('\n'.join(lines))
            return subprocess.run([sys.executable, str(CHECKER), str(log), str(live).lower()], capture_output=True).returncode

    def test_complete_live_suite_passes(self):
        self.assertEqual(0, self.run_fixture())

    def test_only_opt_in_live_case_may_skip_publicly(self):
        self.assertEqual(0, self.run_fixture(live=False))
        self.assertNotEqual(0, self.run_fixture(unexpected_skip=True))

    def test_crashed_process_is_rejected(self):
        self.assertNotEqual(0, self.run_fixture(missing_end=True))

    def test_live_suite_cannot_skip(self):
        self.assertNotEqual(0, self.run_fixture(live=True, unexpected_skip=True))

    def test_failures_are_rejected(self):
        self.assertNotEqual(0, self.run_fixture(failed=True))

    def test_missing_results_are_rejected(self):
        self.assertNotEqual(0, self.run_fixture(missing=True))

    def test_duplicate_results_are_rejected(self):
        self.assertNotEqual(0, self.run_fixture(duplicate=True))

if __name__ == '__main__':
    unittest.main()
