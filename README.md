# Pi Chat

**Pi Chat** is an IntelliJ IDEA plugin that embeds the local [pi](https://pi.dev) coding agent as a right-side tool window. It brings the pi terminal experience into the IDE — the same sessions, the same commands, the same model — without leaving your editor.

> The plugin manages its own pi RPC backend process. Any pi features you rely on in the terminal (agents, skills, MCP, slash commands) work here too.

---

## Features

- **Pi backend in the IDE** — launches `pi --mode rpc` as a local subprocess per project, speaking JSONL over stdin/stdout.
- **Shared sessions** — reads/writes the same session files as the terminal pi (`~/.pi/agent/sessions/`). Opening the panel in a project auto-resumes the most recent session for that directory. Renaming or deleting a session in the plugin is reflected in the terminal pi and vice versa.
- **Single-process session model** — only one pi process is alive at a time (matching the terminal). Switching history sessions or starting a new one restarts the process.
- **Streaming chat UI** — streaming text and thinking deltas, tool-call cards, and command output rendered inline in the message stream.
- **Model & reasoning controls** — model picker, thinking-level selector, and a **context-window preset selector** (200K / 400K / 1M) driven by the pi `ctx-preset` extension.
- **Input-box completion** — slash-command completion (from pi's `get_commands`), `@file` reference completion (project tree scan), input history (↑/↓) and ghost completion.
- **Status bar** — current model, context usage with a progress ring, cache hit rate, token counters, and a busy/idle indicator.
- **History management** — session list with load / rename / delete, and inline ask-user dialogs for pi's `select` / `confirm` / `input` extension requests.
- **Minimal by design** — top message area, input box, and history only. Simplified Chinese UI, theme + font settings.

---

## Prerequisites

- **IntelliJ IDEA** 2026.2+ (Ultimate or Community)
- **Node.js** 20+ (or the bundled Node you already use for pi)
- **pi coding agent** installed and on `PATH` — the plugin locates the pi CLI and spawns it with `--mode rpc`. You also need at least one configured model provider (e.g. in `~/.pi/agent/settings.json` or via `pi login`).

---

## Installation

### From JetBrains Marketplace

Search for **"Pi Chat"** in `Settings → Plugins → Marketplace` and install it.

### From disk (development builds)

1. Build the plugin zip: `.\gradlew.bat buildPlugin` (output: `build/distributions/pichat.zip`)
2. In IDEA: `Settings → Plugins → ⚙ → Install Plugin from Disk...` → select the zip → restart.

---

## Usage

1. Open the **Pi Chat** tool window from the right tool window bar.
2. The plugin starts a pi backend for the current project and auto-resumes the latest session.
3. Type a message and press `Enter` (or `Shift+Enter` for a newline).
4. Use `/` for slash commands, `@` for file references, and the buttons in the input bar to switch model / thinking level / context window.

> Sessions are stored under `~/.pi/agent/sessions/<encoded-project-path>/`, shared with the terminal pi. Deleting the currently-active session may be blocked while its file handle is open — close the session first.

---

## Development

```
# Build the Kotlin plugin (compiles + instruments + zips)
.\gradlew.bat buildPlugin

# Run a sandbox IDE with the plugin (uses the local IDEA install if configured)
.\gradlew.bat runIde

# Webview frontend (React 19 + Vite + Tailwind 4)
cd webview
npm install
npm run build          # emits webview/dist, copied into resources/web/index.html
```

The webview is a single-file bundle embedded in the plugin; after changing frontend code, rebuild it, then `buildPlugin`, then restart the sandbox.

### Project layout

```
src/main/kotlin/com/ruigu/pichat/   Kotlin plugin layer
  └─ ui/                            ToolWindow factory + ChatPanel (JCEF browser + pi RPC bridge)
src/main/java/com/ruigu/pichat/rpc/ Java RPC layer (subprocess spawn, JSONL protocol, pi locator)
webview/                            React webview frontend (chat UI, input box, history)
```

The plugin communicates with the pi RPC backend over a JSONL bridge: the Kotlin side pushes state (models, commands, sessions, stats) to the webview, and the webview sends user actions back.

---

## Acknowledgements

- This project's UI is heavily inspired by and adapted from the open-source [jetbrains-cc-gui](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) (MIT) — the input-box, streaming message rendering, and completion UI are based on it.
- The [pi](https://pi.dev) coding agent by Earendil Works.

---

## License

MIT — see [LICENSE](LICENSE).
