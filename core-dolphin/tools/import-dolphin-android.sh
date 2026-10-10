#!/usr/bin/env bash
# One-shot bootstrap: import mainline Dolphin's Android Java/Kotlin + resources into :core-dolphin with the
# package relocated  org.dolphinemu.dolphinemu  ->  com.noryan.romcore.core.dolphin.jni
# (see ../README-dolphin.md). The result is committed; re-run only to rebase onto a newer Dolphin, then
# re-apply the hand edits listed in README-dolphin.md ("Files changed from upstream").
#
# Native side is handled by patches/0001-*.patch (applied to dolphin-src branch `romcore`).
set -euo pipefail
SRC="${DOLPHIN_SRC:-/Users/ry/Desktop/claude/dolphin-src}/Source/Android/app/src/main"
DST="$(cd "$(dirname "$0")/.." && pwd)/src/main"
OLD_PKG=org.dolphinemu.dolphinemu
NEW_PKG=com.noryan.romcore.core.dolphin.jni
OLD_DIR=$(echo "$OLD_PKG" | tr . /)
NEW_DIR=$(echo "$NEW_PKG" | tr . /)

rm -rf "$DST/java/$NEW_DIR" "$DST/res"
mkdir -p "$DST/java/$NEW_DIR" "$DST/res"
cp -R "$SRC/java/$OLD_DIR/." "$DST/java/$NEW_DIR/"
cp -R "$SRC/res/." "$DST/res/"
# Launcher/banner art would collide with the host app's and other cores' icons; the shell supplies icons.
rm -rf "$DST"/res/mipmap-* "$DST"/res/drawable*/banner_tv* "$DST"/res/drawable*/ic_launcher*

find "$DST/java" "$DST/res" \( -name '*.kt' -o -name '*.java' -o -name '*.xml' \) -print0 |
  xargs -0 sed -i '' -e "s/org\\.dolphinemu\\.dolphinemu/${NEW_PKG}/g"
# Prune Dolphin's own Android UI (RomCore shows the game surface + the shared romcore-api menu only).
# What stays is the JNI-facing model/utility layer that the native IDCache requires.
J="$DST/java/$NEW_DIR"
rm -rf "$J"/{activities,adapters,dialogs,fragments,layout,overlay,services,ui,viewholders}
rm -rf "$J"/features/*/ui "$J"/features/settings/model/view "$J"/features/input/model/view
rm -f  "$J"/features/DocumentProvider.kt "$J"/utils/CoilUtils.kt "$J"/utils/FileBrowserHelper.kt \
       "$J"/utils/InsetsHelper.kt "$J"/utils/ThemeHelper.kt "$J"/utils/TvUtil.kt "$J"/utils/AppLinkHelper.kt \
       "$J"/utils/AlertDialogItemsBuilder.kt "$J"/utils/LifecycleViewHolder.kt "$J"/utils/StartupHandler.kt \
       "$J"/utils/CoverHelper.kt "$J"/model/HomeScreenChannel.kt "$J"/model/TvSettingsItem.kt "$J"/model/TaskViewModel.kt
# Resources: keep ONLY strings (R.string.* is referenced by retained model code). Every layout/style/drawable
# belonged to the removed UI, and dropping them also avoids resource-name merge collisions with :core-primehack.
find "$DST/res" -mindepth 1 -maxdepth 1 ! -name values -exec rm -rf {} +
find "$DST/res/values" -type f ! -name strings.xml -delete
# Upstream resources are kept only for what the retained code references (see README-dolphin.md).
echo "imported + relocated into $DST"
