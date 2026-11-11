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

Before the agent runs a tool, it can pause and ask you where the request came from: in the web chat, or in the Telegram, Discord, WhatsApp or Teams chat you wrote in. You see the tool and its exact arguments, and you choose to allow it once or deny it. A denied call is not an error: the agent gets the denial and the reason as the tool's result and carries on without it.

Each call is decided in this order; the first match wins:

1. **Deny:** a tool in `agent.approval.deny` never runs, and is not even offered to the model.
2. **Protected path:** a call with any argument value that points into a protected path, or mentions a protected file name, always asks, even if the tool is in `allow`.
3. **Allow:** a tool in `agent.approval.allow` runs without asking.
4. **Ask:** everything else asks, including MCP tools and plugin tools.

When nobody can be asked, a call that would ask is denied.

The defaults live in `application.yaml`:

```yaml
agent:
  approval:
    timeout: 2m   # how long a request waits; at most 10m. Timing out counts as deny.
    allow: [Read, Skill, toolSearchTool, CheckListTool, listRecurringTasks, getText, waitForSelector,
            closeBrowser, takeScreenshot, Glob, Grep, ListDirectory, BashOutput, TodoWrite, MemoryView]
    deny: []
    protected-paths:
      - application*.yaml
      - application*.yml
      - application*.properties
      - workspace/conversations/
      - workspace/AGENT.md
      - workspace/INFO.md
      - workspace/skills/
      - workspace/agents/
```

| Property | Default | Description |
|---|---|---|
| `agent.approval.allow` | the list above | Tool names that run without asking, matched exactly. |
| `agent.approval.deny` | empty | Tool names that never run and are not offered to the model. |
| `agent.approval.protected-paths` | the list above | Paths and file names that always ask. The runtime config file (`application.private.yaml`) is always protected, even if you leave it out. |
| `agent.approval.timeout` | `2m` | How long an approval waits for an answer before it counts as denied. Must be greater than zero and at most `10m`; other values stop the application from starting. |
| `agent.web.allowed-origins` | empty | Extra origins allowed to open JavaClaw's WebSockets, for a reverse proxy (see [Chat](#chat-built-in)). |

A list you set in `application.private.yaml` replaces the default list instead of adding to it, so to allow one more tool, repeat the defaults you want to keep. `allow` and `deny` hold tool names only: no tool or argument names are built into JavaClaw's approval code.

**Protected paths.** These paths hold what keeps the gate in place (the configuration) and what the agent is told and remembers (its instructions, skills, subagents and conversation history). Every string value in a call's arguments is checked, at any depth and whatever the argument is called, in two ways:
- **As a path.** Relative values resolve against the working directory, as the file tools resolve them, with `..` and symlinks resolved. So `Read` of `workspace/AGENT.md` asks even though `Read` is allowed.
- **As text.** A value that mentions a protected file name or directory also asks, for example a shell command that names `application.yaml`.

An entry without a `/` is a file name anywhere, where `*` matches any run of letters, digits, `.`, `_` and `-`. An entry ending in `/` covers everything inside that directory. Any other entry is one file. `workspace/` means the configured `agent.workspace`; other relative entries are relative to the working directory.

The text check is a tripwire for mistakes and casual prompt injection, not a sandbox: a shell command can still build a file name at run time. That is why shell tools and subagents (`Task`) are not in `allow` by default.

**Who can be asked.** The request is asked where the conversation is:
- **Web chat:** an approval card with the request's arguments, **Allow once** and **Deny**, shown while at least one browser tab is showing that conversation. If the last tab disconnects while a request is waiting, the request is denied immediately. Waiting requests come back when you reconnect or switch back to the conversation.
- **Telegram, Discord, WhatsApp and Teams:** a message in the same chat, for example:

  ```
  JavaClaw wants to run `Write`
    filePath: workspace/notes/todo.md
    content: - buy milk
      - call the bank
  Expires at 12:05
  Reply /approve_a1b2c3 or /deny_a1b2c3
  ```

  The arguments are indented `key: value` lines, the same for every tool. A call that touches a protected path gets a line saying which path. The expiry is a clock time in the instance's time zone.

  Each message stays under the channel's limit after escaping: Telegram 4096, Discord 2000, and Teams and WhatsApp 4000 characters. Values are cut at 300 characters, and arguments that still don't fit are left out. When anything is cut, the message says "Not shown in full. Approve from the web chat to see everything."

  Reply `/approve_<id>` or `/deny_<id>` (on Telegram, one tap on the command; `/approve <id>` works too). These replies never reach the model. Once the request is answered or expires, Telegram, Discord and Teams edit the request to show the outcome in place of the commands; WhatsApp, which can't edit messages, gets a short reply. Each channel's own formatting is used, and everything a tool call supplies is escaped for it. Only the user each channel already accepts can answer. An unknown, expired, already used or other chat's id changes nothing and gets a short reply saying so.

  Approvals are asked only where nobody else can read them: on Telegram in a private chat with the bot, on Discord in a DM, and on Teams in a personal chat. In a Telegram group or a Discord server channel, a call that needs approval is denied.
- **Everything else** (scheduled tasks and the Vaadin UI): nobody can be asked, so a call that needs approval is denied.

**Only JavaClaw's own pages can answer in the browser.** Every WebSocket JavaClaw serves refuses a handshake from any other origin, and one without an `Origin` header, so another site open in the same browser cannot see or answer approval requests. Behind a reverse proxy, add the proxy's origin to `agent.web.allowed-origins` (see [Chat](#chat-built-in)).

**Scheduled tasks report blocked tools.** A task cannot ask anyone, so any tool it needs that is not in `allow`, or that touches a protected path, is refused. The task then ends in `awaiting_human_input`, and its result lists each blocked tool and why, for example `Write: not run because it needs approval and nobody can be asked here`. To let tasks run unattended, add the tools they need to `agent.approval.allow`.

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
