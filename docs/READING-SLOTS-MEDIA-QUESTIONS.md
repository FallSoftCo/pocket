# Reading, previews and decisions

The browser reserves the maximum intrinsic size of each aligned grammatical phrase in both teaching languages. A subtle two-pixel-radius surface identifies the phrase. Both forms share one CSS grid cell; only the chosen form is visible and exposed to accessibility. Flips change visibility rather than replacing the paragraph, measuring on each tick, shrinking text or animating a transition. Direct taps switch a phrase until its next scheduled flip. Existing controls retain their ordinary action; their translated labels do not intercept button clicks. Long phrases wrap at normal text size.

Questions and approvals have one surface: a right-hand pane on wide desktop and a right-side drawer at narrow widths. A persistent Needs you count and per-session badge expose outstanding work. Existing attention data supplies other sessions; opening one obtains its actual pending requests before answering. No inferred questions, background thread resumes or model polling are used. Request cards are keyed by environment-qualified session/request identity. An unrelated request must preserve input value, focus and selection. A successful submission remains submitted if refreshing afterward fails; uncertain delivery is checked, not automatically replayed.

Saved results share this pane, with Reply, In conversation and Dismiss. Entries matching user input or repeated result text are not presented as answers. Dismiss uses the existing canonical removal receipt. Results outside locally loaded history remain available without pretending their original row has been loaded. No parallel transcript/filter is generated.

For preview adoption, agents can share ordinary Markdown images, direct image links, file attachments or an existing gallery card link. The browser resolves same-origin `/8bar/#card-id` against the existing `showcase-catalog.json`, caches it briefly and renders the card poster or a deliberate non-autoplay video player inline. It never invents card IDs, sends a room/chat post, opens the external gallery automatically, proxies arbitrary servers or invokes a model. Original links remain available. Gallery metadata failure leaves the original link intact. Cross-origin galleries without a supported metadata origin remain ordinary links.

This revision is browser-first, with desktop and mobile viewport qualification. It is not an Android APK delivery or a physical phone qualification. The matching native treatment remains tracked separately.

## Android152 candidate

The shared native renderer shapes both grammatical forms using existing Compose text measurement and reserves their maximum dimensions in native inline placeholders. Ordinary text size, styles, URLs and selection alternate text are retained. Label/card actions disable phrase toggling so translation cannot steal their clicks. Direct phrase taps change to an actually different form; the existing single ten-second screen clock continues. Native intrinsic measurement supports compact controls and menus.

Android recognizes gallery card fragments in Markdown and ordinary pasted URLs, coalesces existing metadata reads with a two-request concurrency bound and short cache, never sends a paired-device token to the gallery, and uses the established image loader/viewer. It does not start agent work or invent assets.

The conversation footer opens one Questions and results surface, with a tablet side rail and a narrow-window end drawer. Questions that arrive while the person is neither typing nor recording are surfaced directly; otherwise the footer count remains visible. Coordinator has the same question entry. Saved results exclude user-message copies and expose Reply, In conversation and Dismiss. Answer drafts persist across drawer dismissal with environment/pairing/thread/request identity. A confirmed decision remains submitted independently of transcript refresh, and an uncertain decision is checked rather than automatically repeated.

The Android build preflight now uses the user-directed8GiB CPU reserve instead of its obsolete hard-coded80GiB floor. The shared storage-build admission still accounts for measured peak bytes and concurrent reservations; RAM remains forbidden for build trees.

The native end drawer uses an in-activity Material navigation drawer, with reading direction restored inside its contents. It does not create a dialog window that would take volume-key handling or foreground-read ownership away from the activity.
