# NextComp documentation

NextComp provides Android and web control of vanilla Codex sessions and native subagents across paired environments. NextComp’s room, LAX and Hollywood entry points were retired on 2026-11-07. Their documentation and original evidence remain historical; they do not describe selectable NextComp backends. This retirement does not establish that separate native runtimes or services are shut down; their lifecycle is outside this release. See [the Codex-only transition](CODEX-ONLY.md).

The [current supported snapshot](CAPABILITIES.md#current-supported-snapshot--2026-11-07) records the published version, deployed sources and last verified handset installation. [182 delivery and qualification evidence](qualification/native-parity-182.json) records the delivered Android, backend and web182 release on both hosts; physical-phone installation remains separately unverified. Publication, synthetic/emulator qualification and physical-phone observations are distinct.

[183 reading update](qualification/immersion-reading-183.json) is published on both Android update channels, with the same soft immersion highlights live on web. It removes idle phrase outlines and corrects enlarged native phrase reserves. Backend182 remains in place; handset installation is separately unverified.

The current controls include native session branching/context actions, configuration and installed-tool inspection, supported questions/approvals, retained command output and background terminals where the selected runtime supports them. The qualified Mac ARM64 0.163 runtime exposes read-only catalogs, terminal listing and retained command-output reads. Live backend 182 checks returned a 592-character output and an explicit unavailable response for a command without retained output; they made no model calls or mutations. Configuration changes and terminal termination remain unavailable on that runtime. [Capabilities and limits](CAPABILITIES.md) are method-specific; this is not a claim of complete Codex TUI parity.

## Understand and evaluate

- [NextComp briefing for Tibo and agent reviewers](NEXTCOMP-BRIEF.md): the complete account of what a person can do, how the system fits personal-agent computing, and what warrants a technical conversation.
- [Capabilities and evidence](CAPABILITIES.md): user actions, implementation entry points, versioned observations and unresolved gaps.
- [Brief evaluation](BRIEF-EVALUATION.md): why this briefing was selected, what the evaluations actually tested, and their limits.
- [Verification](VERIFICATION.md): historical runtime records; each applies to its named version and environment.
- [Architecture decisions](DECISIONS.md), [privacy](../PRIVACY.md) and [security](../SECURITY.md): runtime ownership, delivery semantics and trust boundaries.
- [Recorded UX requests](UX-REQUESTS.json): requirements and implementation status. Historical entries are not blanket current-release certification.

- [Modeled 3D symbol workstream](../branding/modeled/README.md): original construction, design language, perspective/lighting studies and versioned motion qualification.

## Install and operate

- [Named development environments](MULTI-ENVIRONMENT.md): retained remote pairings, source-bound actions and the phone profile.

- [Browser client for Pixelbook Go](WEB-CLIENT.md): private gateway, keyboard/touch flows, pairing, PWA and browser limitations.
- [Setup and everyday use](GETTING-STARTED.md), [deployment](DEPLOYMENT.md) and [Firebase configuration](FIREBASE.md).
- [Android local execution and phone control](ANDROID_LOCAL.md).
- [Voice architecture](VOICE.md) and [native voice investigation](NATIVE-VOICE-INVESTIGATION.md).
- [Notification read handling](NOTIFICATION-READS.md) and [spoken captions](SPEECH-CAPTIONS.md).
- [Speech activity tracking](SPEECH-USAGE.md): local generation/playback counters, blocked-audio safeguards and billing limits.
- [Automatic work record](WORK-RECORD.md): requests, observed outputs, file evidence and linked follow-ups; implementation candidate, with private retention and qualification limits.
- [Work updates](WORK-UPDATES.md): scheduled reports outside coordinator chat, retained history, exact unread state and direct session links.
- [Saved typed replies and recovery](CLIENT-REPLY-RECOVERY.md): durable source-bound intents, validated receipts and unknown-delivery limits.
- [Audited task continuation](CONTINUATION.md): evidence-backed resumption, durable checkpoints, sparse recovery and protected stops.
- [Work lifecycle replacement](WORK-LIFECYCLE.md): event-driven work, one owner, bounded model admission, acceptance receipts and the qualified rollout boundary.
- [Session catalog](SESSION-CATALOG.md): canonical identity, work recency, child capabilities and stable native discovery.
- [Markdown tables](MARKDOWN-TABLES.md): structured cells, shared horizontal scroll and ordinary/immersion qualification.
- [Native linked images](LINKED-IMAGES.md): previews and a zoomable viewer in the conversation, including authenticated shared images.
- [Inline language immersion](ITALIAN-VIRTUAL-IMMERSION.md), [reading and motion design](IMMERSION-PERCEPTION-DESIGN.md), and [versioned qualification](qualification/automatic-inline-cycling.md).
- [Maintaining the backend](MAINTAINER.md) and [release process](RELEASING.md).
- [Shared computer-use ownership](COMPUTER-USE.md): optional, separately installed broker and cooperative worker instructions; direct tools are not automatically fenced by NextComp.

## Historical designs and retired integrations

- [Coordinator room conversation and intent](COORDINATOR-ROOM-INTENTS.md): retired room workflow and its original qualification limits.
- [Codex and Losangelex backend selection](LOSANGELEX-BACKEND.md): historical backend/team contract; Losangelex is no longer selectable.
- [Context snapshots and independent workers](CONTEXT-CONTROL.md): earlier branching and Hollywood coordination design; consult the current capability map for implemented native session controls.
- [Self-service terminal forks](TERMINAL-FORK.md): historical terminal succession proposal, distinct from current native conversation branching.

## Read the evidence correctly

An implemented route is not a verified end-to-end workflow. A passing fixture is not a provider or physical-phone test. A push sender's acceptance is not handset delivery. A historical successful phone upgrade does not establish the latest APK is installed. Use the [capability map](CAPABILITIES.md#release-and-evidence-boundaries) to separate those claims, and inspect the qualification record before making a stronger one.

- [Single conversation context and direct speech qualification](qualification/conversation-single-context-alpha22.json) — alpha22 synthetic actions, layout, playback and keyboard checks.

- [Alpha22 release and real-phone delivery](qualification/alpha22-release-delivery.json) — signatures, tests, installed hash and post-update UI observations.

- [Alpha23 reply recovery and Important filter](qualification/recovery-important-runtime-alpha23.json) — thirteen exact-APK native tap scenarios, including outbox acknowledgement while history is unavailable.

- [Foreground voice interaction](VOICE-INTERACTION.md) — capture, keyboard preservation and playback controls.

- [Settings and update access](qualification/settings-update-access-alpha40.json) — visible footer entry and immediately reachable app updates.

- [Voice latency and measurement boundaries](VOICE-LATENCY.md)
