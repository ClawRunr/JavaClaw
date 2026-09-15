# JavaClaw - Java Edition of OpenClaw

This project represents a Java version of OpenClaw. OpenClaw is an open-source, personal AI assistant designed to run on your own devices. It acts as a control plane (Gateway) for an assistant that can interact across multiple communication channels.

### Key Capabilities of OpenClaw
- **Multi-Channel Integration:** Supports platforms like WhatsApp, Telegram, Slack, Discord, Google Chat, Signal, iMessage, Microsoft Teams, Matrix, and more.
- **Background Daemon:** Runs as a background service to handle tasks and messages autonomously.
- **Extensible Skills:** Modular capabilities that can be added to enhance the assistant's functionality.
- **Local Control:** Designed for privacy and control, running on your own hardware.

---

## Technology Stack
- **Java 25**, Spring Boot 4.0.3, Spring Modulith 2.0.3
- **Job Scheduling:** JobRunr 8.5.0 — background jobs, dashboard on `:8081`
- **LLM Integration:** Spring AI 2.0.0-SNAPSHOT (OpenAI, Anthropic, Ollama)
- **MCP:** Spring AI MCP Client (Model Context Protocol)
- **Agent Framework:** Spring AI Agent Utils (Anthropic agent framework)
- **Database:** H2 (embedded)
- **Templating:** Pebble 4.1.1
- **Discord:** JDA 6.1.1 (Gateway / WebSocket)
- **Telegram:** Telegrambots 9.4.0 (long-polling)

---

## Module Structure

```
root
├── base/               ← Core: agent, tasks, tools, channels, config
├── app/                ← Spring Boot entry point, onboarding UI, web routes, chat channel
├── providers/
│   ├── anthropic/      ← Anthropic (Claude) provider + Claude Code OAuth support
│   ├── openai/         ← OpenAI (GPT) provider
│   ├── ollama/         ← Ollama local provider (no API key required)
│   └── google/         ← Google Gen AI (Gemini) provider
└── plugins/
    ├── telegram/       ← Telegram long-poll channel
    ├── brave/          ← Brave web search tool
    ├── discord/        ← Discord Gateway channel
    └── playwright/     ← Playwright browser tool
```

`app` depends on `base` + all `providers/` + all `plugins/`. `ChatChannel` lives inside `app/`.

Each **provider** implements `AgentOnboardingProvider` (in `base`) and self-registers via Spring Boot auto-configuration (`META-INF/spring/…AutoConfiguration.imports`) — same drop-in mechanism as plugins, independent of the app's component scan. Each **plugin** is an optional Spring Boot auto-configuration module that contributes tools or channels.

---

## Java Implementation Strategy (JavaClaw)

### Task Management
- Tasks are stored as Markdown files in the `workspace/tasks` folder.
- Path format for normal tasks: `yyyy-MM-dd/<HHmmss>-<name>.md`.
- Path format for recurring tasks: `recurring/<name>.md`.
- **States:** `todo`, `in_progress`, `completed`, `awaiting_human_input` — stored in YAML frontmatter (not in the filename).
- Task file format: YAML frontmatter with `task`, `createdAt`, `status`, `description` fields.
- The task `id` is the absolute file path, set by `FileSystemTaskRepository` on first save.

### Core Task Flow
```
User/Agent → TaskManager.create()
  → Task written as .md file via TaskRepository
  → JobRunr enqueues TaskHandler.executeTask(taskId)
  → TaskHandler: marks in_progress → prompts Agent → marks completed/awaiting_human_input
  → On error: resets status back to todo (retried up to 3 times by JobRunr)
```

### Core Components
- **`TaskManager`** (`base/src/main/java/ai/javaclaw/tasks/TaskManager.java`): Creates, schedules (immediate, specific-time, or cron), and manages recurring tasks. Saves via `TaskRepository`, then enqueues to JobRunr by task ID.
- **`Task`** (`base/.../tasks/Task.java`): Immutable value class. Fields: `id` (absolute file path), `name`, `createdAt`, `status`, `description`. Mutation via `withStatus()` / `withFeedback()`.
- **`TaskRepository`** (`base/.../tasks/TaskRepository.java`): Interface for task persistence — `save(Task)`, `getTaskById(String)`, `getTasks(LocalDate, Status)`, `save(RecurringTask)`, `getRecurringTaskById(String)`.
- **`FileSystemTaskRepository`** (`base/.../tasks/FileSystemTaskRepository.java`): Implements `TaskRepository`; handles all file I/O, YAML frontmatter serialization/deserialization, directory management, and filename sanitization.
- **`TaskHandler`** (`base/.../tasks/TaskHandler.java`): `@Job(retries=3)`-annotated; executes task via agent prompt; returns `TaskResult(Status newStatus, String feedback)` record (nested inside `TaskHandler`). Resets to `todo` on exception.
- **`RecurringTask`** (`base/.../tasks/RecurringTask.java`): Immutable value class for recurring task templates stored in `workspace/tasks/recurring/`.
- **`RecurringTaskHandler`** (`base/.../tasks/RecurringTaskHandler.java`): JobRunr worker spawning normal tasks from recurring templates on cron schedule.
- **`TaskNotFoundException`** (`base/.../tasks/TaskNotFoundException.java`): Thrown when a task file cannot be found or read by its ID.

### Workspace
- `workspace/AGENT.private.md`: System instructions + user-specific information (edited during onboarding step 4; falls back to `AGENT.md`).
- `workspace/AGENT.md`: Fallback system-instructions file when `AGENT.private.md` is absent.
- `workspace/AGENT-ORIGINAL.md`: Template / backup of original AGENT.md.
- `workspace/INFO.md`: Environment context auto-injected into every prompt.
- `workspace/context/`: Agent memory and context storage (e.g. `jobrunr.md`).
- `workspace/skills/`: Extensible skill files (`SKILL.md` per skill, loaded dynamically by `SkillsTool`).
- `workspace/tasks/`: Date-bucketed task files + `recurring/` sub-folder.
- `workspace/agents/`: Subagent definition files (`<name>.md`) — see "Agents (Subagents)" below.

---

## Agent System

**`DefaultAgent`** (`base/src/main/java/ai/javaclaw/agent/DefaultAgent.java`) wraps Spring AI's `ChatClient`.

**Prompt construction** (`MainChatClientProvider.java`):
- System prompt = `workspace/AGENT.private.md` (falls back to `workspace/AGENT.md`) + `workspace/INFO.md`
- **Advisors**: `SimpleLoggerAdvisor`, `ToolSearchToolCallingAdvisor` (falls back to plain `ToolCallingAdvisor`), `MessageChatMemoryAdvisor` (chat history)

**Default Tools** always injected:
| Tool | Purpose |
|---|---|
| `JavaClawTaskTool` | `createTask`, `scheduleTask`, `scheduleRecurringTask` |
| `CheckListTool` | Multi-step structured tracking (one `in_progress` at a time) |
| `FileSystemTools` | Read/write/edit files |
| `SmartWebFetchTool` | Intelligent web scraping |
| `SkillsTool` | Loads `SKILL.md` files from `workspace/skills/` |
| `McpTool` | Runtime MCP server management |
| MCP tools | `SyncMcpToolCallbackProvider` callbacks |
| subagent `TaskTool` | Dispatch to subagents in `workspace/agents/` |
| auto-discovered tools | e.g. `BraveWebSearchTool` (only if Brave API key configured) |

**Supported LLM Providers** — each lives in its own `providers/<name>/` module and contributes its `AgentOnboardingProvider` through an `@AutoConfiguration` class registered in the module's `AutoConfiguration.imports`. Each provider in `agent.llm.providers.*` is built through a `ChatModelFactory` SPI implementation and registered in `DefaultChatClientRegistry`; the `default` entry is the main agent's provider:

| Provider | Module | Default Model | API Key |
|---|---|---|---|
| `anthropic` | `providers/anthropic` | `claude-sonnet-4-6` | Required (or Claude Code OAuth) |
| `openai` | `providers/openai` | `gpt-5.4` | Required |
| `ollama` | `providers/ollama` | `qwen3.5:27b` | Not required (local) |
| `google.genai` | `providers/google` | `gemini-3-flash-preview` | Required |

The `AnthropicAgentOnboardingProvider` additionally supports a **system-wide token** via `AnthropicClaudeCodeOAuthTokenExtractor` — if a Claude Code OAuth token is found locally, it is offered as a zero-config option during onboarding.

---

## Channel Architecture

```
Incoming message → ChannelMessageReceivedEvent (channel name, message text)
  → ChannelRegistry routes to Agent
  → Agent.respondTo() → Channel.sendMessage()
```

- **`ChannelRegistry`**: Registers channels, tracks last-active channel so background task replies are routed correctly.
- **`DiscordChannel`**: JDA `ListenerAdapter`; accepts DMs from the configured user and guild messages only when the bot is mentioned.
- **`TelegramChannel`**: `SpringLongPollingBot`; filters by `allowedUsername`; stores `chatId` for routing background replies.
- **`ChatChannel`**: WebSocket-first delivery (`setWsSession()`/`clearWsSession()`); falls back to buffering replies in `ConcurrentLinkedQueue` exposed via `drainPendingMessages()` REST endpoint when no WebSocket session is active. Web chat responses stream live: `Agent.respondTo(conversationId, question, ResponseListener)` uses `ChatClient.stream()` and reports each token via the listener callback (`onToken`/`onComplete`/`onError`); `ChatChannel` supplies a listener that pushes JSON frames (`chunk`, `done`, `error` — see `StreamFrameType`) to the browser over the WebSocket. `Channel` itself knows nothing about streaming; all other channels use the blocking `respondTo`/`sendMessage()` path.

---

## Configuration Management

- **`ConfigurationManager`** (`base/src/main/java/ai/javaclaw/configuration/ConfigurationManager.java`): Updates nested YAML key-value paths in the runtime config file via SnakeYAML. Publishes `ConfigurationChangedEvent` on update, which triggers a full application restart.
- Runtime config file: `app/src/main/resources/application.private.yaml` (gitignored; imported into `application.yaml` via `spring.config.import`; read at startup and mutated by onboarding and the agents UI). `application.yaml` holds static defaults only.
- Key config paths:
  - `agent.onboarding.completed` — set to `true` after onboarding
  - `agent.workspace` — path to workspace root (`file:./workspace/`)
  - `agent.llm.providers.<name>.*` — `provider`, `base-url`, `api-key`, `model` per named provider (the `default` entry drives the main agent)
  - `jobrunr.background-job-server.worker-count: 1`
  - `jobrunr.dashboard.port: 8081`

---

## Agents (Subagents)

The main assistant can delegate to subagents. Each subagent is defined by two pieces:

- **Instructions**: a Markdown file `workspace/agents/<name>.md` with `name`, `description`, and `model` frontmatter plus a Markdown body (the subagent's instructions).
- **Provider config**: a named `agent.llm.providers.<name>` entry in `application.private.yaml` (`provider`, `base-url`, `api-key`, `model`). The subagent's `model:` frontmatter is the routing value — its own provider-entry name.

**`SubagentStore`** (`base/.../llm/SubagentStore.java`) reads/writes the Markdown files. `MainChatClientProvider` wires a subagent-dispatch `TaskTool` backed by a per-provider `ChatClient.Builder` map keyed by provider name.

The management UI (`GET /settings/agents`, template `settings/agents.html.peb`) is driven entirely by htmx fragments instead of a JSON API:

- **`AgentPageController`** (`app/src/main/java/ai/javaclaw/agents/AgentPageController.java`, an `@Controller`) serves HTML fragments:
  - `GET /settings/agents/fragments/list` → `settings/agents/list.html.peb`
  - `GET /settings/agents/new` and `GET /settings/agents/{name}/edit` → `settings/agents/drawer.html.peb`
  - `POST /settings/agents`, `PUT /settings/agents/{name}`, `DELETE /settings/agents/{name}` (form-encoded; validation errors return `422` + `HX-Retarget: #agent-drawer`)
- The page shell loads the list via `hx-trigger="load"`, opens the drawer via `hx-get` + `hx-on:htmx:after-swap`, saves via `hx-post`/`hx-put`, and deletes via `hx-delete` + `hx-confirm`. The only remaining JavaScript is drawer open/close, provider→default-model cascade, and enabling 422-response swaps.

---

## Tools and Capabilities
- **File System:** Read, write, and edit files.
- **Shell:** Execute bash commands.
- **Web:** Search (Brave API) and smart web fetching.
- **MCP:** Support for Model Context Protocol tools (via `SyncMcpToolCallbackProvider`).
- **Skills:** Custom modular skills loaded from `workspace/skills/` at runtime.
- **Channels:** Chat, Telegram, and Discord are implemented.

---

## Frontend Notes
- **Templating (https://pebbletemplates.io/):** Server-rendered HTML uses Pebble templates (suffix `.html.peb`) under `app/src/main/resources/templates/`. Base template: `templates/base.html.peb`.
- **Bulma (https://bulma.io/documentation):** Bulma v1.0.4 (CDN) is used for all web layout — CSS-only, mobile-first, semantic classes + modifiers like `is-primary`, `is-loading`, `is-danger`. Bulma v1 relies heavily on CSS variables. Themes are collections of CSS variables, so branding and light/dark adjustments can be done without rewriting component markup. Use Bulma layout primitives (`section`, `container`, `columns`, `card`, `message`, `progress`, `hero`) and helper classes instead of bespoke layout CSS where possible.
- **htmx v2.0.8 (https://htmx.org/docs/):** htmx is a strong fit for this app because it keeps the interaction model server-driven: the server returns HTML fragments, not JSON, and htmx swaps them into the DOM. We are using `hx-boost` which "boosts" normal anchors and form tags to use AJAX instead (preventing reloading of css and js). This has the nice fallback that, if the user does not have javascript enabled, the site will continue to work. Both Bulma and htmx are already included in `base.html.peb`.

### Onboarding UI
Entry point: `GET /index` → `IndexController.java` (redirects to `/onboarding/`) → `OnboardingController.java`. Session-based flow:
1. Welcome
2. Provider selection — dynamically populated from all `AgentOnboardingProvider` beans (Anthropic, OpenAI, Ollama, Google Gen AI, + any future providers)
3. Credentials (API key + model — skipped for providers where `requiresApiKey()` is `false`)
4. `AGENT.md` editor (system prompt customization)
5. MCP servers configuration (optional)
6. Plugin-contributed steps (e.g. Telegram bot token, Brave API key, Playwright) — injected by each plugin's `OnboardingProvider`
7. Complete summary

Templates live under `templates/onboarding/`, with plugin steps contributed from their own modules. Saves config via `ConfigurationManager.updateProperty()`.

---

## Key Architectural Patterns

| Pattern | Usage |
|---|---|
| **Event-Driven** | `ChannelMessageReceivedEvent`, `ConfigurationChangedEvent`, JobRunr background dispatch |
| **Template Method** | `AbstractTask` subclassed by `Task`, `RecurringTask` |
| **Strategy** | Multiple `Channel` implementations (Discord, Telegram, Chat) |
| **Record Types** | `TaskResult`, `CheckListItem` — structured LLM response types |
| **Markdown as State** | Tasks stored as `.md` files — queryable, diffable, human-readable |
| **Single Agent Instance** | `DefaultAgent` wraps `ChatClient`; all prompts routed through it |
| **Server-Driven HTML** | htmx + Pebble; progressive enhancement, works without JS |

---

## Tests

- `base/src/test/` — `TaskManagerTest`, `TaskHandlerTest`, `FileSystemTaskRepositoryTest`: task lifecycle + file persistence; `DefaultChatClientRegistryTest` and `SubagentStoreTest`: model registry + subagent storage; `ConfigurationManagerTest`: YAML path updates.
- `plugins/discord/src/test/` — `DiscordChannelTest`, `DiscordOnboardingProviderTest`: authorized Discord flow + onboarding config handling.
- `plugins/telegram/src/test/` — `TelegramChannelTest`: unauthorized user rejection, authorized message flow (mocked).
- `providers/anthropic/src/test/` — `AnthropicClaudeCodeBackendTest`: Claude Code OAuth token extraction.
- `app/src/test/` — `OnboardingControllerTest`: session-based workflow; `AgentPageControllerTest`: agents page fragment + validation behavior; `JavaClawApplicationTests`: full Spring context load with Testcontainers.
