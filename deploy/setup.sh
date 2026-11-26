#!/bin/bash
# First install of fjodor-types on the VPS, the same way as Pearl'It All:
#   /opt/fjodor (user fjodor), checkout in /opt/fjodor/src, fjodor.service on 127.0.0.1:8810, nginx in front.
# Run it once from anywhere:  bash setup.sh   (download it from the repo, or paste it into a file)
set -euo pipefail
APP=/opt/fjodor
REPO=git@github.com:bababoy06/fjodor-types.git
as_app() { sudo -u fjodor -H "$@"; }

if [ -d "$APP/src/.git" ]; then
    echo "Already installed: $APP/src exists. Use ~/update-fjodor.sh from now on."
    exit 0
fi
if ss -ltn | grep -q ':8810 '; then
    echo "Something already listens on port 8810. Pick another web.port and change it in the nginx file too."
    exit 1
fi

echo "== 1. The fjodor user and folder"
if ! id fjodor >/dev/null 2>&1; then
    sudo useradd --system --home "$APP" --shell /usr/sbin/nologin fjodor
fi
sudo mkdir -p "$APP"
sudo chown fjodor: "$APP"
sudo chmod 750 "$APP"

echo "== 2. A read-only deploy key, so pulling never asks for a token"
if ! sudo test -f "$APP/.ssh/id_ed25519"; then
    as_app mkdir -p "$APP/.ssh"
    as_app chmod 700 "$APP/.ssh"
    as_app ssh-keygen -q -t ed25519 -N "" -C "fjodor@$(hostname)" -f "$APP/.ssh/id_ed25519"
fi
echo
echo "Add this key on GitHub: fjodor-types -> Settings -> Deploy keys -> Add deploy key (leave 'Allow write access' off):"
echo
sudo cat "$APP/.ssh/id_ed25519.pub"
echo
read -r -p "Press Enter once it's added... "
as_app env GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=accept-new" git clone --quiet "$REPO" "$APP/src"
as_app git -C "$APP/src" config core.sshCommand "ssh -o StrictHostKeyChecking=accept-new"

echo "== 3. Build"
as_app bash -c "cd '$APP/src/server' && ./gradlew --quiet jar"
sudo install -o fjodor -g fjodor -m 644 "$APP/src/server/build/libs/fjodor.jar" "$APP/fjodor.jar"

echo "== 4. Config"
if ! sudo test -f "$APP/config.properties"; then
    sudo install -o fjodor -g fjodor -m 600 "$APP/src/config.example.properties" "$APP/config.properties"
fi

echo "== 5. The service"
sudo cp "$APP/src/fjodor.service" /etc/systemd/system/fjodor.service
sudo systemctl daemon-reload
sudo systemctl enable --now fjodor
sleep 3
if systemctl is-active --quiet fjodor; then
    sudo cat "$APP/src/deploy/update-fjodor.sh" > ~/update-fjodor.sh
    chmod +x ~/update-fjodor.sh
    echo
    echo "Done. fjodor-types runs from $APP as fjodor.service on 127.0.0.1:8810."
    echo "Next: the nginx file and certbot (README, 'Running it on the VPS'),"
    echo "then fill in the Discord settings:  sudo nano $APP/config.properties  &&  sudo systemctl restart fjodor"
    echo "Update from now on with:  ~/update-fjodor.sh"
    echo "Check the log:            journalctl -u fjodor -f"
else
    echo "fjodor didn't start. Look at: journalctl -u fjodor -n 50"
    exit 1
fi
