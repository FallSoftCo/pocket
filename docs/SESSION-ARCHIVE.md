# Archiving sessions

Swipe a session card left in Android to reveal **Archive**, then tap the action.
Swiping alone does not archive or open the conversation. Screen readers expose a
**Show archive action** action. Web cards have an Archive button. Use **Find →
Archived** to find archived work and **Restore** to return it to the current list.

This uses Codex's native archive operation, on the session's original host. It
retains history and local drafts, disables that session's notification watch,
and holds queued messages. Running work must be stopped before manual archive.
Restoring does not automatically send a draft or restart a held queue.

## Owner-authorized cleanup

`scripts/archive-inactive-sessions.mjs` previews sessions inactive for 30 days.
It follows every catalog page before changing anything, protects explicitly
provided session IDs and their known ancestors, and uses work timestamps rather
than catalog-fetch timestamps. The existing archive route rechecks metadata,
current activity, queued/uncertain messages and pending attention before applying
an age-conditioned archive. A current activity timestamp wins over an old creation
date. No histories are scanned, model calls made, or worker continuations sent.

Run from the backend host with its existing local owner configuration:

```sh
POCKET_DATA=/path/to/private/data \
ARCHIVE_RECEIPT=/path/to/private/new-preview.json \
node scripts/archive-inactive-sessions.mjs
```

Only add `--apply` for an authorized cleanup and use a new receipt path. An
optional `ARCHIVE_PROTECTED_FILE` contains a JSON array of session IDs to retain,
for example loaded sessions or known drafts. Unknown outcomes stop the sweep;
inspect the original receipt and native archive state rather than rerunning it.
Drafts stored on offline clients cannot be inspected by this server-side tool;
archiving preserves them for restoration. This is an explicit cleanup tool,
**not an enabled recurring scheduler**.

The November 7 owner-requested cleanup archived 1,370 old workstation sessions
and 75 Mac sessions. Subsequent catalogs contained none of those archived IDs.
Current/loaded work and pending requests were protected. No histories were deleted.
