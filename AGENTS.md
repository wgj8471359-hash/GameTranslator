# AGENTS.md

Welcome to **GameTranslator** repository. This document defines the operating rules, system environment constraints, architecture guidelines, and engineering best practices for AI coding agents working in this workspace.

---

## 1. Operating Environment & System Constraints

### 1.1 Host OS & Shell
* **Host OS**: Windows 10
* **Primary Shell**: PowerShell (`pwsh`)
* **Secondary Shell**: Git Bash (`bash.exe`)

### 1.2 Shell Syntax & Path Rules
* **Paths**: Always use Windows-style paths (e.g., `E:\workspace\GameTranslator\...` or backslashes `\`) when issuing commands or referring to local filesystem resources.
* **Never assume Linux / POSIX default commands** when running in PowerShell:
  * Do NOT use raw `export VAR=value` -> Use `$env:VAR = "value"`
  * Do NOT use `rm -rf <path>` -> Use `Remove-Item -Recurse -Force <path>`
  * Do NOT use `cat <file>` expecting GNU coreutils flags -> Use `Get-Content <file>`
  * Do NOT use `grep -rn <pattern>` -> Use `Select-String -Pattern <pattern>` or ripgrep (`rg`)
  * Do NOT use `touch <file>` -> Use `New-Item -ItemType File -Path <file>`
* **Git Bash Fallback**: If complex multi-line bash scripts or shell pipelines are required, execute them explicitly using `bash.exe -c "..."` or within a Git Bash session.

---

## 2. Project Overview & Architecture

**GameTranslator** is a lightweight, zero-censorship, personal-use Android overlay translation application designed for foreign-language games (specifically Korean/Japanese RPGs and visual novels).

### 2.1 Core Pipeline
1. **Screen Capture**: A semi-transparent floating ball (`WindowManager`) captures the screen on click using `MediaProjection` (silent capture in foreground service).
2. **Offline OCR**: Google ML Kit (`text-recognition-korean:16.0.0`) runs completely on-device without censorship or cloud network requirements.
3. **Text Clustering (Union-Find)**: Merges fragmented OCR lines belonging to the same dialogue bubble using 2D geometric proximity (vertical distance $\le 1.2\times$ line height, horizontal overlap $> -20\text{px}$). This prevents Korean SOV (Subject-Object-Verb) grammar distortion.
4. **LLM Translation**: Sends a single structured batch request to a local or LAN-hosted Tencent Hunyuan-MT2 model (`hy-mt2-1.8b` / `7b`) via OpenAI-compatible endpoint (`/v1/chat/completions`).
5. **Overlay Bubbles**: Dynamically overlays semi-transparent rounded rectangular `TextView`s on top of original game dialogue using `autoSizeTextType`. Clicking any empty area dismisses the overlay instantly without freezing game animations.

### 2.2 Technology Stack
* **Language & SDK**: Kotlin `1.9.22`, Java 17, `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`.
* **Build System**: Gradle `8.5`, Android Gradle Plugin (AGP) `8.2.2`.
* **Architecture**: Pure Views + Foreground Service + `WindowManager`. **Strictly NO Jetpack Compose, NO Dagger/Hilt.**
* **Core Libraries**:
  * `com.google.mlkit:text-recognition-korean:16.0.0`
  * `com.squareup.okhttp3:okhttp:4.12.0`
  * `com.google.code.gson:gson:2.10.1`
  * `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`

---

## 3. Critical Engineering Pitfalls (Must Comply)

When generating, modifying, or reviewing Android code in this repository, the following 5 system-level rules must never be violated:

1. **Android 14 (API 34) MediaProjection Callback**:
   Before calling `mediaProjection.createVirtualDisplay(...)`, you **MUST** call `mediaProjection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))`. Failure to register a callback throws `IllegalStateException` and crashes immediately on Android 14+.
2. **ImageReader RowStride Padding**:
   Physical display buffers usually have `rowStride > width * pixelStride`. Never copy pixels directly without taking row padding into account (`rowPadding = rowStride - width * pixelStride`). Always release `virtualDisplay` and close `image` immediately after frame capture to avoid memory leaks.
3. **Union-Find 2D Text Clustering**:
   Never sort lines purely by Y-coordinate in a single pass. Game interfaces have multiple UI areas (speaker name, dialogue, menu buttons). Lines must be clustered using pairwise adjacency + disjoint-set (Union-Find), then sorted within each connected component.
4. **WindowManager Fullscreen Cutout & Limits**:
   The bubble overlay root view must use `FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_LAYOUT_IN_SCREEN` with `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` so that physical screenshot coordinates match overlay window coordinates $1:1$.
5. **Foreground Service Declaration**:
   `AndroidManifest.xml` must declare `android:foregroundServiceType="mediaProjection"` for `TranslatorService`.

---

## 4. Key Repository Files

* [`.github/workflows/build-apk.yml`](file:///e:/workspace/GameTranslator/.github/workflows/build-apk.yml): GitHub Actions workflow. Automatically builds and signs a test APK using the repository permanent keystore (`signing/release.jks`, `apksigner` V1+V2+V3) and uploads it as an artifact. Uses `gradle/actions/setup-gradle@v3` to eliminate binary wrapper jar requirements.
* [`PROMPT.md`](file:///e:/workspace/GameTranslator/PROMPT.md): Ready-to-use Master Prompt to feed into code-generation models for complete project generation.
* [`安卓端本地离线截屏翻译方案.json`](file:///e:/workspace/GameTranslator/安卓端本地离线截屏翻译方案.json): Original PRD and architectural blueprint discussion.
* [`方案补充.txt`](file:///e:/workspace/GameTranslator/方案补充.txt): Evaluation and risk mitigation discussion.

---

## 5. Development & CI/CD Workflow

### 5.1 Cloud Build (Recommended)
Since the project is designed for zero-local-setup development:
1. Commit and push the generated code to GitHub:
   ```powershell
   git add .
   git commit -m "feat: implement game translator app"
   git push origin main
   ```
2. GitHub Actions will trigger automatically, build debug & release targets, sign the APK with the permanent keystore using `apksigner` (V1+V2+V3), and upload `GameTranslator-Signed-Test-APK`.
3. Download the artifact from the GitHub Actions run summary page, unpack, and install on the phone. Seamless overwrite update is supported without uninstalling.

### 5.2 Local Build (If Android SDK & JDK 17 are installed on Windows)
```powershell
# In PowerShell:
gradle assembleDebug
# or using git bash:
bash -c "gradle assembleDebug"
```
