# GeePlay release procedure

The product branch stays `geely/main`. `master` tracks the merged upstream baseline; never force-push either branch.

`geely-release.yml` runs the base suite and the Geely suite on product pushes and pull requests. A tag must point to a commit contained in `geely/main`. The publish job runs only after checks pass.

Configure protected GitHub environments `geely-release` and `geely-diagnostic` with required reviewers and these secrets:

- `ANDROID_KEYSTORE_BASE64`: permanent keystore (separate identities for production and diagnostic packages).
- `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- `ANDROID_CERTIFICATE_SHA256`: expected signing certificate SHA-256, checked against the finished APK.

The production environment also requires `CARPLAY_IDENTITY_PK8_BASE64`, `CARPLAY_CERTIFICATE_P7B_BASE64` and environment variable `CARPLAY_DISTRIBUTION_APPROVED=true`. Setting the variable records the owner's permission decision; the workflow cannot establish legal rights itself. Do not configure it without rights to use and distribute these assets.

Tags for this version:

- `v0.2.21-geely.1`: full release; signed `:geely:assembleRelease`, authorized assets required.
- `v0.2.21-geely.1-diagnostic.1`: diagnostic pre-release; debug APK without authentication, signed by the persistent diagnostic key.

Do not tag until the built commit and checks are verified. Increment versionCode and versionName for subsequent versions and update release notes. Compare a previously distributed APK's certificate fingerprint before claiming update compatibility. Preserve keystores outside Git and back them up securely.

For local authorized builds, `DIPLAY_AUTH_ASSETS_DIR` is the directory containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Both must be nonempty. `rejectBundledCredentials` remains mandatory and rejects other credential files. Without this variable, build a diagnostic APK with `:geely:assembleDebug`.

Full-release signing uses `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. To sign a diagnostic debug build with a permanent diagnostic key, set the same variables and `GEEPLAY_DIAGNOSTIC_SIGNING=true`.

Publication includes only the verified APK, `git archive` source ZIP, `verification.json` and `SHA256SUMS.txt`. Secrets are restored under the runner's temporary directory and removed even on failure. The source archive never includes local assets or a keystore. Retain public test reports separately; do not upload private runner directories.
