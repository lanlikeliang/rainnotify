# RainNotify development

Native Kotlin Android app, API 36 only. Read `docs/DEVELOPMENT.md` before changing tooling.

- Standard Windows check: `./tools/build_android.ps1 -Lint -UnitTests`.
- Cross-platform: from `android-app`, `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`.
- Run `python -m unittest discover -s tests -v`, `python tools/check_repository.py`, and the lint gate before committing.
- Keep protocol and submission changes separate from UI/toolchain changes. Never retry an ambiguous submission automatically.
- Tests use synthetic data and localhost HTTP. Real accounts/API keys stay on the phone; tests do not enter classrooms or submit real answers.
- Do not commit raw classroom exports, PDFs, logs, screenshots, local tool config or signing keys. Diagnostics must exclude question/answer text and credentials.
- Product UI is API-only: do not reintroduce AI-app accessibility automation or history/rehearsal screens. Synthetic regression belongs in tests.
- Debug and release are different packages. Release diagnostics come from user-initiated in-app export; `run-as` is debug-only.
- No test execution is evidence of actual classroom acceptance. Record verified and unverified behavior separately.
