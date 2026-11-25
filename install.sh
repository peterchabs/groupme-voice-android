#!/data/data/com.termux/files/usr/bin/bash
# One-time setup for Say So. Run inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/peterchabs/groupme-voice-android/main/install.sh | bash
set -e

RAW="https://raw.githubusercontent.com/peterchabs/groupme-voice-android/main"
CONF="$HOME/.config/groupme-voice"
SCRIPT="$HOME/.termux/tasker/groupme_send.py"

echo ""
echo "=== Step 1 of 4: installing the tools this needs (takes a minute) ==="
pkg update -y
pkg install -y python termux-api curl

echo ""
echo "=== Step 2 of 4: storage permission ==="
echo "If your phone asks to allow Termux to access files, tap ALLOW."
termux-setup-storage || true
sleep 3

echo ""
echo "=== Step 3 of 4: downloading the GroupMe sender ==="
mkdir -p "$HOME/.termux/tasker" "$CONF"
curl -fsSL "$RAW/groupme_send.py" -o "$SCRIPT"
chmod 700 "$SCRIPT"
echo "Done."

echo ""
echo "=== Step 4 of 4: your GroupMe token ==="
if [ -s "$CONF/token" ]; then
  echo "You already saved a token. To replace it, run:  rm $CONF/token  and run this installer again."
else
  echo "Paste your GroupMe token below, then press Enter."
  echo "(Nothing will appear while you paste. That is normal; it keeps the token hidden.)"
  read -rs TOKEN < /dev/tty
  echo ""
  TOKEN="$(printf '%s' "$TOKEN" | tr -d '[:space:]')"
  if [ -z "$TOKEN" ]; then
    echo "No token was pasted. Run this installer again when you have it."
    exit 1
  fi
  ( umask 077; printf '%s' "$TOKEN" > "$CONF/token" )
  echo "Token saved."
fi

echo ""
echo "Checking the token by listing your groups:"
echo "--------------------------------------------"
python3 "$SCRIPT" --list
echo "--------------------------------------------"
echo ""
echo "If you see your groups above, setup in Termux is finished."
echo "Go back to the instructions and continue with 'Part 4: Set up Tasker'."
