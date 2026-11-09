# Needs you and retained reports

The current candidate replaces the Work updates navigation/feed with **Needs you**, a filter on the existing session list. It counts sessions with a known unresolved reason, rather than accumulating report or notification counts. Session cards retain their source and show why they need review; opening them uses the existing question, approval or outgoing-delivery controls. A delegated child's request remains individually discoverable. Leaving the filter restores the ordinary list and search; resolving the last item does not strand the reader.

The existing catalog supplies bounded `attention` evidence. Current native questions/approvals, original failed or uncertain sends, explicit valid input requests, and verified runtime errors qualify. Idle, deliberate interruption, silence, completion and notification history do not establish a blocker. An uncertain send offers inspection of its original receipt, never automatic resubmission. Missing or unavailable evidence is not an all-clear. Counts cover loaded catalog sessions, and existing source failure/history controls remain available.

Routine progress and completed results stay in session cards and conversations. Deliberate coordinator catch-up remains available. There is no extra model call, report-fetch loop or separate activity feed in this navigation.

## Existing reports and compatibility

Previously stored reports and device scheduling/audio settings are retained. Android can still open an old report notification in its compatibility reader. Removing the permanent entry does not mark reports read, delete history, re-enable scheduling, or move old reports into coordinator chat.

Cadence, stale-work thresholds and enabling reports are unchanged. The inbox preserves older reports and loads earlier pages automatically near the beginning. Refresh merges new reports without hiding cached pages. Scheduled notifications replace one quiet slot per execution profile; they do not create a speech backlog. Existing speech choices remain available.

Fetching, generating, scheduling and offering a report do not mark it read. The client acknowledges its exact report identity only after its content has been exposed while the app is foreground; partial exposure remains unread. Presentation records are device-scoped, idempotent and separate from scheduling deduplication. Existing catch-up actions record the exact source events included in a presented report. Pending questions can remain actionable after their information has been read.

Existing report rows are retained. Updated clients filter actual scheduled reports from normal coordinator history; manual conversation remains. Older clients retain their prior API response until upgraded. Controller history migration excludes stored scheduled-report membership while deliberate catch-up remains part of the conversation.

Implementation: `server/coordinator-reports.mjs`, `server/voice-controller.mjs`, `PocketWorkUpdates.kt`, `WorkUpdatesScreen.kt`, `WorkInboxCursor.kt` and notification routing. See the versioned [alpha38 qualification](qualification/full-permissions-and-work-inbox-alpha38.json) for evidence and deployment limits.
