# NextComp context control and independent workers

Investigated 2026-10-06. This is a design investigation, not a deployed context editor. No running session was forked, compacted, interrupted or modified; no model call or subscription purchase was made.

## Answer

Yes: save reusable context packages, branch provider conversations at supported boundaries, and run independent workers communicating through Hollywood. Much of the usefulness associated with subagents—separate histories, focused assignments, different models/tools, parallel work, selective result return—is an orchestration choice. A conventional parent/child relationship is not essential.

However, a saved context is not a frozen running mind or a machine snapshot. A context package plus communication does not execute anything by itself. Independent workers still need model requests, a tool loop, cancellation, permissions, durable delivery, and either isolation or coordinated access to shared resources. Different workers can disagree even when given identical context.

## The mental model

A worker has three separate parts:

1. **Working context:** bounded model input assembled for the next inference, including instructions, selected messages, tool definitions/results, images and any supported opaque continuation items. New outputs also need room. A long stored transcript is not necessarily the model's current input.
2. **Session record and harness:** durable history, identity, model/configuration, pending requests, queues, compaction markers and tool-loop bookkeeping. Resume reloads that record; fork creates a new lineage. Compaction changes the active representation without necessarily deleting the original evidence.
3. **World:** current files, git changes, terminals/processes, browser state, databases and remote services. History can describe old world state while the world has changed.

OpenAI documents stateless requests with explicit replay as well as server-managed continuation through response IDs. Its context window includes the input and generated tokens, including reasoning-token budget. Thus saving a transcript and saving the active input are related but distinct operations. [Conversation state](https://developers.openai.com/api/docs/guides/conversation-state).

Claude Code explicitly loads history, files/tool output and instructions into context, and manages context by clearing older tool results and summarizing. Early instructions can be lost. It also distinguishes a resumed/forked conversation from the files currently present on a branch. [How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works).

## What can actually be saved or copied

| State | Portable/editable? | NextComp treatment |
| --- | --- | --- |
| User messages, public responses, selected tool results, attachments | Generally yes, subject to available original data | Preserve source IDs, roles, phases, content hashes and timestamps; create edited derivatives without changing originals |
| Instructions and tools | Explicit configuration can be saved; built-in/managed instructions may not be completely exposed | Show owner-supplied instructions separately from effective provider configuration; don't promise a complete rendered prompt |
| Tool calls/results | Stored observations can be replayed as evidence; live tool resources cannot simply be copied | Preserve complete call/result pairs when using native protocol formats; label transformed text as a handoff |
| Provider session/history | Provider-native resume/fork within supported backend | Keep opaque IDs and lineage; use the provider API instead of rewriting its private storage |
| Encrypted reasoning/compaction items | Some provider-supported items can be replayed intact; not readable or freely editable | Opaque, backend-bound handles; never render as editable thoughts or translate to another provider |
| KV/prompt caches | Provider-managed optimization; no documented downloadable KV snapshot interface in the examined APIs | Report observed cache usage, never offer “save cache” as durable memory |
| Hidden/internal model state and inaccessible reasoning | No documented arbitrary clone/edit interface | Exclude from portability claims |
| Git/files/artifacts | Separate snapshots are possible | Pin commit, dirty patch and artifact hashes, and specify whether the worker uses current files or a captured copy |
| Process/browser/remote state | Not restored by conversation fork | Independent process/lease allocation; inspect current state; external side effects remain external |

OpenAI's prompt cache stores reusable KV tensors for an eligible unchanged prefix. Matching content/settings matters; a session does not guarantee reuse. Cache routing and retention are not a user-exportable execution snapshot. [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching).

Anthropic caches a prefix of tools, system and messages with limited lifetime. This reduces repeated processing; it does not replace storing the actual content or restoring an environment. [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching).

OpenAI exposes opaque encrypted reasoning items for supported continuation. These preserve provider-specific reasoning state without revealing the underlying reasoning. A public transcript alone is therefore not a lossless substitute for every native continuation. [Reasoning](https://developers.openai.com/api/docs/guides/reasoning).

OpenAI compaction can produce an encrypted compaction item. Keep the canonical returned context and item provenance rather than interpreting it as an editable natural-language summary. Claude compaction and OpenAI Responses compaction must not be assumed to have identical formats or semantics. [Compaction](https://developers.openai.com/api/docs/guides/compaction).

## Verified provider branching

**Codex:** official app-server documentation provides `thread/read`, `thread/fork`, `lastTurnId`, `forkedFromId`, `thread/compact/start` and usage/events. `thread/inject_items` appends raw Responses items; it is not a general replace/edit-context endpoint. `thread/rollback` is deprecated. Do not build the normal UX around destructive rollback. [Codex app-server](https://developers.openai.com/codex/app-server).

The installed `codex-cli 0.160.1` generated schema was inspected locally: `schema/v2/ThreadForkParams.json` says `lastTurnId` is inclusive and the referenced turn **cannot be in progress**. This validates completed-turn branching here; it does not establish arbitrary item-level or mid-inference branching. Generated schemas are evidence of the installed binary, not a guarantee for older phone or Losangelex builds.

**Claude:** the official session guide supports `resume` and `forkSession: true` on `query()`. Sessions persist conversations, not files. The former TypeScript V2 session API was removed in SDK 0.3.142; target the current `query()` interface. [Sessions](https://code.claude.com/docs/en/agent-sdk/sessions).

Anthropic's session-browser cookbook documents offline `fork_session(..., up_to_message_id=...)`, then a separate resume to execute. The previously downloaded official TypeScript package 0.3.291 declares `forkSession({upToMessageId})` and `resumeSessionAt`; its declarations say forks start without copied file-undo history. A selected message is a transcript boundary, not a cloned running tool/inference. [Session-browser cookbook](https://platform.claude.com/cookbook/claude-agent-sdk-05-building-a-session-browser).

An offline synthetic probe against official SDK 0.3.291 successfully forked at the selected user-message UUID, remapped fork IDs, and preserved the original history. `claude-fork-probe.mjs` and `claude-fork-probe-result.json` record this proof; it made zero model calls and did not test compacted transcripts.

Compacted Claude transcripts need specific qualification: an open upstream report describes stale preserved-message UUID references in standalone file forks, reproduced on 0.3.274/0.3.280. This is an issue report, not a verified failure of the current 0.3.291 package. Test source-versus-fork reconstructed history and compaction boundaries before enabling that path. [Upstream issue 472](https://github.com/anthropics/claude-agent-sdk-typescript/issues/472).

Claude file checkpointing separately covers Write/Edit/NotebookEdit, with documented exclusions including Bash, most subagent edits and remote resources. Rewinding files leaves the conversation unchanged. Git/worktree isolation remains necessary for broader branch reproducibility. [File checkpointing](https://code.claude.com/docs/en/agent-sdk/file-checkpointing).

Claude itself now documents conversation-fork subagents and ordinary fresh-context subagents. Forks inherit conversation/configuration and return results while keeping their own tool traffic separate; worktree isolation is a separate option. This supports the user's intuition that context inheritance and hierarchy are distinct implementation choices. [Subagents](https://code.claude.com/docs/en/sub-agents).

## Existing local infrastructure

- `/home/ai/Development/hollywood/README.md`: persistent HTTP/SQLite room messages, direct delivery and per-agent cursors. Hollywood is a coordination service, not an agent execution engine.
- `/home/ai/Development/losangelex/docs/experimental-hollywood-quickstart.md`: `losangelex team` creates independent app-server-hosted threads, assigns durable names, attaches them to a room and starts assigned turns. This already implements the independent-worker shape. Native runtime integration can turn focused inbound room traffic into model-visible context. The quickstart explicitly leaves release compatibility and complete external-message semantics unguaranteed.
- NextComp `server/history.mjs` and `timeline.mjs`: original/paged history and display projection. **Display rows are bounded and lossy**; do not export them as lossless provider context.
- NextComp `server/index.mjs`: authenticated pairing boundary, provider routing, persistent outgoing IDs, ambiguous-delivery handling, steer/queue/interrupt and approval/question handling. Reuse these semantics for worker commands.
- NextComp `server/losangelex.mjs`, Android `BackendApp.kt`/`LosangelexScreen.kt`: separate backend selection and team/direct modes. Context controls belong to the selected backend, rather than silently converting Codex sessions into Losangelex sessions.
- Recovery update 2026-10-08: `/home/ai/Development/computer-use-broker` now supplies the canonical shared-input broker, durable task service and an isolated gallery executor. Workers must persist stable admission request IDs and queued tickets, resume the same operation after disconnection, and respect quarantine and revision CAS. `browser-script-run.mjs` manages ad-hoc scripts; `BrowserReadClient` manages fixed owned DOM snapshots. Context forks must not inherit a parent's live browser lease or desktop ownership. Unknown writes remain fenced until independent reconciliation; no worker receives administrator credentials.

## Recommended NextComp architecture (proposal)

Use an immutable evidence store plus versioned **context manifests**. A manifest identifies backend/version, source thread and safe boundary, selected content references/hashes, owner instructions, explicit omissions, model/tool/permission choices, compaction references, workspace revision and artifact pins. Provider-specific opaque references remain private and backend-bound. Saving does not require launching a worker.

Provide two distinct launch paths:

1. **Branch conversation here:** provider-native fork at a supported stable boundary. Preserve native state and lineage. Name the branch, show workspace choice, then continue it independently. Disable unsupported points and explain the nearest eligible boundary. Preserve the original; do not rewind shared files.
2. **Start worker from selected context:** render an edited manifest into an explicit handoff in a fresh thread. Allow included facts/files/messages to be inspected. Label it a reconstructed handoff, because it does not preserve inaccessible/provider-specific state. This is the portable cross-backend path.

“Arbitrary point” in the UI can mean any recorded evidence point for a manifest. Exact native continuation remains constrained by the provider. Mid-tool points should retain pending-call provenance and require settling or explicitly abandoning the pending operation before execution; do not replay side effects automatically.

Compaction provenance should record source boundary, event/item ID, provider/version, available summary or opaque handle, hashes of covered evidence and known omissions. Original history remains available when the backend retains it. Never promise that every compacted state can be fully reconstructed from public display history.

Workers are first-class sessions with lifecycle, model, permissions, workspace/lease, task/room membership and inbox cursors. Hollywood messages carry source worker, task, reply target, durable message ID and delivery state. Delivery acknowledgement is distinct from a worker consuming the message. Feed only scoped, relevant messages into its input; summaries and mailbox pointers prevent room chatter from consuming every context window. Agent-supplied text does not gain authority over user instructions or permissions.

Offer **Context**, **Save snapshot**, **Branch here**, and **Start worker** on a conversation. Show context usage as reported/estimated, included material, omissions and compaction history; don't claim a complete hidden-context inspector. A branch tree and shared task view expose ownership, progress, conflicts and final outputs. The automatic work record links outputs and later revisions without requiring manual journaling.

Subagent benefits recreated by this design: focused context, optional inheritance, selective result return, independent models/tools, parallel work and explicit routing. Work still required: independent execution, resource isolation, cancellation, admission/rate limits, durable delivery and results integration. A hierarchy is optional; these operational responsibilities are not.

## Concrete implementation sequence

1. Add a versioned manifest schema, content-addressed evidence references and lineage tables. Qualify round-trip reconstruction and privacy; no launch on save.
2. Add Codex completed-turn branching using runtime-version/capability checks, idempotent command receipts and an independent workspace policy. Test compaction and attachment history, branch history exclusion, source preservation and restart recovery.
3. Add edited-context handoff previews and fresh-worker launch. Preserve call/result pairing or deliberately convert to cited text evidence; validate budget/truncation explicitly.
4. Attach workers through existing Losangelex team/room semantics. Reuse scheduler leases and outgoing unknown-delivery recovery. Test isolation, independent cancellation, direct-message visibility and conversation provenance.
5. Add Claude-native message branching with current SDK qualification once its separate backend adapter is implemented. Verify compaction UUID handling and enforce user-owned authentication boundaries already researched.

The investigation does not authorize replacing the running backend, patching native transcripts, cloning external sessions, or launching a competing worker/release. This document and the generated installed-Codex schema provide a concrete implementation contract.
