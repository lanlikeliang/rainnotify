"""Audit tracked source before upload; never print credential values."""
import re
import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
denied = re.compile(r'(^|/)(?:\.local-tools|\.idea|\.gradle|\.kotlin|build|dist|tmp|secrets|\.secrets|diagnostic-reports|rainnotify-questions[^/]*)(?:/|$)|\.(?:apk|aab|jks|keystore|p12|dpapi|enc|pdf|zip|jsonl|pyc)$|(?:^|/)(?:local\.properties|\.toolchain\.local\.json)$')
secret = re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9_-]{25,})\b|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
# Only this reviewed, public-derived launcher asset may be binary. See docs/ICON_SOURCE.md.
reviewed_assets = {"android-app/app/src/main/res/drawable-nodpi/ic_rainnotify_art.png": "5ddcb7497b62ff7719e749e8a59d5ca7ba30ded29b535b699e548bc579c4735a"}
errors = []
for name in filter(None, files):
    if denied.search(name): errors.append(f'Forbidden tracked path: {name}')
    path = ROOT / name
    if name in reviewed_assets:
        if hashlib.sha256(path.read_bytes()).hexdigest() != reviewed_assets[name]:
            errors.append(f'Reviewed asset checksum changed: {name}')
        continue
    if path.suffix == '.jar':
        if name != 'android-app/gradle/wrapper/gradle-wrapper.jar': errors.append(f'Unexpected binary: {name}')
        continue
    if not path.is_file(): continue
    try: content = path.read_text(encoding='utf-8-sig')
    except UnicodeError:
        errors.append(f'Unexpected non-text file: {name}'); continue
    if secret.search(content): errors.append(f'Potential credential in: {name}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'Repository audit passed: {len(list(filter(None, files)))} tracked files.')
