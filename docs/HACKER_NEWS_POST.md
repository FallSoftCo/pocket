# Hacker News launch draft

## Submission title

Show HN: Pocket – Control Codex and let it control your phone (Android/Linux)

## Submission URL

https://github.com/FallSoftCo/pocket

## First comment

Hi HN — I built Pocket because I wanted to leave my desk while Codex was working without turning every long task into a one-way process.

Pocket is a self-hosted Android companion for the stock Codex CLI. It works in both directions:

1. Codex can keep running on a Linux workstation while the Android app lets you start tasks, follow their output chronologically, answer questions, handle supported approvals, reply from notifications, and receive completion or attention alerts.
2. Codex can run locally on the Android phone through Termux. In that mode, Pocket exposes the phone through a small MCP server, so an explicitly requested task can inspect the current screen, take screenshots, tap, scroll, enter non-password text, and use Android navigation.

That second direction is the strange and useful part: I can ask the phone-local Codex session to open an app, browse it, and report what it finds. Pocket uses Android Accessibility for this. The owner must enable both Android's Accessibility permission and a separate **Control this phone** switch in Pocket. Once those gates are enabled, the phone tools are preapproved so a multi-step interaction does not stop for another prompt after every tap or scroll.

This does not require a Codex fork. Pocket connects to the existing experimental Codex app-server protocol and uses an MCP server for notifications and phone controls. It is an independent project and is not affiliated with OpenAI.

There is no FallSoft-hosted relay and no Play Store dependency. You run the Node backend on your own Linux machine, pair your own phone, and normally expose it privately with Tailscale Serve. Push delivery uses your own Firebase project. Firebase carries notification titles and bounded previews; conversations and attachments remain on your workstation. Phone-local mode communicates over loopback and does not need Firebase or Tailscale.

The notification side grew beyond simple completion pings. Pocket supports direct replies, questions and approvals, reminders with snooze and dismissal, offline on-device speech, spoken summaries, pause/resume controls, and resuming speech after audio interruptions. The conversation view follows Codex's chronology and shows commands, tool activity, diffs, questions, and final responses in place.

The security model is deliberately for one owner and trusted phones, rather than shared hosting. Pairing grants broad access to that owner's Codex work. Phone screen snapshots omit password text, text entry refuses password fields, and Android secure surfaces and biometric prompts remain outside Accessibility control. The phone-tool instructions also require a direct request for the exact action before posting, messaging, purchasing, deleting, or changing account/security settings.

This is an alpha. The current physical test target is a Pixel 9 Pro Fold, workstation mode is tested on Linux, and phone-local Codex depends on Termux. Accessibility quality varies between apps: standard Android and Compose interfaces tend to work well, while games, canvases, video surfaces, and some WebViews may require screenshot-and-coordinate interaction or may not be controllable. Initial setup is also more involved than installing a normal consumer app because you own the backend and Firebase project.

The repository is MIT licensed and includes the Android source, backend, setup documentation, threat boundaries, verification notes, public CI, and a signed APK with its checksum and certificate fingerprint:

- Repository and setup: https://github.com/FallSoftCo/pocket
- Latest signed Android alpha: https://github.com/FallSoftCo/pocket/releases/tag/v0.5.0-alpha.3
- Android-local setup: https://github.com/FallSoftCo/pocket/blob/main/docs/ANDROID_LOCAL.md
- Security model: https://github.com/FallSoftCo/pocket/blob/main/SECURITY.md
- Verification notes: https://github.com/FallSoftCo/pocket/blob/main/docs/VERIFICATION.md

I would especially value feedback on the self-hosting setup, the boundary between convenient phone automation and understandable owner control, and which Android devices or interfaces fail in practice.
