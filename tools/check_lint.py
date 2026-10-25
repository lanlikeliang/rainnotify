"""Fail on errors and newly introduced lint findings, using a reviewed baseline."""
import argparse
import json
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('report', type=Path)
parser.add_argument('--write-reviewed', action='store_true', help='Maintainer-only baseline update after review')
args = parser.parse_args()
findings = Counter()
errors = []
for issue in ET.parse(args.report).getroot().findall('issue'):
    if issue.get('severity') in {'Error', 'Fatal'}: errors.append(issue.get('id'))
    # Version notices are intentionally excluded from the pinned-baseline gate.
    if issue.get('id') in {'AndroidGradlePluginVersion', 'GradleDependency', 'NewerVersionAvailable'}: continue
    location = issue.find('location')
    path = location.get('file', '').replace('\\', '/') if location is not None else ''
    path = path.split('/android-app/', 1)[-1]
    key = '|'.join([issue.get('id', ''), path, issue.get('errorLine1', '').strip() or issue.get('message', '')])
    findings[key] += 1
baseline = ROOT / 'tools/lint-reviewed.json'
if errors: raise SystemExit('Lint errors: ' + ', '.join(errors))
if args.write_reviewed:
    baseline.write_text(json.dumps(dict(sorted(findings.items())), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Reviewed baseline written: {sum(findings.values())} findings.'); raise SystemExit(0)
known = Counter(json.loads(baseline.read_text(encoding='utf-8')))
new = findings - known
if new: raise SystemExit('New lint findings require review/fix:\n' + '\n'.join(new))
print(f'Lint gate passed; {sum(findings.values())} previously reviewed findings, no new findings.')
