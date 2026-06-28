# CLAUDE.md — 6700 Transfer

Contexte projet et règles à NE PAS oublier entre sessions.

## ⬇️ TÉLÉCHARGER L'APK

```
https://github.com/LeandreSimioni/6700-tranfert/releases/download/latest-build/6700-transfer-debug.apk
```

Ce lien est regénéré à chaque push sur `main` par le CI. Si le lien ne marche pas :
1. Vérifier que le dernier workflow sur `main` est en `success` dans l'onglet Actions
2. Aller sur https://github.com/LeandreSimioni/6700-tranfert/releases/tag/latest-build pour confirmer que le fichier `6700-transfer-debug.apk` est bien attaché

**Note signature APK** : Chaque build CI génère une clé debug différente. Pour mettre à jour sans désinstaller, il faudrait une clé de signature fixe (non implémenté). En attendant : désinstaller l'ancienne version avant d'installer la nouvelle.

## Projet

Application Android (Kotlin) qui transfère automatiquement les photos/vidéos d'un Sony a6700 vers un Pixel Android via câble USB.

- **Package** : `fr.simioni.a6700transfer`
- **minSdk** : 26 / **targetSdk** : 35
- **Branche de dev** : `main` (travailler directement sur main)
- **Version actuelle** : 1.6 (versionCode=6)

## Architecture

| Fichier | Rôle |
|---|---|
| `UsbReceiver.kt` | Détecte le Sony via USB_DEVICE_ATTACHED - log uniquement, pas de MTP |
| `UsbStorageReceiver.kt` | BroadcastReceiver MSC — ACTION_MEDIA_MOUNTED → TransferService |
| `TransferService.kt` | Service foreground — mode MSC uniquement |
| `TransferLog.kt` | Log SharedPreferences (200 entrées max, format dd/MM HH:mm:ss) |
| `UpdateChecker.kt` | Vérifie version.properties sur GitHub, télécharge APK via DownloadManager |
| `DownloadCompleteReceiver.kt` | Reçoit ACTION_DOWNLOAD_COMPLETE, ouvre l'installeur APK |
| `MainActivity.kt` | UI : date config, permissions, scan MSC manuel, logs, mise à jour |
| `ImageProcessor.kt` | Rotation EXIF JPEG |
| `version.properties` | `versionCode=N` — lu par UpdateChecker |

## Mode USB : MSC uniquement

- Sony en mode **Stocker/recharger (MSC)** (réglage sur la caméra)
- Android monte le volume → `ACTION_MEDIA_MOUNTED` → `UsbStorageReceiver` → `TransferService`
- Scan `DCIM/` : JPG, ARW, MP4, MOV, PNG, HEIC
- Le mode MTP est supprimé — ne plus y toucher
- Bouton "Scan MSC maintenant" pour déclenchement manuel

## Pièges connus — NE PAS refaire ces erreurs

### 1. Manifest : deux `<receiver>` avec le même `android:name` → INTERDIT
```xml
<!-- FAUX -->
<receiver android:name=".UsbReceiver">...</receiver>
<receiver android:name=".UsbReceiver">...</receiver>

<!-- CORRECT : un seul receiver, plusieurs intent-filter -->
<receiver android:name=".UsbReceiver" android:exported="true">
    <intent-filter>...</intent-filter>
    <intent-filter>...</intent-filter>
</receiver>
```

### 2. MTP supprimé — ne pas le réintroduire
`UsbReceiver` ne fait que logger la détection du Sony. Le transfert passe uniquement par MSC (`UsbStorageReceiver` + `ACTION_MEDIA_MOUNTED`). Ne jamais rappeler `startMtpTransfer` ou `PendingIntent.FLAG_MUTABLE` pour USB.

### 3. BuildConfig.VERSION_NAME non disponible sans buildConfig=true
```kotlin
buildFeatures { buildConfig = true }
```

### 4. MTP FORMAT_EXIF_JPEG seulement → 0 photo si RAW (ne s'applique plus, MTP supprimé)

### 5. IntArray? n'a pas isNullOrEmpty() → erreur de compilation
```kotlin
// FAUX
if (storageIds.isNullOrEmpty()) ...
// CORRECT
if (storageIds == null || storageIds.isEmpty()) ...
```

### 6. Release GitHub créée en draft → APK 404
Pas de `--draft` dans `gh release create`.

### 7. Signature APK inconsistante entre builds CI
Chaque build CI crée un nouveau debug.keystore. Pour installer une mise à jour sans désinstaller, il faut une clé fixe. Non résolu — documenter dans l'UI que l'utilisateur doit désinstaller manuellement.

## CI/CD

- Workflow : `.github/workflows/build.yml` — déclenché sur push vers `main`
- Release tag : `latest-build` (supprimée et recrée à chaque build)
- APK : `6700-transfer-debug.apk`
- Lien direct : `https://github.com/LeandreSimioni/6700-tranfert/releases/download/latest-build/6700-transfer-debug.apk`
- `version.properties` à la racine doit être mis à jour à chaque incrément de version

## Permissions Android

| Permission | Quand |
|---|---|
| `READ_MEDIA_IMAGES` | API 33+ |
| `READ_MEDIA_VIDEO` | API 33+ |
| `READ_EXTERNAL_STORAGE` | API 26-32 |
| `WRITE_EXTERNAL_STORAGE` | API < 29 seulement |
| `FOREGROUND_SERVICE_DATA_SYNC` | API 34+ |
| `INTERNET` | UpdateChecker |
| `POST_NOTIFICATIONS` | API 33+ |
| `REQUEST_INSTALL_PACKAGES` | DownloadCompleteReceiver |

## Sony a6700 — VID/PID

- VID Sony : `0x054C`
- Deux devices à la connexion : hub USB (PID=0x96F/0xD68) + ILCE-6700 (PID=0xE76/0xE77)
- `UsbReceiver` filtre sur `device.vendorId == 0x054C`, log uniquement
- En MSC, filtrer `/storage/emulated/` = stockage interne à ignorer
