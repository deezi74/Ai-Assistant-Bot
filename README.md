# TruAgent

An Android AI agent that runs GGUF models **on the phone** via llama.cpp, falls back to remote APIs (OpenAI-compatible, OpenRouter, Anthropic), and pulls **live web results** through DuckDuckGo or SearXNG. Kotlin + Jetpack Compose, dark UI, floating agent terminal that streams the model's thoughts, tool calls and observations.

- **Wit mode**: bold, funny, opinionated persona (editable `soul.md`), searches when facts may be stale.
- **Research mode**: sober, thorough, opens sources and cites them.
- **Reason → Act → Observe loop**: the model emits `<tool_call>{"name": ..., "arguments": {...}}</tool_call>`; generation is frozen at the closing tag, the tool runs, and its raw output goes back as an `[Observation]` turn until the model answers without a tool call.

## What's in the app

- **Chat** with a main chat plus side chats (drawer: search, rename, archive, delete), image attachments, voice input through the phone's speech recognizer, Wit and Research modes and the floating agent terminal.
- **Your assistant**: pick any name and one of 16 original avatars or your own photo (Settings ▸ Avatar & name, or tap the header). The name, what to call you, memory, enabled skills and open goals go into every prompt.
- **Feed**: short posts the assistant writes from one steering sentence, tuned by likes and dislikes. A "Write the feed" routine runs every morning.
- **Ideas**: ready-made prompts by category.
- **Goals**: tracked goals (the assistant can add them with the `track_goal` tool), routines that run a prompt on a schedule and save the result to the Library, and goal categories to start a conversation.
- **Library**: artifacts the assistant saves with `save_artifact`, your own notes, and media. Share or delete any file.
- **Settings**: Models, Manage providers, Token usage, Soul, Memory, Skills, System files, Connectors, Environment variables (`$NAME` in tool arguments), Permissions, Background & notifications, Storage, Shared folders, Mount external folders (read-only `shared_files` tool), Backup & restore (zip without secrets), Appearance (accent, pure black, text size), Data controls, Network (offline mode, Wi-Fi-only downloads), Logs, App lock, Set as default assistant, About, Privacy policy.
- **Phone controls** (`phone` tool): flashlight, volume, brightness, Do Not Disturb, alarms and timers, opening apps by name, settings panels, media keys, battery, clipboard, maps/links, and call/SMS drafts the user confirms. Toggle and one-time permissions in Settings ▸ Phone controls.
- **Recommended models**: one-tap downloads of tested GGUF builds (Qwen3 0.6B/1.7B/4B/8B, Qwen2.5 3B, Gemma 3 1B/4B) with a pick based on the phone's RAM; the download loads itself when done. Also offered on the empty chat screen.
- **Appearance**: accent, six backgrounds including a light one, and eight text colours.
- **Its own computer on the phone.** A sandboxed shell (`ls`, `cd`, `cat`, `mkdir`, `rm`, `grep`, `find`, `tree`, `run`) over a private folder, with JavaScript (ES6, Rhino interpreter) as the program language. The agent builds and runs projects there with the `computer` and `run_code` tools; you watch it in the Computer terminal and edit files in Code. No internet, no access to other apps, a 10 s time limit per run.
- **AI-generated characters.** Describe anything in Avatar & name (or say "turn yourself into a robot owl" in chat) and it draws a new character in one of 8 styles. Free through Pollinations, or with your own OpenAI or OpenRouter key. Past characters stay in a gallery.
- **FreeLLMAPI.** Pick "FreeLLMAPI (free models)" in Manage providers to use a self-hosted FreeLLMAPI router (many free-tier providers behind one key, model `auto`). Plain http is allowed only for localhost and LAN addresses.
- **Connectors.** Email over IMAP with an app password, as many accounts as you like (Gmail, Yahoo, iCloud, AOL, Fastmail, any IMAP); inbox and search cover every account unless you name one: summarize, search, read, mark read, archive and move to Trash; it never sends or permanently deletes. Location (platform LocationManager, opt-in) for local briefs; nearby places with distance, drive time (OSRM) and open/closed from OpenStreetMap hours, or Google Places with your key; weather from Open-Meteo.
- **Sign in with Google.** One tap per Google account (several allowed) gives the agent Gmail (IMAP with OAuth, same organize rules as email), Google Calendar (upcoming events and search; adding events only when allowed) and Google Drive (search and read Docs, Sheets, Slides and text files; read only). Needs a one-time Google Cloud setup: enable the Gmail, Calendar and Drive APIs, an External consent screen in Testing with your addresses as test users, and an Android OAuth client for package `dev.pocketagent` with the SHA-1 printed in each release's notes.
- **Voice mode.** Hands-free conversation: on-device speech recognition, the reply read aloud, then it listens again. Every reply also has read-aloud and copy buttons.
- **Imagine.** "Imagine a…" makes a picture right in the chat (Pollinations free, or OpenAI / OpenRouter with your key); attach a photo and ask for changes to edit it (needs OpenAI or OpenRouter). The + menu has Photo, Camera, Agent task and Imagine.
- **Agent tasks.** Pick Agent task in the + menu and describe a job: it opens its own chat, writes a plan, works through up to 30 tool steps in the background, saves the result to the Library and notifies you when it's done.
- **Trending feed.** The Feed tab's Trending button writes a "what's trending now" edition from the web, each post with a source.
- **Telegram.** Connect your own bot from @BotFather, pair your account with a code, and chat with the assistant from Telegram (text and photos in, text and images out). Runs as a foreground service on the phone.
- **Builds Android apps for you.** Ask for an app and the `app_builder` tool creates a private repo in your GitHub from a working Kotlin + Compose template, commits the code, lets GitHub Actions build a signed APK, reads compiler errors back so the agent can fix them, and returns the download link. It only writes to repos it created. Needs a GitHub sign-in with repo and workflow access.
- **Termux.** With `allow-external-apps=true` in Termux and the "Run commands in Termux" permission, the `termux` tool runs commands (short ones directly, long ones as background jobs with logs), writes files, sets up an on-phone Android toolchain (OpenJDK 17, Gradle, aapt2, SDK platform 35) and builds `~/apps/<name>` into an APK copied to Downloads. Off until you turn it on; refuses commands that would wipe storage.
- **Sign in with GitHub.** Device-flow sign-in: tap the button, enter the short code on github.com, done. Needs a GitHub OAuth app with Device Flow enabled; its Client ID can be typed in the app or set as the `TRUAGENT_GITHUB_CLIENT_ID` repository variable for CI builds.
- **GitHub.** A personal access token (stored encrypted, never in backups) lets it list repos, issues, pull requests and notifications, read files and search code and issues. Opening issues and commenting is off until you turn it on; it never pushes, merges, closes or deletes.
- **Speed.** The prompt carries a one-line-per-tool list; full tool instructions and skill steps are added only for the tools and skills a message needs, which cuts the prompt several times over for an on-device model. The stable part comes first so the on-device KV cache is reused between turns, the clock is not in the prompt (the `current_time` tool has it), memory and skills have size budgets, on-phone reflection waits until the app is idle, and Quick replies skips Qwen3's long thinking in Wit mode. A fast cloud provider (FreeLLMAPI with Groq or Cerebras keys) answers far faster than any on-phone model.
- **AI videos.** "Make a video of…" (or + ▸ Make a video, or attach a photo and say "animate this") makes a short clip with sound that plays in the chat and can be opened full screen or shared; Telegram gets it too. Uses Gemini Omni (`gemini-omni-1.1-flash`) with a Google AI Studio key, or Pollinations video models (Veo 3.1 fast, Seedance, Wan, Grok Imagine) with a Pollinations key. Settings ▸ Connectors ▸ Photos & videos.
- **Built-in Linux computer.** Settings ▸ Connectors ▸ Linux computer installs Alpine Linux inside the app (no root, no Termux): the `linux` tool runs shell commands, Python, Node, git and curl, installs packages with `apk`, and keeps servers running as background jobs. It runs through PRoot (GPL-2.0, from the Termux package repository, bundled as a separate program by `scripts/fetch-proot.sh` in CI) and can't see the phone's files or other apps.
- **Natural voices.** Settings ▸ Connectors ▸ Voice: replies are spoken by OpenAI `gpt-4o-mini-tts` (your OpenAI key), the same model through OpenRouter, or ElevenLabs (your key), streamed sentence by sentence so speech starts fast; it falls back to the phone voice if the service fails.
- **Live camera.** In voice mode, tap the camera button and show it things: each time you speak, it sends what the camera sees with your question (front or back camera; needs a model that can see images).
- **Better photos.** With a Pollinations key, Imagine uses the model you pick (Z-Image, Flux 2 Pro, Seedream 4.5, Gemini image, GPT Image 2, Ideogram); without one it uses the free service.
- **Learns as it grows.** After each task it reflects and saves facts about you (memory.md), lessons (lessons.md) and new skills; it can also save skills itself with `learn_skill`. It earns XP and levels up (Settings ▸ Growth), and everything learned can be read and deleted.

Upgrading from 1.1.0 resets chat history once because the database layout changed.

## Get the APK

Every push to `main` builds a signed arm64-v8a APK in GitHub Actions and publishes it under **Releases** (tag `build-N`). On your phone:

1. Open the repo's **Releases** page, download `TruAgent-N-arm64.apk`.
2. Open it and allow *Install unknown apps* for your browser / file manager.
3. In the app: **Settings → Download from Hugging Face**, keep the default `bartowski/Qwen2.5-1.5B-Instruct-GGUF`, tap *List files*, download the `Q4_K_M` file, then *Load* it. Or add an API key under *Remote endpoint*.

Requirements: Android 9+ on a 64-bit ARM CPU. 4 GB RAM runs 0.5B–1.5B Q4 models comfortably; 8 GB+ handles 3B–4B.

### Stable signing (so updates install over the old version)
CI signs with a cached debug key by default. For a permanent key, create one and add four repo secrets:
```bash
keytool -genkeypair -v -keystore release.jks -alias pocketagent -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # paste into secret POCKETAGENT_KEYSTORE_BASE64
```
plus `POCKETAGENT_KEYSTORE_PASSWORD`, `POCKETAGENT_KEY_ALIAS`, `POCKETAGENT_KEY_PASSWORD`.

## Project layout
```
TruAgent/
├── settings.gradle.kts            # repos; :app is skipped automatically when no Android SDK exists
├── build.gradle.kts
├── gradle/libs.versions.toml      # AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Room 2.6.1, WorkManager 2.10
├── agent-core/                    # pure Kotlin/JVM, unit-tested on any JDK
│   └── src/main/kotlin/dev/pocketagent/core/
│       ├── llm/LLMProvider.kt         # Flow<LlmEvent> contract
│       ├── llm/ProviderScheduler.kt   # local/remote routing with fallback
│       ├── llm/RemoteApiEngine.kt     # OpenAI-compatible / OpenRouter / Anthropic SSE streaming
│       ├── llm/LlamaBridge.kt         # JNI surface for libagentnative
│       ├── llm/GgufMetadataReader.kt  # reads GGUF headers for RAM planning
│       ├── llm/HuggingFaceClient.kt   # search, list .gguf, resumable downloads
│       ├── agent/AgentLoop.kt         # Reason-Act-Observe orchestrator
│       ├── agent/ToolCallParser.kt    # streaming <think>/<tool_call> splitter
│       ├── agent/PersonaManager.kt    # soul.md + system prompt assembly
│       └── tools/WebSearchTool.kt, FetchPageTool.kt, UtilityTools.kt
├── app/
│   ├── build.gradle.kts           # Compose M3, Room, WorkManager, OkHttp, EncryptedSharedPreferences, NDK arm64-v8a
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/CMakeLists.txt, llama_jni.cpp   # llama.cpp bridge (prefix-cached KV, UTF-8 safe streaming)
│       └── java/dev/pocketagent/
│           ├── llm/LocalGgufEngine.kt         # RAM check, context planning, single-thread native worker
│           ├── llm/ModelDownloadManager.kt    # WorkManager foreground downloads with progress
│           ├── agent/AgentSessionController.kt
│           ├── data/AppDatabase.kt, SecureSettings.kt
│           ├── service/AgentForegroundService.kt
│           └── ui/ChatScreen.kt, SettingsSheet.kt, ChatViewModel.kt, theme/Theme.kt
├── scripts/setup.sh, setup.ps1        # install SDK platform 35, build-tools, NDK, CMake + llama.cpp
├── scripts/build-apk.sh, build-apk.ps1 # setup + build + copy to dist/ (+ --install over adb)
└── .github/workflows/android.yml  # tests + APK + GitHub Release
```

## Build from source

The NDK and aapt2 that Google ships are x86_64-only on Linux, so **local builds need an x86_64 Linux, macOS or Windows machine**. On ARM devices, push to GitHub and let Actions build.

| Platform | Steps |
|---|---|
| **Linux (Ubuntu/Xubuntu, x86_64)** | `sudo apt install -y openjdk-17-jdk git unzip curl` → `./scripts/setup.sh` → `./gradlew :app:assembleRelease` |
| **Windows 10/11** | `winget install EclipseAdoptium.Temurin.17.JDK Git.Git` → `powershell -ExecutionPolicy Bypass -File scripts\setup.ps1` → `.\gradlew.bat :app:assembleRelease` |
| **Chromebook (Crostini)** | Intel/AMD Chromebooks: same as Linux. ARM Chromebooks: build via GitHub Actions. Install the APK by opening it from the Files app (Play Store-enabled devices) or `adb install` after enabling ADB in Linux settings. |
| **Android (Termux)** | Building Android apps on-device isn't reliable (no ARM64 aapt2/NDK from Google). Use the Release APK. Termux is handy for testing the core: `pkg install openjdk-17 git` → `./gradlew :agent-core:test`. |

One command does everything (SDK tools, llama.cpp, build, copy to `dist/`, optional install):

```bash
./scripts/build-apk.sh --install          # Linux / macOS / WSL / x86_64 Chromebook
```
```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -Install   # Windows
```

Install over USB by hand: `adb install -r dist/TruAgent-<version>-release-arm64.apk`.

Push a model without downloading in-app:
```bash
adb push model.gguf /sdcard/Android/data/dev.pocketagent/files/models/
```

## Testing

```bash
./gradlew :agent-core:test        # parser, agent loop, SSE parsing, DDG scraping, resumable downloads
```
The JNI bridge can be tested on a desktop JVM against a host build of `libagentnative`:
```bash
git clone --depth 1 --branch b12017 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
cmake -S app/src/main/cpp -B build-host -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build build-host
./gradlew :agent-core:test -Dpocketagent.nativeLib=$PWD/build-host/libagentnative.so -Dpocketagent.testModel=/path/model.gguf
```

## Notes
- API keys and the Hugging Face token are stored with EncryptedSharedPreferences (Keystore-backed AES-256). Anthropic uses the `x-api-key` header; OpenAI/OpenRouter use `Authorization: Bearer`.
- Local models get no image input; screenshots/images are only sent to remote vision models.
- On-device screen control (Accessibility/Shizuku) is not part of this build.
