# OpenCode Relay for JetBrains IDEs

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/30674-opencode-relay?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/30674-opencode-relay)

Integrates the [OpenCode](https://opencode.ai) AI coding assistant into IntelliJ-based IDEs.
<table>
  <tr>
    <td width="33%">
      <img height="300" src="https://github.com/user-attachments/assets/444bb0d1-2f13-495a-9bd2-b0b8fb54b4a5" alt="Screenshot 1" />
      <p align="center"><b>Inline diffs</b></p>
    </td>
    <td width="33%">
      <img height="300" src="https://github.com/user-attachments/assets/1eb25eac-06aa-4f67-90eb-45c911627735" alt="Screenshot 2" />
      <p align="center"><b>Detailed 3 panel diff</b></p>
    </td>
    <td width="33%">
      <img height="300" src="https://github.com/user-attachments/assets/165dfcd4-592b-46fa-9015-37841a3fe070" alt="Screenshot 3" />
      <p align="center"><b>Customizable Settings</b></p>
    </td>
  </tr>
</table>

## Features

### Server lifecycle management

The plugin can launch, monitor, and stop the OpenCode server for you.

- **Start / Stop** — Start the server from the tool window with a single click. The plugin spawns
  `opencode serve --port <port>` in the project directory.
- **Auto-detect a running server** — If OpenCode is already running on the configured port (e.g. started from the
  terminal), the plugin connects to it automatically without taking ownership of the process.

Once the server is running the plugin connects over SSE (Server-Sent Events) to receive real-time events.

### Session list

The **Session changes** panel lists all root sessions. Each row shows:

- Session title and a short description
- A spinner while the session is actively running
- The number of files being tracked in that session

Clicking a session row makes it the active session; the file list updates to show only that session's changes.

### Modified-files list

The **Files** pane shows every file touched by the selected session:

| State    | Visual                     |
|----------|----------------------------|
| Modified | Bold filename              |
| Added    | Green filename             |
| Deleted  | Red strikethrough filename |

Interact with the list:

- **Double-click** a file — opens a diff view (see below)
- **Right-click** — context menu with _Jump to Source_ and _Open Diff_
- **F4** (or IDE's _Jump to Source_ shortcut) — navigate directly to the file in the editor

### Inline diff in the editor

While a session is active, changed lines are highlighted inlined in the editor:

- **Added lines** — green background highlight
- **Removed lines** — rendered as red inlay blocks above the insertion point, showing the original content that was
  deleted

_Due to OpenCode limitations, only the last write will be shown._

This feature can be toggled in settings.

### Diff view of modified files

Double-clicking a file in the modified-files list opens a standard JetBrains **diff editor tab** comparing:

- **Left side** — the session baseline (the content before the agent started making changes)
- **Middle** - The current file on disk
- **Right side** — The changes the AI had made to the file

### Send selection to TUI

Share code from the editor directly with OpenCode's prompt input:

- Select any code in the editor
- Use **Send Selection to OpenCode** from the right-click context menu
- The selection is sent as a fenced code block (with filename and line range) to the active TUI session's prompt input
  buffer

The text is appended — not submitted — so you retain full control before pressing Enter in the TUI.

You can also send file and folder paths to the TUI by right-clicking them in the project view.

### Open in browser

The **Open in Browser** toolbar button opens the OpenCode web UI in your default browser.

### New session in TUI

The **New Session** toolbar button creates a new OpenCode session and immediately switches the active TUI instance to
it.

### Permission prompts

When any agent requests permission, a prompt appears at the bottom of the panel with three choices:

- **Allow** — permit this one request
- **Allow Always** — permit this type of request for the rest of the session
- **Reject** — deny the request

Responses are sent back to the server immediately.

## Requirements

- A JetBrains IDE based on IntelliJ Platform 2025.3 or later
- [OpenCode CLI](https://opencode.ai/docs) version `1.16.0+` installed and on `PATH` (or the path configured in
  settings)

## JetBrains MCP

OpenCode Relay will warn you when JetBrains MCP is disabled or when a running OpenCode server is not connected to it.

**It is highly recommended to enable JetBrains MCP for the best experience.**

To enable JetBrains MCP:

1. Open **Settings | Tools | MCP Server** in your JetBrains IDE and enable the server.
2. Note the MCP server URL or port shown in the IDE settings.
3. Add a `jetbrains` MCP server to your `opencode.json`.
4. Restart the OpenCode server so it reloads the config.

Basic `opencode.json` example:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jetbrains": {
      "type": "remote",
      "url": "http://localhost:64342/sse",
      "enabled": true
    }
  }
}
```

Edit `url` to match the host, port, and path from **Settings | Tools | MCP Server**. If your IDE shows a different port,
replace `64342` with that port.

### MCP Tips

* You may wish to disable some of the tools provided by the MCP - Settings | Tools | MCP Server | Exposed Tools
* You may also want to provide some guidance to the agent in your AGENTS.MD file to use any of the tools you have
  enabled.
* Models are not likely to use the tools without some guidance.