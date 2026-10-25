"""Audit tracked source before upload; never print credential values."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
denied = re.compile(r'(^|/)(?:\.local-tools|\.idea|\.gradle|\.kotlin|build|dist|tmp|secrets|\.secrets|diagnostic-reports|rainnotify-questions[^/]*)(?:/|$)|\.(?:apk|aab|jks|keystore|p12|dpapi|enc|pdf|zip|jsonl|pyc)$|(?:^|/)(?:local\.properties|\.toolchain\.local\.json)$')
secret = re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9_-]{25,})\b|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
errors = []
for name in filter(None, files):
    if denied.search(name): errors.append(f'Forbidden tracked path: {name}')
    path = ROOT / name
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
