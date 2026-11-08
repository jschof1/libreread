#!/usr/bin/env python3
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

path = pathlib.Path(sys.argv[1])
live = sys.argv[2] == 'true'
text = path.read_text(errors='replace')
bundle = {}
cases = []
reported = set()
unattributed = []
for line in text.splitlines():
    if line.startswith('INSTRUMENTATION_STATUS: '):
        key, _, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
        bundle[key] = value
        if key == 'numtests':
            reported.add(int(value))
    elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
        code = int(line.split(':', 1)[1])
        if code <= 0 and 'test' in bundle:
            cases.append({'class': bundle.get('class', ''), 'test': bundle['test'], 'code': code})
        elif code < 0:
            unattributed.append(code)
        bundle = {}

failures = [c for c in cases if c['code'] not in (0, -3, -4)]
skipped = [c for c in cases if c['code'] in (-3, -4)]
expected_skipped = [] if live else [('com.github.libretube.reader.LiveArticleInstrumentedTest', 'realVideoBecomesASavedArticle')]
valid = (
    len(cases) >= 22
    and reported == {len(cases)}
    and len({(c['class'], c['test']) for c in cases}) == len(cases)
    and not failures and not unattributed
    and sorted((c['class'], c['test']) for c in skipped) == expected_skipped
    and 'INSTRUMENTATION_CODE: -1' in text
)
summary = {'tests': len(cases), 'failures': len(failures), 'skipped': len(skipped), 'liveEnabled': live, 'completeAndValid': valid, 'cases': cases}
path.with_suffix('.json').write_text(json.dumps(summary, indent=2) + '\n')
suite = ET.Element('testsuite', name='LibreRead release', tests=str(len(cases)), failures=str(len(failures)), errors='0', skipped=str(len(skipped)))
for case in cases:
    node = ET.SubElement(suite, 'testcase', classname=case['class'], name=case['test'])
    if case['code'] in (-3, -4):
        ET.SubElement(node, 'skipped')
    elif case['code'] != 0:
        ET.SubElement(node, 'failure', message='See instrumentation.txt')
ET.ElementTree(suite).write(path.with_name('TEST-release.xml'), encoding='utf-8', xml_declaration=True)
print(json.dumps({k: v for k, v in summary.items() if k != 'cases'}))
if not valid:
    raise SystemExit('Incomplete, failed, unexpectedly skipped, or duplicate instrumentation results')
