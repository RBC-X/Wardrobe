# Wardrobe 1.0.1 validation

> The branch adds source changes after this delivery; see “Changes after 1.0.1” at the end for the checks run on them.

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

## Changes after 1.0.1 (source branch; debug test APK built, no release APK)

These checks cover the in-app AI key setup, phone-side analysis and styling, automatic framing, phone product import and the ChatGPT-plan connection. They do not replace the 1.0.1 release checks above. A debug test APK was assembled; no new release APK was built.

- 67 Python/FastAPI tests passed; 1 skipped: the Windows DPAPI round-trip, which cannot run on this Linux host. The phone key flow is covered with a stand-in protector, and the Windows path still needs one run on a Windows machine.
- 20 live local HTTP checks passed, including the real bundled U²-Net model: cutout 0.769 s, background-free detection 0.767 s, automatic framing 1.087 s, detected colour `#3d6799` for the synthetic `#3d6699` garment, trimmed RGBA result 253×248, plus the existing pairing, sync, retry, backup/restore and shutdown checks.
- Automatic framing returns crop fractions instead of the full frame: left 7.6%, top 9.2%, right 7.3%, bottom 7.3% on the 320×320 synthetic garment, so the background is excluded. Three contract tests cover the mask-only box, the 40%-per-side clamp for a garment at the edge, and the pairing requirement.
- New contract tests cover: colours read only inside the mask, detection job ownership, cross-device job isolation (second paired phone gets 404 and no job in sync), phone key save with no secret in responses/database, PC-only agent providers rejected, connection checks rate-limited, and AI analysis sending the saved background-free cutout.
- Kotlin was checked with a downloaded JDK 17 and the Kotlin 2.1.21 compiler: the `domain` module and the new phone `WeatherClient` compile cleanly, and **50 domain tests pass**, including six for weather bands/summaries and reply parsing, eight for background-free segmentation (fenced JSON, provider errors, prose refusal), four for product-page parsing (JSON-LD, OpenGraph, entity decoding, relative HTTPS images, safe fallback), eleven ChatGPT protocol tests (authorization parameters, returning sign-in, callback decoding, code/refresh forms, token parsing, model/vision parsing, SSE deltas, completion fallback, usage-limit and incomplete-stream errors), and a real loopback-socket browser callback test. The ChatGPT tests use fake JSON and a local socket, not an OpenAI account. `Secrets.kt`, `PhoneAi.kt`, `ChatGpt.kt` and `ProductImport.kt` also compile against API-shaped stubs for the Android classes they use; that verifies Kotlin syntax and types, not the real Android SDK. One genuine defect was caught this way: `List<ProviderCapability>=models` parsed as the `>=` operator.
- On-device detection was exercised for real on this Linux host: the bundled `u2netp.onnx` ran through the new Kotlin path with onnxruntime 1.20.0 on a synthetic garment, and the result matched `server/wardrobe/detect.py` on the identical photo exactly — bounding box 54,44–266,281, strong-mask pixels 39905, aspect 1.119, fill 0.809, flare 0.932, shoulder 0.939, area share 0.3897, category `top` at 0.58 confidence, colour `#3d6699`, shape `straight silhouette`, fit `relaxed`. Only Bitmap decode/encode is Android-specific; the model is read from the app's assets.
- `detect.py` divided the shoulder width by the widest row twice, pinning it near zero and making that signal useless in classification and shape. It is now a single division; 67 Python tests and 20 live checks still pass, and the detection above reflects the corrected value.
- The full app module was then compiled for real with JDK 17.0.21, Android SDK 36 and build-tools 36: `:app:assembleDebug` succeeded and produced a **131 MB debug-signed** `app-debug.apk` (package `com.personal.wardrobe`, minSdk 29, targetSdk 36). The APK passed `apksigner verify`, contains `assets/u2netp.onnx` and the ONNX Runtime native libraries, and is copied to the workspace as `Wardrobe-debug.apk`. This is a test APK, not a release-signed artifact. The model has run only on a desktop JVM, never inside Android, so its speed, memory use and the APK size increase on a phone are unmeasured. The model picker and Compose card compile but were not visually exercised. Live Open-Meteo responses, live Gemini/OpenAI calls, live Sign in with ChatGPT (including the loopback callback and ID-token/JWKS validation), Android keystore storage on a real device, real wardrobe photographs and the full no-PC flow all still need a phone. Lint was not run.
- Detection quality on real photographs, low-confidence thresholds and the review sheet layout are not verified; they need real devices and real wardrobe photos. Phone product import has not been run against live retailer pages; its parser and size/HTTPS/redirect/private-host guards are covered only by unit tests and stub compilation. Sign in with ChatGPT is implemented against OpenAI's published OAuth/Responses API contract, but no OpenAI account was available here to complete registration, token exchange, refresh, revocation or inference. Version name/code were not bumped.
