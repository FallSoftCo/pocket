# Session diagnostic previews

Published in Android197/alpha179 and web. Session previews remain within their two-line card region at normal and enlarged text sizes. Long diagnostics, unbroken tokens and bilingual phrase placeholders cannot paint over neighboring cards. Full error details remain in the conversation/attention view.

Android bounds both placeholder measurement and glyph rendering by the caller line and height constraints, then clips the preview viewport. Web overrides the unwrapped noninteractive label style inside previews and applies a two-line ellipsis without nested scrollbars.

The dedicated native long-error check passes in both language forms at normal and doubled text size; adjacent actions remain usable. The exact optimized signed APK passed catalog/history/reopen checks. Real Chrome screenshots verify desktop and narrow enlarged previews. See [delivery evidence](qualification/session-preview-overflow197.json). Emulator and publication evidence do not establish physical-phone installation.
