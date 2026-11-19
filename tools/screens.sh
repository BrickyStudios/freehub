#!/usr/bin/env bash
# Retakes the 17 demo screenshots in docs/bilder/app on a running emulator (1080×2400, e.g. a Pixel 7 AVD),
# then `node tools/bilder.mjs` turns them into the README images. Install a build of the app first.
#   tools/screens.sh            (adb on PATH, or ANDROID_SDK_ROOT set)
set -u
cd "$(dirname "$0")/.."
OUT=docs/bilder/app
[ -n "${ANDROID_SDK_ROOT:-}" ] && export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
P=io.github.brickystudios.freehub

# clean status bar: fixed clock, full battery, no notification icons
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0835
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile show -e datatype none -e level 4 -e fully true
demo notifications -e visible false

grab() { adb exec-out screencap -p > "$OUT/$1.png"; echo "  $1"; }
# first node whose text contains $1 (case-insensitive): "x y" of its centre, or its top y with $2 = top
node() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml | python3 -I -c '
import sys,re
x=sys.stdin.read(); q=sys.argv[1].lower()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0)
    if q in (re.search(r" text=\"([^\"]*)\"",n).group(1)+" "+re.search(r"content-desc=\"([^\"]*)\"",n).group(1)).lower():
        a=list(map(int,re.findall(r"\d+",re.search(r"bounds=\"([^\"]*)\"",n).group(1))))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break
' "$1"; }
tap() { local p; p=$(node "$1"); [ -n "$p" ] && adb shell input tap $p; sleep "${2:-1.2}"; }
swipe_left() { adb shell input swipe 900 1200 150 1200 250; sleep 1.2; }
# y of the first node whose text contains $1
ypos() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml | python3 -I -c '
import sys,re
x=sys.stdin.read(); q=sys.argv[1].lower()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0)
    if q in re.search(r" text=\"([^\"]*)\"",n).group(1).lower():
        print(re.findall(r"\d+",re.search(r"bounds=\"([^\"]*)\"",n).group(1))[1]); break
' "$1"; }

for lang in en de; do
  for theme in dark light; do
    echo "$lang-$theme"
    [ $theme = dark ] && adb shell cmd uimode night yes >/dev/null || adb shell cmd uimode night no >/dev/null
    adb shell am force-stop $P
    adb shell pm clear $P >/dev/null
    adb shell cmd locale set-app-locales $P --locales $([ $lang = de ] && echo de-DE || echo en-US)
    adb shell "run-as $P sh -c 'mkdir -p shared_prefs && printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?>\\n<map>\\n    <boolean name=\\\"onboarded\\\" value=\\\"true\\\" />\\n</map>\\n\" > shared_prefs/prefs.xml'"
    adb shell am start -n $P/.MainActivity >/dev/null
    sleep 4
    grab welcome-$lang-$theme
    tap "demo" 4
    grab live-$lang-$theme
    swipe_left; sleep 1
    grab stats-$lang-$theme
    swipe_left; sleep 1
    grab bike-$lang-$theme
    if [ $lang-$theme = en-dark ]; then
      # scroll so the shift mode row sits at the top: stored panel and shift quality follow
      y=$(ypos "shift mode"); [ -n "$y" ] && adb shell input swipe 540 $y 540 230 2500; sleep 1.5
      grab derailleur-en-dark
    fi
  done
done
adb shell cmd uimode night yes >/dev/null
demo exit
