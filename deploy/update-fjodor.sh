#!/bin/bash
# Copy this to your home folder once (the folder is only readable by fjodor, hence sudo):
#   sudo cat /opt/fjodor/src/deploy/update-fjodor.sh > ~/update-fjodor.sh && chmod +x ~/update-fjodor.sh
# Pulls the newest code as the fjodor user (deploy key, no token), then builds and restarts.
set -euo pipefail
sudo -u fjodor -H git -C /opt/fjodor/src pull --ff-only
exec sudo bash /opt/fjodor/src/deploy/update.sh
