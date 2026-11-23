#!/data/data/com.termux/files/usr/bin/sh
set -eu

if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX" ]; then
  echo "Run this script inside Termux on Android." >&2
  exit 1
fi

case "$(uname -m)" in
  aarch64) npm_cpu=arm64 ;;
  x86_64) npm_cpu=x64 ;;
  *) echo "Unsupported Android CPU: $(uname -m)" >&2; exit 1 ;;
esac

pkg install -y nodejs proot termux-services
npm install -g --os=linux --cpu="$npm_cpu" @openai/codex@0.158.0
install -m 700 "$(dirname "$0")/codex-phone" "$PREFIX/bin/codex-phone"

echo "Codex is installed. Sign in with:"
echo "  codex-phone login --device-auth"
echo "Then install Pocket's local runtime with:"
echo "  npm run android-local"
