# JavaClaw

JavaClaw is a Java-based personal AI assistant that runs on your own devices. It acts as a control plane (gateway) for an AI agent that can interact across multiple communication channels, manage tasks, execute shell commands, browse the web, and more — all while keeping your data local.

> NOTE: this project was originally created as a demo to show the use of JobRunr, JavaClaw is now an open invitation to the Java community, let’s build the future of Java-based AI agents together ☕.

## Features

- **Multi-Channel Support** — Chat UI (WebSocket), Telegram, Discord, and an extensible plugin-based channel architecture
- **Task Management** — Create, schedule (one-off, delayed, or recurring via cron), and track tasks as human-readable Markdown files
- **Extensible Skills** — Drop a `SKILL.md` into `workspace/skills/` and the agent picks it up at runtime
- **LLM Provider Choice** — Plug in OpenAI, Anthropic, or Ollama (local); switchable during onboarding
- **MCP Support** — Model Context Protocol client for connecting external tool servers
- **Shell & File Access** — Agent can read/write files and run bash commands on your machine
- **Smart Web Tools** — Brave web search and intelligent web scraping
- **Background Jobs** — Powered by JobRunr with a built-in dashboard at `:8081`
- **Privacy-First** — Runs entirely on your own hardware; no data leaves unless you configure an external LLM

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.3, Spring Modulith 2.0.3 |
| LLM | Spring AI 2.0.0-SNAPSHOT (OpenAI, Anthropic, Ollama) |
| Agent | Spring AI Agent Utils |
| MCP | Spring AI MCP Client |
| Jobs | JobRunr 8.5.0 |
| Database | H2 (embedded, file-backed) |
| Templating | Pebble 4.1.1 |
| Frontend | htmx 2.0.8 + Bulma 1.0.4 |
| Discord | JDA 6.1.1 |
| Telegram | Telegrambots 9.4.0 |

## Project Structure

```
JavaClaw/
├── base/           # Core: agent, tasks, tools, channels, config
├── app/            # Spring Boot entry point, onboarding UI, web routes, chat channel
└── plugins/
    ├── brave/      # Brave web search integration
    ├── discord/    # Discord Gateway channel plugin
    ├── playwright/ # Browser automation tools
    └── telegram/   # Telegram long-poll channel plugin
```

## Getting Started

### Prerequisites

- Java 25
- Gradle (or use the included `./gradlew` wrapper)
- An LLM API key (OpenAI / Anthropic) **or** a running [Ollama](https://ollama.com) instance

### Run

```bash
./gradlew :app:bootRun
```

You can also build a docker image and use it as follows (from the cloned repo):
```bash
./gradlew app:jibDockerBuild
docker run -it -p 8080:8080 -p:8081:8081 -v "$(pwd)/workspace:/workspace" jobrunr.io/javaclaw
```

Then open [http://localhost:8080/onboarding](http://localhost:8080/onboarding) to complete the guided onboarding.

### Onboarding

1. **Welcome** — Introduction screen
2. **Provider** — Choose Ollama, OpenAI, or Anthropic
3. **Credentials** — Enter your API key and model name
4. **Agent Prompt** — Customize `workspace/AGENT.md` with your personal info (name, email, role, etc.)
5. **MCP Servers** — Optionally configure Model Context Protocol servers
6. **Channel/Tool Plugins** — Optional steps such as Telegram, Discord, and other plugin-provided setup
7. **Complete** — Review and save your configuration

Configuration is persisted to `app/src/main/resources/application.yaml` and takes effect immediately.

## Workspace

The `workspace/` directory is the agent's home:

| Path | Purpose |
|---|---|
| `workspace/AGENT.md` | System prompt — edit during onboarding or at any time |
| `workspace/INFO.md` | Environment context injected into every prompt |
| `workspace/context/` | Agent memory and long-term context files |
| `workspace/skills/` | Drop a `SKILL.md` here to add new capabilities |
| `workspace/tasks/` | Task files, date-bucketed (`yyyy-MM-dd/HHmmss-<state>-<name>.md`) |
| `workspace/tasks/recurring/` | Cron-scheduled recurring task templates |

### Task States

`todo` → `in_progress` → `completed` / `awaiting_human_input`

## Channels

### Chat (built-in)

Available at [http://localhost:8080/chat](http://localhost:8080/chat). Uses WebSocket for real-time delivery and falls back to REST polling when no WebSocket session is active.

### Telegram

Configure during onboarding or by setting:

```yaml
agent:
  channels:
    telegram:
      token: <your-bot-token>
      username: <your-telegram-username>
```

### Discord

Configure during onboarding or by setting:

```yaml
agent:
  channels:
    discord:
      token: <your-discord-bot-token>
      allowed-user: <your-discord-user-id>
```

## Skills

Skills extend the agent's capabilities at runtime without code changes. Create a directory under `workspace/skills/<skill-name>/` containing a `SKILL.md` file and the agent will load it automatically via `SkillsTool`.

## Dashboard

JobRunr's job dashboard is available at [http://localhost:8081](http://localhost:8081) for monitoring background task execution.

## Configuration

Key properties in `application.yaml`:

| Property | Description |
|---|---|
| `agent.workspace` | Path to the workspace root (default: `file:./workspace/`) |
| `agent.onboarding.completed` | Set to `true` after onboarding is done |
| `spring.ai.model.chat` | Active LLM provider/model |
| `javaclaw.tools.dynamic-discovery.enabled` | Enable dynamic tool discovery (Tool Search Tool pattern) instead of exposing all tools up front |
| `jobrunr.dashboard.port` | JobRunr dashboard port (default: `8081`) |
| `jobrunr.background-job-server.worker-count` | Concurrent job workers (default: `1`) |

### Dynamic Tool Discovery

When enabled, JavaClaw uses Spring AI's "Tool Search Tool" pattern ("tool search") so the model discovers relevant tools at runtime instead of receiving every tool definition up front.

Use it when:
- You have many tools (plugins, MCP servers, skills) and prompts are getting large.
- The model picks the wrong tool because the tool list is too big or too similar.

```yaml
javaclaw:
  tools:
    dynamic-discovery:
      enabled: true
      # Optional tuning:
      max-results: 8
      lucene-min-score-threshold: 0.25
```

Flag behavior:
- `enabled=true` (default): uses the Tool Search advisor (dynamic discovery, Lucene keyword search).
- `enabled=false`: eager tool exposure (legacy behavior).

Notes:
- Tool search quality depends on `@Tool(description = "...")`. Keep descriptions specific and disambiguating.
- Tuning: raise `lucene-min-score-threshold` to be stricter; lower it if tools are not found. Adjust `max-results` to control how many tools get surfaced.

## Running Tests

```bash
./gradlew test
```

Tests cover task management, Telegram and Discord channel authorization/flow, onboarding steps, and the full Spring context.

## More info?

[Release Demo Video](https://www.youtube.com/watch?v=_n9PcR9SceQ)
[Website](https://clawrunr.io/)
[Release Blog post](https://www.jobrunr.io/en/blog/clawrunr/)

## License

This project is open-source. See [LICENSE](license.md) for details.
