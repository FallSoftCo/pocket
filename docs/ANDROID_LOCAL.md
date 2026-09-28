# Codex and Pocket on Android

Pocket can run beside stock Codex CLI on one Android phone. A small Node bridge owns a stock `codex app-server` child over stdio and exposes Pocket only on `127.0.0.1:18880`. The Android app connects over loopback. FallSoft operates no relay and this mode does not use Firebase.

This path is verified on an ARM64 Pixel 9 Pro Fold with Android API 36, Termux 0.118.3, Node 26.1.0, and Codex CLI 0.158.0. Other devices and CLI versions remain experimental.

## Install

Install Termux from F-Droid, plus matching-signature Termux:API and Termux:Boot apps. Open each companion once. In Termux:

```bash
pkg update
pkg install git
git clone https://github.com/fallsoftco/pocket.git
cd pocket
./scripts/install-codex-android.sh
codex-phone login --device-auth
npm ci
npm run android-local
```

Open Pocket. Choose **Connect Codex on this phone** and paste the one-time code if Android did not open the prefilled form. The installer copies the code to the clipboard when Termux:API is available. Pairing codes expire after 15 minutes and work once.

The `codex-phone` wrapper maps Termux's DNS file and temporary directory into the Linux Codex process through PRoot. Use it instead of the raw `codex` command. Phone-local turns use `danger-full-access` because Codex's desktop `workspace-write` sandbox cannot establish its mount isolation inside Android/PRoot. Android's Termux app permissions remain the outer boundary; Codex can access Termux private files and any shared storage granted to Termux.

## Background operation

`npm run android-local` installs a runit service under `$PREFIX/var/service/pocket-local`, logs under `~/.local/state/pocket-local/log`, and writes a Termux:Boot script. Pocket shows a foreground **Local Codex monitor** notification while the local profile is active. The bridge and monitor must both be alive for background task events and prompts.

Allow Termux, Termux:Boot, and Pocket to run in the background. Launch Termux:Boot once after installing it. Android force-stop, clearing app data, explicit service shutdown, or an OS process kill can interrupt the bridge. After a reboot, unlock the phone once if Android requires it.

Useful checks:

```bash
sv status "$PREFIX/var/service/pocket-local"
node scripts/android-local.mjs status
tail -f ~/.local/state/pocket-local/log/current
codex-phone mcp get pocket-phone
```

Rerun `npm run android-local` after updating the checkout. It refreshes the service and MCP configuration without replacing Codex history.

## Phone control

Pocket's optional accessibility service provides these local tools to Codex:

- `phone_screen` reads a bounded accessibility tree.
- `phone_screenshot` captures the foreground display.
- `phone_tap` and `phone_click` activate coordinates or semantic nodes.
- `phone_scroll`, `phone_type`, and `phone_key` handle gestures, non-password text, Back, Home, and Recents.

Enable **Pocket** in Android's Accessibility settings, then turn on **Control this phone** inside Pocket. Either control can pause access. The service binds only to `127.0.0.1:18881`, requires a random secret stored separately in both app sandboxes, and refuses every request while the Pocket switch is off. Password text is omitted from screen snapshots and password fields reject text entry.

All phone tools are configured as approved once both Android controls are enabled, allowing an explicitly requested multi-step interaction to continue without a prompt for each tap or scroll. A direct request such as “open YouTube and browse my subscriptions” supplies the task intent; posting, sending messages, purchases, deletion, and account or security changes must still be stated explicitly for that exact action.

Accessibility structure varies by app. Standard Android views and Compose usually expose useful labels. Games, video surfaces, canvases, and some WebViews may expose little structure; Codex can inspect a screenshot and use coordinates, but visual automation is less deterministic. CAPTCHAs, biometric prompts, Android permission dialogs, and protected or secure screens may block inspection or control.

Accessibility-tree control supports Pocket's Android 9 minimum. Screen capture through the accessibility service requires Android 11 or newer.

## Remove

In Pocket, disconnect **This phone** and disable its Accessibility service. Then in Termux:

```bash
sv down "$PREFIX/var/service/pocket-local"
codex-phone mcp remove pocket-phone
rm -rf "$PREFIX/var/service/pocket-local" ~/.local/share/pocket-local
rm -f ~/.termux/boot/20-pocket-local
```

This removes Pocket's local bridge state. It does not remove Codex, its login, or its conversation history.
