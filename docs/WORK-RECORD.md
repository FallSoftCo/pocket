# Automatic work record

NextComp records ordinary requests, agent responses and selected tool results without asking the user to rate, tag or journal their work. It covers NextComp development and other projects equally, using actual conversation/workspace provenance. Open **Settings → Work record** to inspect the result.

The record is empirical evidence of observed activity. Completed means the backend reported a completed turn. Agent output remains agent-reported; a recorded file-change tool item is not proof that an artifact was published or that a user was satisfied. Command exit codes are recorded as command results, not automatically classified as tests. Later turns link to the same source conversation; the implementation does not infer that every follow-up is a correction.

## Collection

- Codex: sessions created, opened, followed or replied to through NextComp enroll automatically. Existing followed/outgoing/started sessions enroll on server startup. Item/turn events update stable source IDs; opening history hydrates the record. A read-only background scan recovers missed history, one page every 15 seconds, round-robin across enrolled sessions. No new model turns are generated.
- History paging uses existing `ThreadHistory` with a target of four turns / 80 items / 160KB per scan. Older servers without paging can require a full original history read through its existing compatibility fallback. The record retains only bounded display text. Coverage reports unfinished backfill.
- Voice: the authenticated device's existing persisted voice transcript/response history supplies the latest 30 entries. No coordinator raw reasoning is copied.
- Losangelex: authorized history/messages responses seen by each device are copied with environment/team/task/message/author/visibility provenance. Stable message IDs deduplicate replay. This records observed communication; it does not reconstruct every team task or automatically scrape unseen direct conversations. The latest 30 messages are shown, separately from Codex turns.

## Stored data and privacy

Derived tables live in the existing private NextComp SQLite store: enrolled session metadata, source turn status/time, user text, agent text/phase, changed file paths/status, command exit codes, generated-image paths when exposed, and device-scoped observed team messages. Existing voice storage is reused. Requests and outputs may themselves contain personal or sensitive information; this is additional local retention of those excerpts, not automatic secret scrubbing.

Raw reasoning, arbitrary tool payloads, shell output, commands and file diffs are excluded. Each text excerpt is capped at 16,000 characters; file-path lists are capped at 100. Truncation is carried as evidence metadata. `version` identifies first collection under NextComp, not the version that performed historical work. Original backend history remains the authoritative source when available.

`GET /api/work-record` uses the existing paired-device/owner authentication. Codex work follows the existing shared-host conversation visibility model; voice and observed team records remain device-scoped. Pairing origins are captured by the Android client, redirects disabled, and team records include environment identity. No external analytics service, inference call or automatic publication is added. Records persist with the local database; no automatic retention/deletion policy is implemented yet.

## Delivery and remaining scope

This feature adds a dedicated server projection and native screen with narrow history/event/provider hooks. It preserves the release owner's control of versioning, backend restart and physical-phone installation. The initial screen shows source requests, final or latest agent output, recorded file changes, command-result counts, conversation links and follow-up provenance.

Automatic semantic accomplishment summaries, independently checked publication receipts, cross-conversation project identity, a full team/voice archive UI, retention controls and a branch tree are follow-on work. They must retain provenance and uncertainty rather than manufacturing success counts. Context manifests/forks are a separate investigation; bounded display records must never be used as lossless model-context exports.
