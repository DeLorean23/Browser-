#!/bin/bash
# Emulator-Test für DeLorean Tube: Start, Video antippen, verkleinern, Shorts, Link.
set -x
OUT=emu-out
mkdir -p $OUT
APK=DeLoreanTube-32bit.apk
adb install -r -g $APK > $OUT/install.txt 2>&1
adb shell settings put global window_animation_scale 0
adb logcat -c
alive() { adb shell pidof de.delorean23.tube > $OUT/pid-$1.txt || echo "APP LAEUFT NICHT MEHR" > $OUT/pid-$1.txt; }
shot() { adb exec-out screencap -p > $OUT/$1.png; }
adb shell am start -W -n de.delorean23.tube/.MainActivity
sleep 30
shot 1-start
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DENS=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
# erstes Video antippen
adb shell input tap $((W/2)) $((H*35/100))
sleep 40
shot 2-video
alive video
# Video nach unten ziehen (verkleinern)
adb shell input swipe $((W/2)) $((H*12/100)) $((W/2)) $((H*80/100)) 400
sleep 6
shot 3-mini
alive mini
# Shorts-Tab (2. von 4 Tabs unten)
adb shell input tap $((W*3/8)) $((H - 28*DENS/160))
sleep 40
shot 4-shorts
alive shorts
# nach oben wischen = nächstes Short
adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*20/100)) 250
sleep 20
shot 5-shorts-weiter
alive shorts2
adb shell input keyevent KEYCODE_BACK
sleep 3
# Video direkt per Link öffnen
adb shell am start -W -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=jNQXAC9IVRw" -n de.delorean23.tube/.PlayerActivity
sleep 30
shot 6-link
alive link
adb shell input keyevent KEYCODE_BACK
sleep 6
shot 7-link-zurueck
alive link-zurueck
adb logcat -d -b crash > $OUT/crash.txt 2>&1
adb logcat -d -v time > $OUT/logcat-alles.txt 2>&1
grep -E "AndroidRuntime|FATAL|de.delorean23|ExoPlayer|MediaSession|tube-io|System.err|ActivityManager: (Start|Process|Force)" $OUT/logcat-alles.txt | tail -800 > $OUT/logcat-app.txt
exit 0
