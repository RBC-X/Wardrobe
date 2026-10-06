# Wardrobe — your private Android wardrobe

Current delivery: **1.0.1**, signed Android versionCode **2**. The release preserves the existing signing identity and database format.

Wardrobe is a personal Android 10+ app with a Windows companion. Matching, manual editing, photo color extraction, preferences, and actual-photo outfit boards work offline. AI features start locked. The PC must remain awake and online for refresh, product retrieval, cutouts, and AI.

## Install on Android

1. Copy `Wardrobe.apk` to your phone and open it. Allow installation from the file/browser app only when Android asks. Revoke that installation permission afterward if desired.
2. Launch **Wardrobe**. It starts empty: Today → Add your first item.
3. Add at least a top, bottom (or a one-piece garment), and shoes. Camera access is optional; use Import photo or manual fields if denied.
4. Crop each image, correct its colors/category, and save. Additional photos can show labels or different angles. Photos are copied into private app storage.
5. Today offers Timeless, Balanced (default), or Trend-focused modes, occasion/weather/season controls, and up to five combinations. Build around an item, swap pieces, save, give feedback, or export an outfit board.

Brand resemblance is not identification. Appearance-derived texture is not verified fabric composition. Only mark brand/composition confirmed after checking a label or exact product.

## Windows installation

1. Extract `Wardrobe-Windows.zip` into a permanent folder owned by your Windows user, for example `%USERPROFILE%\Apps\Wardrobe`. Do not run it inside the ZIP.
2. Install **Python 3.11, 64-bit**, including the Python launcher, from https://www.python.org/downloads/windows/ if missing.
3. Double-click **Setup.cmd**. It downloads dependencies into this folder's `.venv`; no administrator access is required. A model-load check runs afterward.
4. Double-click **Open Dashboard.cmd**. It starts the companion and opens an authenticated local dashboard.
5. Use **Start Server.cmd** / **Stop Server.cmd** for controls. **Enable Startup.cmd** asks before adding a current-user sign-in entry; **Disable Startup.cmd** removes only that entry. Startup is off by default. Keep the extracted folder in place once enabled.

The server listens only on `127.0.0.1:8765`. Local data and the runtime dashboard token are in `%LOCALAPPDATA%\WardrobeServer` (or `WARDROBE_DATA` if explicitly set). Do not share the initial dashboard URL or that data directory. Logs are in `server.log`; application access logging is disabled. Do not delete this directory to update the server.

## Private phone-to-PC connection

1. Install Tailscale on Windows and Android from https://tailscale.com/download and sign in to the same personal network. Review https://tailscale.com/docs/features/tailscale-serve.
2. With Wardrobe running, use a terminal: `tailscale serve --bg http://127.0.0.1:8765`. Enable tailnet HTTPS if Tailscale directs you to do so. **Do not enable Tailscale Funnel, port-forward the server, or bind it to all interfaces.**
3. Paste the resulting `https://PC.network.ts.net` address into the dashboard and create a pairing QR.
4. Phone → Connect → Scan pairing QR, or paste the complete manual pairing JSON. A code expires in five minutes and works once; paired devices get revocable tokens.
5. Tap Refresh database. The app drains queued changes in bounded batches, checks photo/rule hashes, and applies each accepted batch transactionally. A failed transfer leaves the last usable data intact; retry Refresh or allow its queued WorkManager retry.
6. To prove cellular access, turn Wi-Fi off on the phone, keep Android Tailscale connected, and Refresh again. This requires your actual devices and is not proved by localhost tests.

Disconnecting the phone preserves local data. Revoke its token from the PC dashboard to invalidate it. Authorization errors need re-pairing; an unresolved `.ts.net` address suggests a Tailscale/DNS problem. PC-unavailable errors can also mean the PC is asleep, the server stopped, or the network is down; the app cannot reliably distinguish every underlying cause.

## AI connections and consent

Use the PC dashboard to enable and check only the connections you want:

- **Codex:** install the current Codex CLI and authenticate with your ChatGPT account. Wardrobe uses a clean temporary Codex home, stdio app-server, a read-only job root, disabled command tools/hooks/plugins/MCP/browser integrations, and selected job images. It does not expose the agent protocol to the phone. The setup check requires the restricted filesystem sandbox to start; Some Windows configurations reject these restricted filesystem permissions; Codex stays locked when its isolation check fails. There is deliberately no unsandboxed fallback.
- **Claude Code:** install the current CLI and authenticate with a Claude subscription. Wardrobe uses structured print mode with safe/restricted mode, no tools, no MCP, no hooks/skills, no persisted session, and selected images. Older CLIs that lack these isolation flags fail rather than falling back to general execution.
- **Gemini API / OpenAI API:** enter your own API key in the dashboard. Keys are protected with Windows DPAPI and never sent to the phone or included in exports. Do not paste keys into source files or scripts.

Free-only is the default. OpenAI paid requests stay locked until a positive app budget is entered and free-only is disabled. Free-only Gemini requires explicit confirmation that billing is disabled on the API project; provider quota availability still applies. PC subscription connections use their account allowances. Background trend analysis is separately opt-in.

The app conservatively reserves **$1 per submitted paid request** against its app budget. This is a request allowance, **not a hard provider billing cap or exact cost accounting**. A request can cost more; configure provider-side spend limits too. Failed or uncertain submitted requests retain their reservation. A successful login/models check is not proof of every runtime entitlement: unsupported/expired/quota-exhausted requests report provider failure. Vision additionally requires photo sharing; generated images additionally require a successful real image probe. Claude has no image generation/web-search route in this implementation. Codex web search is intentionally disabled. Cloud providers are never automatically configured from ambient environment keys.

Review clothing analysis before applying it. Manually corrected fields are protected, stale revisions are rejected, and possibleBrand remains unconfirmed. Try-on requires explicit photo consent and a supported image route; choose your own reference under Your style or use a generic adult model. Every generated preview is approximate, not a physical-fit guarantee.

## Products, trends, and sources

Paste an exact public HTTPS product URL into Edit → Find details on PC. Select the exact item/color variant and image in the review, then explicitly confirm. Blocked, inaccessible, or unstructured pages may require manual editing. Server fetching pins public IPs and validates every redirect; local/private/Tailscale destinations are blocked.

Discover suggests nonduplicate wardrobe gaps, matching owned items, and example outfits. Connected search shows only product pages actually retrieved, available price/size information, source URLs and checked timestamps. Product variants and remote images are unconfirmed until reviewed; prices/stock are never invented.

The PC attempts Vogue, GQ, and Fashionista feeds at startup and every six hours. Site failures are shown, not silently treated as new trends. Feed collection can be switched off. AI trend interpretation requires a separate opt-in; evidence, confidence, observation time, season and at-most-90-day influence are stored separately from protected core rules. No connection means source updates only.

## Backup and restore

- Phone → Connect → Export backup saves records, photos, preferences, outfits and styling cache; it excludes connection credentials.
- Phone Restore accepts the **phone format** (`wardrobe-phone.json`). Matching IDs are restored at newer revisions; unrelated current records remain. Your PC connection is unchanged. Refresh afterward to upload restored changes.
- PC dashboard Download backup saves the **PC format** (`wardrobe.json`) and media, excluding device/API/dashboard secrets. PC Restore uses that format, then the phone retrieves restored records through Refresh.
- These formats are intentionally distinct; do not restore a phone ZIP on the PC or vice versa. Keep backups private. Backup filenames may be identical; label the storage location.

## Upgrades and signing

Install a newer APK over the existing installation **without uninstalling**; the application ID and signing certificate must remain the same and versionCode must increase. Uninstalling clears private phone data. Export a backup before an upgrade. SQLite/Room schema is currently version 1 with no destructive migration.

This delivery uses a dedicated personal release key, not the Android debug key. An earlier debug APK has a different signing identity and cannot be upgraded in-place to this release; export data before changing identity. The maintainer keeps release keys and passwords offline. They are never included in this repository or GitHub downloads. Keep your own signing material private when building a fork. This artifact is sensitive by design, unlike wardrobe backup ZIPs.

The 1.0.1 APK can replace the previous signed 1.0.0 APK without uninstalling. The database schema and application ID are unchanged. The signing/metadata checks establish compatibility; actual phone upgrade preservation still requires device testing.

## Rebuild

Clone this repository or extract its source download. Android requires JDK 17+ (tested here with JDK 21.0.11), Android SDK platform/build-tools 36 and internet for dependency resolution. Fix `JAVA_HOME` if it points to a removed JDK. Release signing uses local `mobile/signing.properties` plus its referenced keystore. Maintainers restore their private signing backup; fork builds use their own locally generated signing key. Copy `mobile/signing.properties.example` to `mobile/signing.properties` and fill it locally. No signing key is included in the source ZIP.

On a PC with limited memory, run these commands separately from the `mobile` folder in PowerShell. The smaller first-phase heap compiled the app; a fresh process with the configured 640 MiB heap then completed dex merging and packaging. Close each phase before starting the next. One worker and serial GC are already configured.

```powershell
.\gradlew.bat :domain:test :app:compileReleaseKotlin '-Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:lintRelease
```

Server checks: create a Python 3.11 environment, install `server/requirements-dev.txt`, then set `PYTHONPATH=server` and run `python -m pytest server/tests`. See `VALIDATION.md` for measured checks, artifact hashes, failures and device/account checks still needed. Do not interpret a successful build as proof of camera, upgrade, cellular, or generated-preview behavior.
