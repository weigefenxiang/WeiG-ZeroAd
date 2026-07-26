#!/system/bin/sh

ui_print "- Installing WeiG ZeroAd"
ui_print "- Creating persistent state"

DATA_DIR=/data/adb/weig_rootad
mkdir -p "$DATA_DIR/state" "$DATA_DIR/user"
touch "$DATA_DIR/user/allow.txt" "$DATA_DIR/user/block.txt" "$DATA_DIR/user/disabled.txt" \
  "$DATA_DIR/user/packs.enabled"

set_perm_recursive "$DATA_DIR" 0 0 0700 0600
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/rulectl" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

MANAGER_APK="$MODPATH/manager/WeiG-ZeroAd-Manager.apk"
if [ -f "$MANAGER_APK" ]; then
  ui_print "- All-in-one package: installing manager app"
  if command -v pm >/dev/null 2>&1 && pm install -r "$MANAGER_APK" >/dev/null 2>&1; then
    ui_print "- Manager app installed or updated"
    rm -f "$MANAGER_APK"
    rmdir "$MODPATH/manager" 2>/dev/null
  else
    ui_print "! Manager app installation failed; the core is still installed"
    ui_print "! Install the standalone Manager APK from the same GitHub Release"
  fi
else
  ui_print "- Core-only package: manager app was not included"
fi

ui_print "- Default profiles: domestic lean, global off"
ui_print "- Reward-ad blocking packs: enabled"
ui_print "- Compatible module layout: Magisk / KernelSU / APatch"
ui_print "- Reboot after installation"
