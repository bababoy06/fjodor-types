#!/bin/bash
# Builds and restarts fjodor-types from the code in /opt/fjodor/src.
# ~/update-fjodor.sh pulls first and then runs this file with sudo (only root and fjodor can read
# /opt/fjodor), so changes to these steps arrive with the code.
set -euo pipefail
APP=/opt/fjodor
as_app() { sudo -u fjodor -H "$@"; }

cd "$APP/src/server"
as_app ./gradlew --quiet jar
sudo install -o fjodor -g fjodor -m 644 "$APP/src/server/build/libs/fjodor.jar" "$APP/fjodor.jar"
sudo systemctl restart fjodor
echo "fjodor-types updated to $(as_app git -C "$APP/src" log -1 --format='%h %s')."
