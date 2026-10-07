# NextComp documentation

NextComp connects a person on Android to ongoing work in their own computing environments. Its default backend uses stock Codex; an optional Losangelex backend exposes Hollywood team interaction. Start with the briefing to understand the intended experience, then use the capability map to inspect mechanisms, evidence and limits. Installation instructions alone do not describe the product's scope.

## Understand and evaluate

- [NextComp briefing for Tibo and agent reviewers](NEXTCOMP-BRIEF.md): the complete account of what a person can do, how the system fits personal-agent computing, and what warrants a technical conversation.
- [Capabilities and evidence](CAPABILITIES.md): user actions, implementation entry points, versioned observations and unresolved gaps.
- [Brief evaluation](BRIEF-EVALUATION.md): why this briefing was selected, what the evaluations actually tested, and their limits.
- [Verification](VERIFICATION.md): historical runtime records; each applies to its named version and environment.
- [Architecture decisions](DECISIONS.md), [privacy](../PRIVACY.md) and [security](../SECURITY.md): runtime ownership, delivery semantics and trust boundaries.
- [Recorded UX requests](UX-REQUESTS.json): requirements and implementation status. Historical entries are not blanket current-release certification.

- [Modeled 3D symbol workstream](../branding/modeled/README.md): original construction, design language, perspective/lighting studies and versioned motion qualification.

## Install and operate

- [Codex and Losangelex backend selection](LOSANGELEX-BACKEND.md): interaction modes, private registration, team controls and origin-bound attention.
- [Setup](../README.md#setup), [deployment](DEPLOYMENT.md) and [Firebase configuration](FIREBASE.md).
- [Android local execution and phone control](ANDROID_LOCAL.md).
- [Voice architecture](VOICE.md) and [native voice investigation](NATIVE-VOICE-INVESTIGATION.md).
- [Notification read handling](NOTIFICATION-READS.md) and [spoken captions](SPEECH-CAPTIONS.md).
- [Speech activity tracking](SPEECH-USAGE.md): local generation/playback counters, blocked-audio safeguards and billing limits.
- [Work updates](WORK-UPDATES.md): scheduled reports outside coordinator chat, retained history, exact unread state and direct session links.
- [Native linked images](LINKED-IMAGES.md): previews and a zoomable viewer in the conversation, including authenticated shared images.
- [Inline language immersion](ITALIAN-VIRTUAL-IMMERSION.md), [reading and motion design](IMMERSION-PERCEPTION-DESIGN.md), and [versioned qualification](qualification/automatic-inline-cycling.md).
- [Maintaining the backend](MAINTAINER.md) and [release process](RELEASING.md).
- [Shared computer-use ownership](COMPUTER-USE.md): optional, separately installed broker and cooperative worker instructions; direct tools are not automatically fenced by NextComp.

## Read the evidence correctly

An implemented route is not a verified end-to-end workflow. A passing fixture is not a provider or physical-phone test. A push sender's acceptance is not handset delivery. A historical successful phone upgrade does not establish the latest APK is installed. Use the [capability map](CAPABILITIES.md#release-and-evidence-boundaries) to separate those claims, and inspect the qualification record before making a stronger one.

- [Single conversation context and direct speech qualification](qualification/conversation-single-context-alpha22.json) — alpha22 synthetic actions, layout, playback and keyboard checks.

- [Alpha22 release and real-phone delivery](qualification/alpha22-release-delivery.json) — signatures, tests, installed hash and post-update UI observations.

- [Alpha23 reply recovery and Important filter](qualification/recovery-important-runtime-alpha23.json) — thirteen exact-APK native tap scenarios, including outbox acknowledgement while history is unavailable.

- [Foreground voice interaction](VOICE-INTERACTION.md) — capture, keyboard preservation and playback controls.
