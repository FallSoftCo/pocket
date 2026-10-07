# Work updates

Scheduled check-ins belong to a durable, device-owned inbox rather than coordinator chat. The session list has a compact Work updates entry showing unread count and the latest meaningful preview. Open it to read reports, follow their session links or explicitly listen. Ordinary intentions, replies and deliberate requests such as “what did I miss?” remain in coordinator chat.

Cadence, stale-work thresholds and enabling reports are unchanged. The inbox preserves older reports and loads earlier pages automatically near the beginning. Refresh merges new reports without hiding cached pages. Scheduled notifications replace one quiet slot per execution profile; they do not create a speech backlog. Existing speech choices remain available.

Fetching, generating, scheduling and offering a report do not mark it read. The client acknowledges its exact report identity only after its content has been exposed while the app is foreground; partial exposure remains unread. Presentation records are device-scoped, idempotent and separate from scheduling deduplication. Existing catch-up actions record the exact source events included in a presented report. Pending questions can remain actionable after their information has been read.

Existing report rows are retained. Updated clients filter actual scheduled reports from normal coordinator history; manual conversation remains. Older clients retain their prior API response until upgraded. Controller history migration excludes stored scheduled-report membership while deliberate catch-up remains part of the conversation.

Implementation: `server/coordinator-reports.mjs`, `server/voice-controller.mjs`, `PocketWorkUpdates.kt`, `WorkUpdatesScreen.kt`, `WorkInboxCursor.kt` and notification routing. See the versioned [alpha38 qualification](qualification/full-permissions-and-work-inbox-alpha38.json) for evidence and deployment limits.
