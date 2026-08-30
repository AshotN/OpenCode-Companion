# Changelog

## [Unreleased]

<p><strong>Added</strong></p>
<ul>
  <li>Add the Reworked Terminal as an optional embedded TUI engine on IntelliJ Platform 2026.2 and newer, with feature parity for clipboard integration, file drag and drop, and file navigation.</li>
</ul>

<p><strong>Breaking Changes</strong></p>
<ul>
  <li>Drop support for IntelliJ Platform versions earlier than 2025.3.</li>
</ul>

<p><strong>Changed</strong></p>
<ul>
  <li>Standardize generated IDE file references on project-relative <code>./path/to/File.kt:line</code> syntax.</li>
</ul>

<p><strong>Fixed</strong></p>
<ul>
  <li>Improve embedded terminal cleanup and restart behavior when startup fails, a session terminates, or the terminal engine changes.</li>
  <li>Send selected folders as <code>@path/</code> and multi-line editor selections as <code>@path#Lstart-end</code> so OpenCode recognizes them correctly.</li>
</ul>

## [2.2.0] - 2026-06-25

<p><strong>Fixed</strong></p>
<ul>
  <li>Copy selected text to the IDE clipboard in the embedded TUI by intercepting OSC 52 terminal escape sequences.</li>
  <li>Support pasting screenshots and other raw clipboard image data into the embedded TUI by saving clipboard images as temporary PNG files.</li>
  <li>Paste dropped and copied file paths as raw OpenCode TUI paths instead of shell-quoted arguments, improving Windows path compatibility.</li>
</ul>

## [2.1.1] - 2026-06-22

<p><strong>Added</strong></p>
<ul>
  <li>Support dragging, dropping, and pasting files into the embedded TUI.</li>
  <li>A warning banner when JetBrains MCP is not configured.</li>
</ul>

<p><strong>Fixed</strong></p>
<ul>
  <li>Remove internal IntelliJ Platform API usage from JetBrains MCP detection while preserving the MCP warning banner behavior.</li>
</ul>

## [2.1.0] - 2026-06-20

<p><strong>Added</strong></p>
<ul>
  <li>Support dragging, dropping, and pasting files into the embedded TUI.</li>
  <li>A warning banner when JetBrains MCP is not configured.</li>
</ul>

## [2.0.0] - 2026-06-17

<p><strong>Breaking Changes</strong></p>
<ul>
  <li>Drop support for OpenCode versions earlier than 1.16.0.</li>
</ul>

<p><strong>Fixed</strong></p>
<ul>
  <li>Improve responsiveness when working with large pending file and session lists by avoiding unnecessary list model rebuilds and expensive HTML renderer work.</li>
  <li>Improve embedded terminal performance by avoiding filesystem stat calls while resolving local Markdown and path hyperlinks.</li>
</ul>

## [1.4.0] - 2026-06-09

<p><strong>Changed</strong></p>
<ul>
  <li>Support OpenCode 1.16+ message-scoped diffs by aggregating per-message diff snapshots when session-level diffs are empty, while keeping compatibility with older session-level diff events.</li>
  <li>Limit background message-summary file-count loading to a small recent batch to avoid excessive API requests on large session histories.</li>
</ul>

## [1.3.2] - 2026-06-04

<p><strong>Fixed</strong></p>
<ul>
  <li>Improve plugin performance when using OpenCode server authentication passwords.</li>
  <li>Remove internal IntelliJ Platform API usage from OpenCode Relay prompt metadata generation by injecting the plugin version at build time.</li>
</ul>

## [1.3.1] - 2026-06-02

<p><strong>Added</strong></p>
<ul>
  <li>Allow the success notifications shown after sending file, folder, or selection references to OpenCode to be disabled via the IDE notification's <code>Do not ask again</code> option.</li>
  <li>Add a GitHub Actions workflow that builds the plugin ZIP and uploads it as a CI artifact for pushes to <code>master</code>.</li>
</ul>

<p><strong>Changed</strong></p>
<ul>
  <li>Mark non-release builds with clearer versions: CI builds include the short commit hash and local builds include a <code>-local</code> suffix, while tagged release builds keep the published plugin version.</li>
  <li>Refine the injected IDE guidance to prefer relative file references instead of full paths.</li>
</ul>

<p><strong>Fixed</strong></p>
<ul>
  <li>Improve Windows support by launching OpenCode commands through <code>call</code> and normalizing Windows paths case-insensitively across prompt injection, diff previews, turn patch scopes, and session diff state.</li>
  <li>Exclude trailing punctuation such as periods, commas, colons, and semicolons from terminal file hyperlinks, including line references like <code>src/File.kt:42.</code> and <code>note.md#L2.</code>.</li>
</ul>

## [1.3.0] - 2026-05-16

<p><strong>Added</strong></p>
<ul>
  <li>Inject JetBrains IDE guidance into OpenCode's system prompt for plugin-launched servers, with a settings toggle to disable it.</li>
  <li>Enable navigation to the specified file and line by clicking on line references within the embedded TUI.</li>
</ul>

## [1.2.0] - 2026-05-14

<p><strong>Fixed</strong></p>
<ul>
  <li>Switch to <code>/global/event</code> stream for SSE updates, fixing a regression in some OpenCode versions (fixed upstream in OpenCode 1.14.50) and restoring VCS refresh after LLM file edits.</li>
</ul>

## [1.1.0] - 2026-05-04

<p><strong>Added</strong></p>
<ul>
  <li>Alert users when OpenCode requests permission by requesting IDE attention while the project window is inactive and showing a notification when the permission UI is not visible.</li>
  <li>Brave Mode for automatically accepting OpenCode permission requests, with toggles in the tool window toolbar and settings UI.</li>
</ul>

<p><strong>Fixed</strong></p>
<ul>
  <li>Use <code>session.status</code> as the authoritative session state signal, dropping deprecated <code>session.idle</code> handling and fixing cases where the session list could stay stuck on <code>running...</code>.</li>
  <li>Refresh MCP server connection status immediately after connect and disconnect actions.</li>
  <li><code>RejectedExecutionException</code> when replacing the current project in the same IDE window while late OpenCode server callbacks are still being delivered.</li>
</ul>

## [1.0.0] - 2026-04-24

<p><strong>Added</strong></p>
<ul>
  <li>OpenCode server authentication support and expose the related IDE settings.</li>
  <li>OpenCode env variables support.</li>
  <li>Capture Ctrl-Z (<a href="https://github.com/ashotn/OpenCode-Companion/issues/3">#3</a>).</li>
</ul>

<p><strong>Breaking Changes</strong></p>
<ul>
  <li>Drop support for OpenCode versions earlier than 1.4.0.</li>
</ul>

<p><strong>Changed</strong></p>
<ul>
  <li>Improve compatibility with newer OpenCode 1.4+ patch payloads by updating patch parsing and diff handling.</li>
</ul>

## [0.4.1] - 2026-04-07

<p><strong>Fixed</strong></p>
<ul>
  <li>Force HTTP/1.1 for OpenCode 1.4 server communication to avoid HTTP/2 upgrade issues.</li>
</ul>
