# DeLorean Tube

YouTube-App für Android (32-bit, armeabi-v7a, ab Android 5.0).

Created by DeLorean23

## Funktionen

- **Mit Google anmelden** über die normale Google-Kontoauswahl (Konto antippen, fertig)
- Mit Konto: Abo-Feed, Liste deiner Abos, deine Playlists, „Mag ich“-Videos, Abonnieren/Deabonnieren, Liken
- Startseite mit Kategorien (Trends, Musik, Gaming, Live, Podcasts, Filme & Serien), Suche, Kanäle, Playlists
- **Keine Werbung**: Videos werden direkt geladen, es wird gar keine Werbung abgerufen
- **Hintergrund-Wiedergabe**: Ton läuft weiter, wenn die App im Hintergrund ist oder der Bildschirm aus ist
  (Steuerung über Benachrichtigung und Sperrbildschirm). Im Hintergrund wird nur der Ton geladen.
- Schalter **„Nur Ton“** (spart Daten), Qualitätsauswahl (getrennt für WLAN und mobile Daten)
- **Download** mit Auswahl:
  - **Audio (MP3)**, 192 kbit/s, mit Titel und Interpret → Ordner `Music/DeLorean Tube`
  - **Video (MP4)** mit Nachfrage nach der Auflösung → Ordner `Movies/DeLorean Tube`
- YouTube-Links aus anderen Apps öffnen oder über „Teilen“ an die App schicken
- Lokaler Verlauf

## Google-Login einrichten (einmalig, ca. 10 Minuten)

Google erlaubt die Anmeldung nur für Apps, die in der Google Cloud Console eingetragen sind.
Das ist kostenlos.

1. https://console.cloud.google.com öffnen und mit deinem Google-Konto anmelden.
2. Oben **Projekt auswählen → Neues Projekt**, Name z. B. `DeLorean Tube`, **Erstellen**.
3. Links **APIs & Dienste → Bibliothek**, nach **YouTube Data API v3** suchen, **Aktivieren**.
4. Links **APIs & Dienste → OAuth-Zustimmungsbildschirm** (bzw. **Google Auth Platform**):
   - App-Name `DeLorean Tube`, deine E-Mail als Support- und Entwickler-Mail, Zielgruppe **Extern**.
   - Unter **Zielgruppe / Testnutzer** deine Google-Mail-Adresse hinzufügen.
   - Danach **App veröffentlichen** („In Produktion“), sonst musst du dich alle 7 Tage neu anmelden.
     Beim Anmelden zeigt Google dann „Google hat diese App nicht überprüft“ → **Erweitert → Weiter zu DeLorean Tube**.
5. Links **Clients** (bzw. **Anmeldedaten → Anmeldedaten erstellen → OAuth-Client-ID**):
   - Anwendungstyp: **Android**
   - Paketname: `de.delorean23.tube`
   - SHA-1-Zertifikatsfingerabdruck:
     ```
     38:A3:F8:6F:E6:57:CE:9B:F9:F8:0F:49:A9:BF:C1:4F:80:F6:38:64
     ```
   - **Erstellen**. Eine Client-ID musst du nirgends eintragen, Google erkennt die App an Paketname + SHA-1.
6. App öffnen → oben rechts aufs Konto-Symbol → Google-Konto auswählen → Zugriff erlauben.

Der SHA-1 gehört zum Schlüssel `youtube/signing.jks` im Repo. Wird der Schlüssel ausgetauscht,
muss der neue SHA-1 in Schritt 5 eingetragen werden.

## APK bauen

Bei jedem Push baut GitHub Actions (Workflow **YouTube-App bauen**) automatisch die APK und
veröffentlicht sie unter **Releases** als `DeLoreanTube-32bit.apk`.

Android Studio: `gradlew :youtube:assembleRelease` → `youtube/build/outputs/apk/release/youtube-release.apk`

## Grenzen

- Die Startseiten-Empfehlungen und der Wiedergabeverlauf deines Kontos sind über die offizielle
  YouTube-Schnittstelle nicht abrufbar. Statt „Empfohlen“ gibt es die Kategorien und den Abo-Feed,
  der Verlauf wird in der App gespeichert.
- Kommentare werden nicht angezeigt.
- Die MP3-Umwandlung läuft auf dem Handy; auf alten Geräten dauert ein 4-Minuten-Lied ca. 1–3 Minuten.
- MP4 gibt es bis zur höchsten H.264-Qualität, die YouTube anbietet (meist 1080p). 1440p/4K liefert
  YouTube nur als VP9/AV1, das passt nicht in eine normale MP4-Datei für alte Handys.
- Ändert YouTube etwas an der Seite, muss die NewPipeExtractor-Version in `youtube/build.gradle`
  aktualisiert werden (gleiche Version wie die aktuelle NewPipe-App).
- Downloads nur für den privaten Gebrauch; YouTubes Nutzungsbedingungen erlauben Downloads eigentlich nicht.

## Bibliotheken

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0)
- jump3r / LAME (Java-MP3-Encoder, LGPL, siehe `src/main/java/de/delorean23/tube/lame/COPYING-LGPL.txt`)
- AndroidX Media3, Glide, OkHttp, Google Play Services Auth
