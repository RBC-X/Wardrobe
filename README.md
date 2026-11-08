# Wardrobe — your private Android wardrobe

Current delivery: **1.0.1**, signed Android versionCode **2**. The release preserves the existing signing identity and database format. A separate debug-signed weather-first preview is available as a prerelease; it cannot be installed over the signed 1.0.1 app.

Wardrobe is a personal Android 10+ app with an optional Windows companion. Matching, manual editing, photo colour extraction, today's weather, preferences and actual-photo outfit boards work on the phone. AI features start locked; add your own OpenAI or Gemini key or connect your ChatGPT plan in the app, and clothing analysis and styling advice run on the phone, with credentials encrypted by the Android keystore. The PC is optional — pair it for sync and try-on previews; automatic framing, product import, styling advice and clothing analysis run on the phone. The download includes the 4.6 MB detection model, so detection works offline.

## Install on Android

1. Copy `Wardrobe.apk` to your phone and open it. Allow installation from the file/browser app only when Android asks. Revoke that installation permission afterward if desired.
2. Launch **Wardrobe**. It starts empty: Today → Add your first item.
3. Add at least a top, bottom (or a one-piece garment), and shoes. Camera access is optional; use Import photo or manual fields if denied.
4. Take or import a photo. The phone finds the garment with the bundled model and frames the photo automatically — adjust only if an edge is cut off. No PC or key is involved. Correct the colors/category and save; other angles or labels can be added as extra photos. The original gallery photo is unchanged, and the crop is copied into private app storage.
5. Open the saved item and tap **Detect clothing**. The bundled U²-Net model runs on the phone: it removes the background, trims the photo to the garment, reads type, main colours and silhouette, and shows a review. It is free, private and needs no PC or key; if the model cannot load, a paired PC can run the same detection. Change any field you disagree with before applying; corrections are protected from later analysis. **AI analysis** and **styling advice** run on the phone with your own provider key or ChatGPT plan, no PC involved.
6. Today opens with today's weather, then up to three looks ranked for the day; the highest-scoring look is first and marked **★ Recommended**. On first launch, allow approximate location for the forecast or enter a city. Timeless, Balanced (default), or Trend-focused modes and occasion/weather/season controls are under Today's edit. The phone fetches a keyless Open-Meteo forecast and sets warm, mild or cold from the perceived temperature. Build around an item, swap pieces, save, give feedback, or export an outfit board.

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

Open **Connections → AI connections** on the phone, or use the PC dashboard, and enable only the connections you want:

- **ChatGPT plan (phone):** sign in under Connections → AI connections with the official **Sign in with ChatGPT** flow. The phone keeps the OAuth tokens encrypted with the Android keystore and uses your plan through OpenAI's Responses API; no PC or API key is needed. This is ChatGPT plan usage, **not the Codex CLI agent**: it cannot run commands or edit files, and plan limits apply. After sign-in, choose a model under the connection; vision-capable models are marked for photo analysis, while text-only models leave analysis locked.
- **Codex on your PC:** install the current Codex CLI and authenticate with your ChatGPT account. The Codex agent is PC-only; the phone connection above does not expose it. Wardrobe uses a clean temporary Codex home, stdio app-server, a read-only job root, disabled command tools/hooks/plugins/MCP/browser integrations, and selected job images. It does not expose the agent protocol to the phone. The setup check requires the restricted filesystem sandbox to start; Some Windows configurations reject these restricted filesystem permissions; Codex stays locked when its isolation check fails. There is deliberately no unsandboxed fallback.
- **Claude Code:** install the current CLI and authenticate with a Claude subscription. Wardrobe uses structured print mode with safe/restricted mode, no tools, no MCP, no hooks/skills, no persisted session, and selected images. Older CLIs that lack these isolation flags fail rather than falling back to general execution.
- **Gemini API / OpenAI API:** enter your own API key in the app under Connections → AI connections, or in the PC dashboard. The phone keeps it encrypted with the Android keystore and analyses clothing and writes styling advice directly; when paired it also sends the key to your PC, which protects it with Windows DPAPI and never returns it. Neither side includes keys in exports. Do not paste keys into source files or scripts.

Phone-side analysis and styling call the provider directly, so the PC's budget and free-only guards below do not cover them: they spend against your own provider account or ChatGPT plan. Saving a budget, a photo-sharing toggle or a free-only choice keeps a checked connection working; replacing or clearing the key invalidates the previous login and image check. Background trend analysis and capability probes remain PC-dashboard-only. A paired phone can only see and cancel jobs it submitted itself.

Free-only is the default. OpenAI paid requests stay locked until a positive app budget is entered and free-only is disabled. Free-only Gemini requires explicit confirmation that billing is disabled on the API project; provider quota availability still applies. PC subscription connections use their account allowances. Background trend analysis is separately opt-in.

The app conservatively reserves **$1 per submitted paid request** against its app budget. This is a request allowance, **not a hard provider billing cap or exact cost accounting**. A request can cost more; configure provider-side spend limits too. Failed or uncertain submitted requests retain their reservation. A successful login/models check is not proof of every runtime entitlement: unsupported/expired/quota-exhausted requests report provider failure. Vision additionally requires photo sharing; generated images additionally require a successful real image probe. Claude has no image generation/web-search route in this implementation. Codex web search is intentionally disabled. Cloud providers are never automatically configured from ambient environment keys.

**Detect clothing** is local, free and needs no AI connection: the bundled U²-Net weights run on the phone, mask the garment, the photo is trimmed to that mask, and type, dominant colours, fit and silhouette are read from those masked pixels only. Backgrounds therefore never enter the colour or shape reading. Detection returns a confidence value and a suggestion; the review lets you change every field before applying, and each field you change is locked against later analysis. Outlines alone cannot prove a brand, fabric or exact subtype, so low-confidence suggestions should always be checked.

Review clothing analysis before applying it. Manually corrected fields are protected, stale revisions are rejected, and possibleBrand remains unconfirmed. AI analysis prefers the saved background-free cutout and reports when no background could be excluded. Try-on requires explicit photo consent and a supported image route; choose your own reference under Your style or use a generic adult model. Every generated preview is approximate, not a physical-fit guarantee.

## Products, trends, and sources

Paste an exact public HTTPS product URL into Edit → Find details. The phone fetches and parses the page itself; no PC or AI key is needed. Select the exact item/color variant and image in the review, then explicitly confirm. Blocked, inaccessible, or unstructured pages may require manual editing. The phone accepts only HTTPS, re-checks every redirect, blocks private/local/Tailscale hosts, caps page and image size, and re-encodes the selected image before private storage.

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

Clone this repository or extract its source download. Android requires JDK 17+ (tested here with JDK 17.0.21), Android SDK platform/build-tools 36 and internet for dependency resolution. Fix `JAVA_HOME` if it points to a removed JDK. Release signing uses local `mobile/signing.properties` plus its referenced keystore. Maintainers restore their private signing backup; fork builds use their own locally generated signing key. Copy `mobile/signing.properties.example` to `mobile/signing.properties` and fill it locally. No signing key is included in the source ZIP.

On a PC with limited memory, run these commands separately from the `mobile` folder in PowerShell. The smaller first-phase heap compiled the app; a fresh process with the configured 640 MiB heap then completed dex merging and packaging. Close each phase before starting the next. One worker and serial GC are already configured.

```powershell
.\gradlew.bat :domain:test :app:compileReleaseKotlin '-Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:lintRelease
```

On Linux, install Android SDK platform/build-tools 36 and run `./gradlew :domain:test :app:assembleDebug`. The installable debug APK is `mobile/app/build/outputs/apk/debug/app-debug.apk` (debug-signed, not a release artifact). The domain test task runs the offline ChatGPT harness: OAuth URL/form coverage, a real loopback socket callback test, token/refresh parsing, model/vision parsing and Responses SSE parsing. It needs no OpenAI account.

Server checks: create a Python 3.11 environment, install `server/requirements-dev.txt`, then set `PYTHONPATH=server` and run `python -m pytest server/tests`. See `VALIDATION.md` for measured checks, artifact hashes, failures and device/account checks still needed. Do not interpret a successful build as proof of camera, upgrade, cellular, or generated-preview behavior.
