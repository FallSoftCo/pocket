# Verification

## Tested platform

Stock Codex CLI **0.157.1**, Linux, Node **22.23.1**, and Pixel 9 Pro Fold on Android **API 36**. The shared Unix app-server was used without a fork or competing runtime.

## Physical-device evidence from 0.4

- Created a read-only session using the actual New task screen. Codex inherited the workstation model, emitted commentary, ran `pwd` and a marker-print command, and finished successfully. No project files were modified.
- Visually checked chronological prompt, commentary, command, expanded output, final response, and completion. Earlier reading position stayed put during new live activity; Latest returned to live output.
- Loaded a long conversation with more than 500 rows and paged to its earliest turn without overlap.
- Delivered a Firebase notification after verifying the Android process was absent. Android started the app process and displayed the notification. No permanent foreground service or battery exemption was present.
- Replied through a real notification into the original active Codex turn. The server recorded acceptance and the phone showed the final sent status.
- Generated all five generic spoken labels using an offline English voice. The user confirmed hearing the question label.
- Exercised a persisted reminder worker after its delay by explicitly triggering the eligible Android job. This verifies worker execution, not exact natural five-minute delivery. The reply resolved attention and stopped further reminders.

## Public alpha checks

Release-specific results are recorded below after the signed build is verified. Automated tests exercise pairing/replay protection, owner/device separation, revocation, durable reply/session IDs, exact request routing, interruption, push retries/project isolation, transcript merging and paging. Controlled app-server fixtures are not equivalent to live approvals.

## Limits

Physical reply evidence covers an active turn. Idle continuation, approvals, and stop-task routing use controlled protocol fixtures; no permission request was induced in ongoing user work. Other physical phone vendors, overnight Doze, full 30-minute snooze, reboot recovery, and other Codex versions are unverified. Force-stop prevents push until reopening. FCM acceptance alone never proves phone delivery. This is not an independent security audit or broad compatibility certification.
