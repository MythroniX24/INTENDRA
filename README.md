# INTERNDRA

**A privacy-first hybrid AI agent for Android — runs fully offline, or escalates to the cloud only when you allow it.**

INTERNDRA understands what you ask in plain language, turns it into real device actions (shell commands, app launches, messages, web search, file workflows), and shows you the actual result — not a tutorial on how to do it yourself.

---

## ✨ What it does

- **Hybrid AI** — a local on-device model (Qwen2.5 via llama.cpp) handles requests privately; cloud AI (via OpenRouter) is used only in Cloud/Hybrid mode, and only with your consent per request.
- **Real execution, not explanations** — requests like *"what's my battery and storage?"* or *"list files in Downloads"* run real commands and show real output in the chat, instead of a manual how-to.
- **Workflow Engine (Phase 5/6/7)** — a structured intent-detection + planning + execution engine that handles real-world workflows:
  - **WhatsApp messaging**: *"open WhatsApp and say hello"* → opens WhatsApp with your message pre-filled
  - **File search**: *"find all PDF files in my downloads"* → searches storage, filters by extension, returns structured results
  - **App launch**: *"open YouTube"* → launches the app
  - **Phone/SMS**: *"call +919876543210"* / *"SMS John: running late"*
  - **Device info**: *"battery status"* / *"storage space"* / *"network info"*
  - Multi-step chaining with safety validation, permission checks, and structured result reporting
- **Rich Markdown Chat (Phase 3)** — AI responses render with full markdown: headings, bullet/numbered lists, checklists, tables, code blocks (with copy button), block quotes, callout boxes (info/success/warning/danger), inline code, bold/italic/strikethrough, and tappable links.
- **Web Search Pipeline (Phase 4)** — search → fetch page content → extract main article → summarize → format with sources. Returns actual useful information, not just URLs.
- **Knowledge Vault** — a local, on-device knowledge base (RAG) you can pin notes, documents, and web clips into; the AI references it for context.
- **🧠 Train Memory** — one tap fetches fresh info across a rotating pool of topics into the Knowledge Vault.
- **Safety Engine (Phase 10)** — hardened command validator with normalization (whitespace/quote/IFS/base64 bypass protection) and 30+ regex patterns covering destructive commands, privilege escalation, and covert exfiltration.
- **Conversation memory** — the AI sees real prior messages in the conversation, not just a log of past actions.
- **Privacy modes** — Local-only, Cloud-only, or Hybrid (local first, cloud on demand), switchable any time in Settings.
- **Emergency Privacy Lock** — one-tap forces LOCAL_ONLY; restores your previous mode when deactivated.

---

## 🏗️ Architecture

```
com.interndra/
├── ai/                     Core AI logic
│   ├── AiOrchestrator.kt       Routes between local/cloud, parses model output into AiIntent
│   ├── LocalAiEngine.kt        On-device inference via llama.cpp (JNI)
│   ├── CloudAiEngine.kt        OpenRouter cloud calls, robust JSON extraction
│   ├── HybridExecutionEngine.kt  Executes parsed commands (shell, intents, messaging, files)
│   ├── CommandRegistry.kt      Keyword-matched fallback commands (battery, storage, etc.)
│   ├── SafetyEngine.kt         Hardened command validator (normalization + regex patterns)
│   ├── MemoryTrainer.kt        Train Memory feature — fetch + compress knowledge
│   ├── ModelDownloadManager.kt Local model download/install flow
│   ├── workflow/               Phase 5/6/7 — Workflow Engine
│   │   ├── WorkflowPlanner.kt    Intent detection + workflow composition
│   │   └── WorkflowEngine.kt     Safe execution with permission checks + narration
│   ├── agents/, graph/, rag/, ocr/, timeline/, intelligence/
│
├── data/
│   ├── local/               Room database, DAOs, repositories
│   ├── knowledge/           Knowledge Vault repository
│   └── model/               Data models (ChatMessage, AiIntent, Workflow, KnowledgeEntry, ...)
│
├── search/                  WebSearchEngine (Phase 4 pipeline: search → fetch → extract → summarize)
├── service/                 SmartShell (sandboxed command execution, concurrent IO, output cap)
├── services/                Notification listener (with safety re-validation), accessibility service
├── ui/
│   ├── components/            RichMarkdownText (Phase 3 — native Compose markdown renderer)
│   ├── screens/              Chat, Settings, Knowledge Vault, Timeline, Workspace, Terminal, Dashboards
│   └── viewmodel/            HybridAgentViewModel — the app's central state/orchestration
└── util/                    Constants (including the AI system prompt)
```

**Execution flow:**

1. User input → `HybridAgentViewModel.sendCommand()`
2. **WorkflowPlanner** tries to detect a known workflow (WhatsApp, file search, etc.)
3. If a confident match is found → `WorkflowEngine` executes it with narration
4. Otherwise → `AiOrchestrator` (local or cloud, per privacy mode) → parsed into `AiIntent`
5. `SafetyEngine` validates all commands (normalization + regex patterns)
6. `HybridExecutionEngine` runs commands via `SmartShell` (on `Dispatchers.IO`)
7. Results appended back into the chat message with rich markdown formatting

---

## 🔧 Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Local DB | Room |
| Local AI | llama.cpp (JNI) running Qwen2.5 Q4_K_M |
| Cloud AI | OpenRouter API |
| Networking | OkHttp |
| Markdown rendering | Native Compose (RichMarkdownText) — Phase 3 |
| Web search | Jsoup (DuckDuckGo HTML + page content extraction) — Phase 4 |
| Async | Kotlin Coroutines |
| Key storage | Encrypted DataStore |
| Build | Gradle 8.9, AGP 8.7.3, Kotlin Symbol Processing (KSP) |

**Min SDK:** 26 (Android 8.0) — required by the llama.cpp JNI build.

---

## 🚀 Getting started

### Prerequisites
- Android Studio (or Gradle CLI) with JDK 17
- An Android device/emulator running Android 8.0+
- *(Optional, for Cloud/Hybrid mode)* an [OpenRouter](https://openrouter.ai) API key
- *(Optional, for Local mode)* the local GGUF model downloaded via Settings → Local AI Model

### Build

```bash
git clone https://github.com/MythroniX24/INTERNDRA.git
cd INTERNDRA
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Run tests

```bash
./gradlew testDebugUnitTest
```

### First run
1. Install and open the app.
2. Grant the permissions it asks for (storage, notifications, etc. — each maps to a feature, see [Permissions](#-permissions) below).
3. Go to **Settings**:
   - To use **Cloud or Hybrid mode**, paste your OpenRouter API key and tap **Save Key**.
   - To use **Local mode**, tap **Download Model** (downloads the on-device model).
4. Pick a **Privacy Mode** (Local / Cloud / Hybrid) and start chatting.

---

## 🔐 Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Cloud AI calls, web search |
| `READ/WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES` | File operations the AI performs on request |
| `READ_CONTACTS`, `SEND_SMS`, `CALL_PHONE` | Messaging/calling actions |
| `RECORD_AUDIO` | Voice input |
| `POST_NOTIFICATIONS` | Status/result notifications |
| `BIND_ACCESSIBILITY_SERVICE` | UI automation features (enabled separately in Android Settings) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Notification-triggered automations |
| `FOREGROUND_SERVICE*`, `WAKE_LOCK` | Keeping long-running local AI tasks alive |

No permission is required just to install the app — each is only needed for the specific feature that uses it.

---

## 🛡️ Privacy & safety model

- **Local mode**: nothing leaves the device. All inference runs on-device via llama.cpp.
- **Cloud mode**: requests go to OpenRouter using your own API key (stored encrypted, locally).
- **Hybrid mode**: local-first; cloud is only used when needed, and the app asks for consent on each cloud escalation unless you choose otherwise.
- **SafetyEngine**: every command is checked before execution — destructive patterns (storage wipes, fork bombs, firmware flashing, security-disabling commands) are blocked outright. The engine normalizes commands (collapses whitespace, strips quotes, decodes base64 payloads) to prevent bypass tricks.
- **Memory sanitization**: pinned memories sent to cloud are sanitized — API keys, phone numbers, emails, and credit-card patterns are redacted before any cloud call.
- **Messaging**: INTERNDRA never auto-sends a message to an auto-picked contact. It pre-fills the message and opens the target app — you choose the contact and tap Send.

---

## 🗺️ Project status

Actively developed. The v2.2.0 upgrade includes:
- **Phase 8**: Full rebrand from INTENTRA → INTERNDRA (packages, classes, files, manifest, docs)
- **Phase 2/10**: Critical bug fixes — SmartShell ANR/deadlock, SafetyEngine bypasses, web search main-thread blocking, emergency lock state, network security, memory leak
- **Phase 3**: Native Compose rich markdown renderer (headings, lists, tables, code blocks with copy, callouts, quotes)
- **Phase 4**: Web search pipeline with page content fetching + extraction + 30-minute cache
- **Phase 5/6/7**: Workflow Engine with intent detection, planning, WhatsApp/file/device workflows, structured narration
- **Phase 9/11**: UI/layout refinements, performance optimizations (hoisted renderer, keys, regex precompilation, output caps)

See `UPGRADE_AUDIT_REPORT.md` for the deeper technical audit log.

---

## 🤝 Contributing

Issues and PRs are welcome — please keep changes scoped and include a clear description of what was tested.

## 📄 License

Not yet specified — all rights reserved by the repository owner until a license is added.
