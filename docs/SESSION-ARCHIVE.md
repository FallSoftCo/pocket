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
It follows every native current/archived catalog page, including delegated sessions,
and every displayed catalog page before changing anything. This also covers child
sessions that native list versions omit despite supporting metadata reads. It protects explicitly
provided session IDs and their known ancestors, and uses actual work timestamps rather
than catalog-fetch or metadata-maintenance timestamps. `updatedAt` is a fallback
only when the runtime supplies no work timestamp. The existing archive route rechecks metadata,
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
reconcile the original receipt against native archived inventory rather than
repeating the mutation. A positively confirmed archive can continue the sweep;
an unconfirmed outcome still stops it. Archiving a parent may archive its children.
The sweep preserves that native result and does not resend those child archives.
Drafts stored on offline clients cannot be inspected by this server-side tool;
archiving preserves them for restoration. This is an explicit cleanup tool,
**not an enabled recurring scheduler**.

The November 7 owner-requested cleanup archived 1,370 old workstation sessions
and 75 Mac sessions. Subsequent catalogs contained none of those archived IDs.
Current/loaded work and pending requests were protected. No histories were deleted.

## November 7 coverage repair

The initial sweep missed delegated and cached sessions. The corrected backend is
live on both workstation and Mac. Reading native archived inventory reconciles
stale cache membership; this is not a cosmetic age filter. Follow-up cleanup made
430 additional acknowledged workstation archive calls and one Mac archive call;
native descendant archives were separately reconciled without replay. Final full
normal-list reads returned 157 workstation sessions and 8 Mac sessions, with no
work older than 30 days and no further page cursor. Histories remain in Archived.

The gateway release passed 688 backend checks. The final cleanup runner passed
an HTTP/native-transport integration journey covering pagination, metadata churn,
cached and native-list-omitted children, and an archive becoming canonical before
its later read fails. No native worker restart or new APK was required. Physical
phone rendering of the refreshed list has not been independently inspected.
