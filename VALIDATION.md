# Wardrobe 1.0.1 validation

The source and installation packages were checked locally. Private execution logs, host paths, account status, wardrobe records and signing secrets are excluded from publication.

## Completed checks

- 57 Python/FastAPI regression tests passed.
- 19 Kotlin matching/synchronization domain tests passed.
- Android release compilation, assembly and lint passed; lint found zero errors and 25 warnings.
- Signed APK verification, ZIP integrity and native-library ZIP alignment passed. Package ID is `com.personal.wardrobe`, Android minimum API 29, target API 36, versionName 1.0.1, versionCode 2.
- 16 live local HTTP checks passed: authentication, single-use pairing, incomplete-photo rejection, upload retries, 601 wardrobe changes with interrupted-response retries, content hashes, AI capability locks, real cutout, backup/restore, token revocation and shutdown.
- A fresh Windows installation created an independent Python environment, installed and checked dependencies, loaded the cutout model, and passed start/status/stop controls with no initial wardrobe or AI connection.
- Original release signing identity, application ID and database schema are preserved. Release private keys and passwords remain outside GitHub.

## Fixes in this release

Shutdown no longer races database closure or starts new feed collection after stop. Simultaneous upload/job retries are serialized. Styling advice and generated previews are rejected when selected garment revisions change. Photo analysis/cutout require saved edits. Image generation requires consent and a verified provider route.

## Remaining acceptance checks

Physical-phone camera permissions, photos/collage appearance, installation/upgrade preservation, responsiveness and cellular Tailscale access require device testing. Live cloud AI, subscription entitlements, generated preview quality and provider quota exhaustion require configured accounts and consent. Some Windows sandbox configurations cannot run the restricted Codex connection; its setup check keeps it locked in that case.

Local tests do not prove these device/account checks. The app budget is a conservative request allowance rather than guaranteed provider billing enforcement. See README.md for setup and usage details.
