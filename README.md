# JavaClaw

JavaClaw is a Java-based personal AI assistant that runs on your own devices. It acts as a control plane (gateway) for an AI agent that can interact across multiple communication channels, manage tasks, execute shell commands, browse the web, and more — all while keeping your data local.

> NOTE: this project was originally created as a demo to show the use of JobRunr, JavaClaw is now an open invitation to the Java community, let’s build the future of Java-based AI agents together ☕.

## Features

- **Multi-Channel Support** — Chat UI (WebSocket), Telegram, Discord, WhatsApp, Microsoft Teams, and an extensible plugin-based channel architecture
- **Task Management** — Create, schedule (one-off, delayed, or recurring via cron), and track tasks as human-readable Markdown files
- **Extensible Skills** — Load skills from `workspace/skills/` and from `skills jar` packages on the classpath
- **LLM Provider Choice** — Plug in OpenAI, Anthropic, your Claude Pro/Max subscription (via the official Claude Code CLI), or Ollama (local); switchable during onboarding
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
    ├── teams/      # Microsoft Teams webhook channel plugin
    └── telegram/   # Telegram long-poll channel plugin
```

## Getting Started

### Prerequisites

- Java 25
- Gradle (or use the included `./gradlew` wrapper)
- An LLM API key (OpenAI / Anthropic), a Claude Pro/Max subscription with [Claude Code](https://code.claude.com) installed and logged in (`claude auth login`), **or** a running [Ollama](https://ollama.com) instance

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
2. **Provider** — Choose Ollama, OpenAI, Anthropic, or Claude subscription
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

JavaClaw's WebSockets (the chat's, and the push socket Vaadin registers for the `/ui` pages) only accept pages served by JavaClaw itself (the same scheme, host and port), because the chat can answer [tool approvals](#tool-approval). A handshake from another origin, or without an `Origin` header, is refused. If a reverse proxy serves JavaClaw under another address, list that origin in `agent.web.allowed-origins`:

```yaml
agent:
  web:
    allowed-origins: [https://claw.example.com]
```

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

### WhatsApp

WhatsApp connectivity uses [wacli](https://github.com/openclaw/wacli), a small Go CLI that pairs as a
WhatsApp Web linked device. There is no Java WhatsApp library — JavaClaw launches `wacli` as a
subprocess and receives inbound messages through a local webhook.

**1. Install wacli**

```bash
# macOS
brew install openclaw/tap/wacli

# Linux
go install github.com/openclaw/wacli@latest
```

**2. Pair your phone**

Run the following and scan the QR code with WhatsApp on your phone (Settings → Linked devices → Link a
device):

```bash
wacli auth
```

**3. Configure**

Configure during onboarding or by setting:

```yaml
agent:
  channels:
    whatsapp:
      enabled: true
      allowed-chat-jid: "1234567890@s.whatsapp.net"
```

`allowed-chat-jid` is the JID of the single WhatsApp chat the assistant responds in. During
onboarding you pick it by name from your synced chats, so you rarely need to type it — WhatsApp also
issues opaque `@lid` identifiers that cannot be derived from a phone number. To find one yourself:

```bash
wacli chats list                    # every chat, with its JID
wacli chats list --query "alice"    # search by name
```

Only one-to-one chats are accepted. Groups, newsletters and broadcasts are deliberately refused:
the assistant authorises by chat, so every member of a group would be able to instruct it — and the
agent can run shell commands. Point it at your own "Message Yourself" chat to talk to the assistant
from your own number. `wacli` itself is taken from your `PATH`.

> **Disclaimer:** wacli uses the unofficial WhatsApp Web protocol — use at your own risk.

### Microsoft Teams

Teams delivers messages to a bot through [Azure Bot Service](https://learn.microsoft.com/azure/bot-service/):
Microsoft calls a public HTTPS webhook on JavaClaw, and JavaClaw answers through the Bot Framework
Connector API.

**1. Expose the webhook**

The webhook lives at `/api/teams/webhook`. Azure must be able to reach it over public HTTPS, so for
a self-hosted setup put a tunnel in front of JavaClaw, for example:

```bash
ngrok http 8080   # gives you https://<random>.ngrok-free.app
```

**2. Create the bot**

In the Azure portal, create an **Azure Bot** resource, set its messaging endpoint to
`https://<your-host>/api/teams/webhook`, and enable the **Microsoft Teams** channel. Note the bot's
App ID, create a client secret for it, and note your Tenant ID.

**3. Configure**

Configure during onboarding or by setting:

```yaml
agent:
  channels:
    teams:
      enabled: true
      app-id: <bot-app-id>
      app-secret: <bot-client-secret>
      tenant-id: <azure-tenant-id>
      allowed-user-id: <your-entra-object-id>
```

`allowed-user-id` is the only Teams user the assistant responds to: either your Microsoft Entra
object ID (Azure portal → Microsoft Entra ID → Users) or your Teams user ID (`29:...`). It is
required, because the agent can run shell commands; without it every message is ignored. Every
webhook call must carry a valid Bot Framework JWT (signature, issuer, audience, expiry and
`serviceUrl` are checked), so requests that do not come from Microsoft are rejected with `401`.

The assistant only responds in personal (1:1) chats with the bot. Messages in team channels or group
chats are ignored, even from the allowed user, because replies can contain shell output or file
contents. Messages the agent sends on its own, e.g. from scheduled tasks, also only go to the 1:1 chat.


## Skills

Skills extend the agent's capabilities at runtime without code changes. Create a directory under `workspace/skills/<skill-name>/` containing a `SKILL.md` file and the agent will load it automatically via `SkillsTool`.

### Skills As Jars (SkillsJars)

You can also package skills into a jar (or use a prebuilt one) and put it on the runtime classpath. JavaClaw can load skills from `classpath:/META-INF/skills`.

1. Add a dependency that contains the skills (example):

```gradle
dependencies {
  runtimeOnly("com.skillsjars:some-skill-pack:VERSION")
}
```

2. Configure the classpath scan path:

```yaml
agent:
  skills:
    paths: classpath:/META-INF/skills
```

3. Ensure the jar contains this layout:

```text
META-INF/skills/<skill-name>/SKILL.md
```

`SKILL.md` must include YAML frontmatter with a `name` (this is what you invoke):

```md
---
name: <skill-name>
description: <optional>
---
<instructions...>
```

See the SkillsJars docs for packaging and published skill packs: https://www.skillsjars.com/

## Dashboard

JobRunr's job dashboard is available at [http://localhost:8081](http://localhost:8081) for monitoring background task execution.

## Configuration

Key properties in `application.yaml`:

| Property | Description |
|---|---|
| `agent.workspace` | Path to the workspace root (default: `file:./workspace/`) |
| `agent.onboarding.completed` | Set to `true` after onboarding is done |
| `agent.skills.paths` | Classpath resource roots to scan for skills (e.g. `classpath:/META-INF/skills`) |
| `spring.ai.model.chat` | Active LLM provider/model |
| `javaclaw.tools.dynamic-discovery.enabled` | Enable dynamic tool discovery (Tool Search Tool pattern) instead of exposing all tools up front |
| `agent.approval.*` | Which tool calls need your approval, and how long a request waits. See [Tool Approval](#tool-approval) |
| `agent.web.allowed-origins` | Extra browser origins (`scheme://host[:port]`) allowed to open JavaClaw's WebSockets, for a reverse proxy. Empty by default: only JavaClaw's own origin. Wildcards are refused at startup. |
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

### Tool Approval

Before the agent runs a tool that can change something, it pauses and asks you where the request came from: in the web chat, or in the Telegram, Discord, WhatsApp or Teams chat you wrote in. You see the tool, its risk, its exact arguments and, for file tools, the working directory. For a subagent you see the subagent type and the full prompt. You choose to allow it once or deny it. A denied call is not an error: the agent gets the denial and the reason as the tool's result and carries on without it.

Every tool has a risk level: `READ_ONLY`, `WRITE`, `EXECUTE` or `NETWORK`. With no rules configured, `READ_ONLY` tools (for example `Read`, `Skill`, `CheckListTool`, `toolSearchTool`) run without asking. Everything else asks, including MCP tools and any other tool nobody has classified.

```yaml
agent:
  approval:
    timeout: 2m          # how long a request waits; at most 10m. Timing out counts as deny.
    ask-fallback: deny   # deny | allow — used when nobody can be asked (see below)
    rules:
      allow: [WebFetch, WebSearch]   # run without asking
      ask: [Read]                    # always ask, even if also allowed
      deny: [Task]                   # never run; not even offered to the model
```

| Property | Default | Description |
|---|---|---|
| `agent.approval.rules.allow` | empty | Tool names that run without asking. |
| `agent.approval.rules.ask` | empty | Tool names that always ask. |
| `agent.approval.rules.deny` | empty | Tool names that never run and are not offered to the model. |
| `agent.approval.timeout` | `2m` | How long an approval waits for an answer before it counts as denied. Must be greater than zero and at most `10m`; other values stop the application from starting. |
| `agent.approval.ask-fallback` | `deny` | What happens to a call that needs approval when nobody can be asked: `deny` or `allow`. Never applies to protected actions (below), which are always denied when nobody can be asked. |
| `agent.web.allowed-origins` | empty | Extra origins allowed to open JavaClaw's WebSockets, for a reverse proxy (see [Chat](#chat-built-in)). |

Rules match tool names exactly and are evaluated in the order deny, ask, allow; the first match wins. With no match, `READ_ONLY` tools run and everything else asks.

Some calls **always ask, whatever the rules say**, because they could turn the approval gate off or widen what the agent can reach:
- Adding or changing an MCP server (`addStdioMcpServer`, `addStreamableHttpMcpServer`).
- Writes that target the configuration file holding `agent.approval.*`, or any `application*.yaml|yml|properties` file.
- Shell commands or subagent prompts that mention those files or `agent.approval`.

The last check matches text: it is a tripwire for mistakes and casual prompt injection, not a sandbox, because a shell command can still build a file name at run time. That is why shell tools and subagents (`Task`) ask by default. When nobody can be asked, a protected action is always denied, even with `ask-fallback: allow`.

**Who can be asked.** The request is asked where the conversation is:
- **Web chat:** an approval card with **Allow once** and **Deny**, while at least one browser tab is showing that conversation. If the last tab disconnects while a request is waiting, the request is denied immediately. Waiting requests come back when you reconnect or switch back to the conversation.
- **Telegram, Discord, WhatsApp and Teams:** a message in the same chat, for example:

  ```
  JavaClaw wants to run WebFetch: {"url":"https://example.com"}
  Risk: NETWORK
  Reply /approve a1b2c3 or /deny a1b2c3 (expires in 2 minutes).
  ```

  Reply `/approve <id>` or `/deny <id>`; these replies never reach the model. JavaClaw then confirms the outcome in one short message, also when the request expires. Very long arguments are cut short in the message, which says so. Only the user each channel already accepts can answer (Teams: personal chats only, as for any message). An unknown, expired, already used or other chat's id changes nothing and gets a short reply saying so.
- **Everything else** (scheduled tasks and the Vaadin UI): nobody can be asked, so `ask-fallback` decides.

**Only JavaClaw's own pages can answer in the browser.** Every WebSocket JavaClaw serves refuses a handshake from any other origin, and one without an `Origin` header, so another site open in the same browser cannot see or answer approval requests. Behind a reverse proxy, add the proxy's origin to `agent.web.allowed-origins` (see [Chat](#chat-built-in)).

**Scheduled tasks report blocked tools.** A task cannot ask anyone, so with the default `ask-fallback: deny`, any tool it needs that is not `READ_ONLY` or allowed by a rule is refused. The task then ends in `awaiting_human_input`, and its result lists each blocked tool and why, for example `Write: not run because it needs approval and nobody can be asked here`. To let tasks run unattended, allow the tools they need with `agent.approval.rules.allow`, or set `ask-fallback: allow`.

A blocked task is not picked up again on its own, and JavaClaw has no way yet to resume it: re-running its job is refused because the task is no longer `todo`. After allowing the tool, create the task again, or open the task's conversation (`task-…`) in the web chat and ask the agent to finish; there you can approve the tool directly. The task file keeps its `awaiting_human_input` status either way.

Pending approvals are kept in memory, so restarting JavaClaw drops them.

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
