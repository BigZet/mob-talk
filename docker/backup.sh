#!/bin/sh
# World backup for the Docker server: pauses saving, flushes, packs data/world into backups/, resumes, keeps the last 14.
# cron (as the user that runs docker): 30 5 * * * /opt/mobtalk/docker/backup.sh >> /opt/mobtalk/docker/backups/backup.log 2>&1
set -eu
cd "$(dirname "$0")"
mkdir -p backups
stamp=$(date +%Y%m%d-%H%M%S)
console() { docker exec --user 1000 mobtalk-mc mc-send-to-console "$@" || true; }
console save-off
console save-all flush
sleep 10
tar -czf "backups/world-$stamp.tar.gz" -C data world
console save-on
ls -1t backups/world-*.tar.gz | tail -n +15 | xargs -r rm --
echo "$(date -Is) backups/world-$stamp.tar.gz"
