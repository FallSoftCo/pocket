# Self-service terminal forks

Status: implemented candidate, not enabled on the production environments. See the [qualification receipt](qualification/terminal-fork-20261010.json). Native goal ownership and app-owned dynamic tools are deliberately unsupported in this first version.

A session can prepare its own native successor on an owner-registered environment, then release its primary task when its current turn finishes. The original remains available as history or for a **named dependency role**. This supports incomplete dependency migration without telling two workers to do the same primary work. It does not move running processes, browser ownership, live forms, authentication, or an entire writable Codex profile.

## Agent workflow

Use the `terminal_fork` MCP tool, or `node scripts/terminal-fork.mjs`. The source must use its actual `CODEX_THREAD_ID`, a stable migration ID and an explicit target registration. The CLI obtains its source ID from that environment variable; the MCP tool checks it when available. This is owner-authorized cooperative tooling, not a security boundary against a local account owner.

1. Reconcile files, background jobs, children and external receipts. Unknown delivery must be settled first. Identify the successor's primary scope and any source-retained dependency scope. Provision required destination files/tools through their existing owners.
2. `request` persists that inventory and checkpoint. It creates no model turn or destination worker. Repeating the same ID/checkpoint returns the same request; reusing the ID for changed content fails.
3. Make `release` the final tool action, then finish the source turn. The controller waits for completion. Interrupted, failed, cancelled, archived or usage-stopped work is not treated as permission to continue.
4. The controller makes a native fork at that completed turn, snapshots its exact transitive rollout ancestry and checks the prepared destination. Only then does it submit one continuation with the checkpoint and split of responsibilities.
5. `status` observes stored receipts. `cancel` is allowed before native fork creation. An uncertain acknowledgement is retained for reconciliation; it is never handled by creating another worker or replaying the original request.

Example checkpoint file for the CLI:

```json
{
  "primaryScope": "Finish the verified implementation and tests on the destination",
  "sourceScope": "Observe the already-running source packaging job and report its receipt",
  "handoff": "The implementation is saved. Inspect the current files and receipts before acting. Do not restart packaging or repeat any external submission.",
  "workspaceFiles": ["server/example.mjs", "tests/example.test.mjs"],
  "dependencies": [{"id":"existing-packaging-job","state":"source-retained","description":"Existing source job retains ownership until it settles"}]
}
```

```sh
node scripts/terminal-fork.mjs request --id transfer-my-work-001 --to build-host --cwd /workspace/project --checkpoint /private/checkpoint.json
node scripts/terminal-fork.mjs release --id transfer-my-work-001
node scripts/terminal-fork.mjs status --id transfer-my-work-001
```

Workspace evidence hashes explicitly listed files; it is not a complete repository backup. Supply a complete task-relevant inventory and use existing verified repository/artifact transfers. Empty evidence does not establish that a coding workspace was copied.

## Native context and displayed history

A fork rollout alone cannot recreate its paginated lineage. Copying a live profile's SQLite index retains invalid absolute paths and can import unrelated work. This implementation transfers only checksum-verified immutable rollout prefixes and the fork; the destination's native scanner rebuilds its index. Original opaque native context remains byte-for-byte intact. Conflicting destination history fails without overwrite. An existing ancestor may have an identical prefix plus later work; an existing destination fork may not.

Stock Codex can retain inherited context for the model while omitting inherited turns from displayed history. NextComp separately preserves sanitized public history for its normal recent-first history API. This display archive is not injected into model context. Imported ancestor records and source transport forks are hidden from the ordinary catalog; the successor is discoverable without waiting for its first response.

## Owner installation

The backend is disabled when its private data directory has no `terminal-forks.json`. Both source and destination require this reviewed source and their actual, exclusive Codex profile path. The source also needs named private SSH registrations:

```json
{
  "environmentId": "source-env",
  "codeHome": "/private/codex-profile",
  "targets": {
    "build-host": {
      "sshHost": "trusted-ssh-alias",
      "helper": "/opt/nextcomp/scripts/terminal-fork-target.mjs",
      "data": "/private/nextcomp-data",
      "profile": "/private/destination-codex-profile",
      "url": "http://127.0.0.1:18880"
    }
  }
}
```

The destination needs its own `environmentId`, `codeHome` and optional outbound targets. Registry values are set by the environment owner, not supplied in the model's tool request. The SSH helper reads the destination's existing local owner token; credentials, auth caches and SQLite databases are not transferred. Rollouts/checkpoints can contain private work and require private storage and retention. Do not put registrations, bundles or private receipts in public source.

Install the caller, helper, backend routes and matching MCP tooling as one reviewed release, preserving live sessions and pairing. Qualify the actual runtime pair before enabling it. No production service is restarted or target registered automatically by this candidate. Existing native MCP processes may need their owning release to discover the added tool; the CLI remains an alternative.

## Boundaries

- The signed-in ChatGPT account, effective model/provider, approval/sandbox settings, selected exposed execution settings and MCP tool schemas/auth inventory must match. Checks run again before activation. This does not authorize changing permissions, models or account settings to force a match.
- App-owned dynamic tools need a matching destination controller. Active native goals need a separate budget and autonomous-admission handover. These cases are blocked, not reconstructed silently.
- NextComp source admission and recovery are gated after release; a prepared/uncertain destination cannot accept NextComp replies. Independent terminals and other controllers are not globally fenced. This terminal fork requires cooperative ownership and explicit source scope; it is not the exclusive rolling migration controller described in [the migration design](ROLLING-MIGRATION.md).
- The source and target have different native IDs and environment identities. Android logical-work unification, automatic draft retargeting, catch-up/notification migration and arbitrary child-controller portability are not implemented here. Existing receipts and drafts stay on their original environment.
- Native activation acceptance is not task completion. No unknown external action or original request is replayed. Rollback after destination execution requires a new settled handover.
- Qualification covers isolated stock 0.162.0 profiles and synthetic provider context carry. It does not certify arbitrary legacy/compacted versions, paid-provider cross-host continuation, or an actual migrated production task.

Pending requests use bounded deterministic checks, not model check-ins. They do not enroll unrelated or historical sessions. Lost native fork acknowledgements, unknown destination activation and uncertain external dependencies remain explicit holds.

To reproduce the native qualification on an authorized build host, set absolute `TERMINAL_FORK_CODEX_BINARY`, `TERMINAL_FORK_AUTH_SOURCE` and `TERMINAL_FORK_QA_ROOT`, then run `node scripts/qualify-terminal-fork.mjs`. It creates isolated private profiles, holds the first synthetic response to test an active source boundary, verifies context/history on the successor, stops its own runtimes and removes its auth copies. The fixture uses a local synthetic provider, not paid inference. Keep its raw profile/log/receipt output private; the committed receipt contains only bounded qualification claims.
