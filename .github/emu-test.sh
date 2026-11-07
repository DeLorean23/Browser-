#!/bin/bash
# Emulator-Test für DeLorean Tube: App starten, Video antippen, Protokoll sichern.
set -x
OUT=emu-out
mkdir -p $OUT
APK=DeLoreanTube-32bit.apk
unzip -l $APK | grep "lib/" > $OUT/native-libs.txt || echo "keine nativen Bibliotheken" > $OUT/native-libs.txt
adb install -r -g $APK > $OUT/install.txt 2>&1
adb shell settings put global window_animation_scale 0
adb logcat -c
adb shell am start -W -n de.delorean23.tube/.MainActivity
sleep 30
adb exec-out screencap -p > $OUT/1-start.png
# erstes Video in der Liste antippen
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
adb shell input tap $((W/2)) $((H*35/100))
sleep 45
adb exec-out screencap -p > $OUT/2-nach-antippen.png
adb shell pidof de.delorean23.tube > $OUT/pid-nach-antippen.txt || echo "APP LAEUFT NICHT MEHR" > $OUT/pid-nach-antippen.txt
# zweiter Test: Video direkt per Link öffnen
adb shell am start -W -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=jNQXAC9IVRw" -n de.delorean23.tube/.PlayerActivity
sleep 45
adb exec-out screencap -p > $OUT/3-link.png
adb shell pidof de.delorean23.tube > $OUT/pid-nach-link.txt || echo "APP LAEUFT NICHT MEHR" > $OUT/pid-nach-link.txt
adb logcat -d -b crash > $OUT/crash.txt 2>&1
adb logcat -d -v time > $OUT/logcat-alles.txt 2>&1
grep -E "AndroidRuntime|FATAL|de.delorean23|ExoPlayer|MediaSession|tube-io|System.err|ActivityManager: (Start|Process|Force)" $OUT/logcat-alles.txt | tail -800 > $OUT/logcat-app.txt
exit 0
