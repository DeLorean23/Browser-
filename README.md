# AdBlock Browser

Android-Browser mit Werbeblocker, Popup-Sperre, Vollbild-Videos und Chromecast.
32-bit-Build (armeabi-v7a), läuft ab Android 5.0.

Created by DeLorean23

## Funktionen

- Adressleiste (Adresse oder Suchbegriff → DuckDuckGo)
- Werbeblocker: eingebaute Liste + große Filterliste (StevenBlack, ~80.000 Domains), wird beim ersten Start automatisch geladen, im Menü aktualisierbar
- Popups und neue Fenster werden blockiert, `window.open` ist abgeschaltet
- Sprünge in andere Apps (`intent:`, `market:`) werden blockiert
- Strenger Umleitungs-Schutz: automatische Weiterleitung auf fremde Domains ohne Antippen wird blockiert (im Menü abschaltbar, falls eine Seite dadurch nicht mehr geht)
- Videos im Vollbild (Querformat, Systemleisten ausgeblendet)
- Downloads direkter Dateilinks mit Nachfrage, landen im Ordner „Download“
- Chromecast: Cast-Symbol oben, sobald ein Chromecast im WLAN ist. Läuft ein Video und der Fernseher ist verbunden, fragt die App „Auf Fernseher“ oder „Hier abspielen“. Steuerung über Menü und Benachrichtigung
- Bildschirm spiegeln als Ausweichlösung

## APK bauen

### Variante A: GitHub (ohne Android Studio)
1. Neues Repository auf GitHub anlegen und diesen Ordner hochladen.
2. Der Workflow „APK bauen“ startet automatisch (Reiter **Actions**).
3. Nach ca. 5 Minuten unten beim Lauf unter **Artifacts** `AdBlockBrowser-32bit` herunterladen und entpacken.

### Variante B: Android Studio
1. Ordner in Android Studio öffnen und Gradle-Sync abwarten.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, oder im Terminal: `gradlew assembleRelease`
3. APK liegt in `app/build/outputs/apk/release/app-release.apk`

Die APK ist mit dem Debug-Schlüssel signiert und lässt sich direkt installieren
(„Installation aus unbekannten Quellen“ erlauben).

## Grenzen bei Chromecast
Der Chromecast holt das Video selbst aus dem Netz. Das klappt bei direkten MP4- und HLS-Videos.
Bei verschlüsselten Streams (DRM), `blob:`-Playern oder Servern, die nur Anfragen mit
bestimmten Headern annehmen, geht das nicht – dann im Menü „Bildschirm spiegeln“ nutzen.

## Anpassen
- App-Name: `app/src/main/res/values/strings.xml`
- Eigene Werbe-Domains: `app/src/main/assets/adhosts.txt` (eine pro Zeile)
