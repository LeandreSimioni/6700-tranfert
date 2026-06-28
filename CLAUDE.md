# 6700 Transfer

Application Android qui synchronise automatiquement les photos d'un Sony a6700 vers un Pixel 7a via USB.

## Stack

- Kotlin, minSdk 26, targetSdk 35, compileSdk 35
- AGP 8.7.0, Gradle 8.9, JDK 17
- `app/build.gradle.kts` — versionCode et versionName a bumper a chaque release
- `version.properties` (racine) — versionCode utilise par UpdateChecker pour detecter les mises a jour

## Structure

```
app/src/main/kotlin/fr/simioni/a6700transfer/
  MainActivity.kt       — UI principale, logs de diagnostic, bouton MAJ
  UsbReceiver.kt        — BroadcastReceiver USB (attach + permission MTP, mount MSC)
  TransferService.kt    — ForegroundService, copie MTP et MSC
  TransferLog.kt        — Logs persistants dans SharedPreferences (200 entrees max)
  UpdateChecker.kt      — Verifie version.properties sur GitHub, telecharge et installe APK
  ImageProcessor.kt     — Traitement EXIF des photos
```

## Modes USB

- **MTP** (recommande) : Sony en mode "Transfert de fichiers". Dialog permission Android une seule fois. `UsbManager.requestPermission()` avec `FLAG_MUTABLE` obligatoire (sinon `EXTRA_PERMISSION_GRANTED` n'est pas transmis).
- **MSC** : Sony en mode "Stockage de masse". Detection via `ACTION_MEDIA_MOUNTED` + verification Sony VID=0x054C dans la liste USB.

## CI/CD

`.github/workflows/build.yml` — build APK debug et publie sur GitHub Releases tag `latest-build`.
- Upload via `curl` direct sur l'API GitHub (pas `gh release create` ni `softprops/action-gh-release` — les deux echouaient silencieusement dans cet environnement)
- La release doit etre `draft: false` explicitement

## Lien de telechargement

```
https://github.com/LeandreSimioni/6700-tranfert/releases/download/latest-build/6700-transfer-debug.apk
```

## Bumper une version

1. `app/build.gradle.kts` : incrementer `versionCode` et `versionName`
2. `version.properties` : mettre le meme `versionCode`
3. Pousser sur `main` — le CI build et publie automatiquement
