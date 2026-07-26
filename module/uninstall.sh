#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/weig_rootad
KEEP_DIR=/data/adb/weig_rootad-user-backup

"$MODDIR/bin/rulectl" cleanup-mount >/dev/null 2>&1
rm -rf "$DATA_DIR" "$KEEP_DIR"
