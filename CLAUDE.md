# CLAUDE.md — 6700 Transfer

Contexte projet et règles à NE PAS oublier entre sessions.

## Projet

Application Android (Kotlin) qui transfère automatiquement les photos/vidéos d'un Sony a6700 vers un Pixel Android via câble USB.

- **Package** : `fr.simioni.a6700transfer`
- **minSdk** : 26 / **targetSdk** : 35
- **Branche de dev** : `claude/clever-wozniak-5vxdsu` (toujours travailler ici, pas sur main)
- **Version actuelle** : 1.5 (versionCode=5)

## Architecture

| Fichier | Rôle |
|---|---|
| `UsbReceiver.kt` | BroadcastReceiver USB Host (MTP) — USB_DEVICE_ATTACHED + USB_PERMISSION |
| `UsbStorageReceiver.kt` | BroadcastReceiver MSC — ACTION_MEDIA_MOUNTED |
| `TransferService.kt` | Service foreground — mode MTP et MSC |
| `TransferLog.kt` | Log SharedPreferences (200 entrées max, format dd/MM HH:mm:ss) |
| `UpdateChecker.kt` | Vérifie version.properties sur GitHub, télécharge APK via DownloadManager |
| `MainActivity.kt` | UI : date config, permissions, scan MSC manuel, logs, mise à jour |
| `ImageProcessor.kt` | Rotation EXIF JPEG |
| `version.properties` | `versionCode=N` — lu par UpdateChecker |

## Modes USB

### MSC (recommandé)
- Sony en mode **Stocker/recharger (MSC)**
- Android monte le volume → `ACTION_MEDIA_MOUNTED` → `UsbStorageReceiver` → `TransferService MODE_MSC`
- Scan direct `File` dans `DCIM/`, copie tous types : JPG, ARW, MP4, MOV, PNG, HEIC
- Bouton "Scan MSC maintenant" dans l'UI pour déclenchement manuel (API 30+ : `StorageManager.storageVolumes`)

### MTP (fallback)
- Sony en mode **Transfert de fichiers (MTP)**
- `ACTION_USB_DEVICE_ATTACHED` → `UsbReceiver` → demande permission → `TransferService MODE_MTP`
- Scan formats : `FORMAT_EXIF_JPEG`, `0x3800`, `FORMAT_MP4_CONTAINER`, `FORMAT_AVI`, `FORMAT_UNDEFINED_VIDEO`

## Pièges connus — NE PAS refaire ces erreurs

### 1. Manifest : deux `<receiver>` avec le même `android:name` → INTERDIT
```xml
<!-- FAUX : Android refuse deux declarations pour la meme classe -->
<receiver android:name=".UsbReceiver">...</receiver>
<receiver android:name=".UsbReceiver">...</receiver>

<!-- CORRECT : un seul receiver, plusieurs intent-filter -->
<receiver android:name=".UsbReceiver" android:exported="true">
    <intent-filter><action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" /></intent-filter>
    <intent-filter><action android:name="fr.simioni.a6700transfer.USB_PERMISSION" /></intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" android:resource="@xml/device_filter" />
</receiver>
```

### 2. PendingIntent FLAG_IMMUTABLE → permission USB toujours refusée sur API 31+
```kotlin
// FAUX : Android ne peut pas écrire EXTRA_PERMISSION_GRANTED=true dans un intent immutable
PendingIntent.FLAG_IMMUTABLE

// CORRECT
val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
else
    PendingIntent.FLAG_UPDATE_CURRENT
```

### 3. Release GitHub créée en draft → APK 404
Dans le workflow CI, toujours passer `--prerelease` ou s'assurer que `draft=false`.
La commande `gh release create latest-build ... "apk#nom.apk"` suffit sans `--draft`.

### 4. BuildConfig.VERSION_NAME non disponible sans buildConfig=true
```kotlin
// build.gradle.kts — obligatoire pour accéder à BuildConfig
buildFeatures { buildConfig = true }
```

### 5. MTP FORMAT_EXIF_JPEG seulement → 0 photo si l'utilisateur shoote en RAW
Toujours scanner aussi `0x3800` (images non-standard, ARW Sony) + formats vidéo.

### 6. catch (_: Exception) swallow les erreurs
Ne jamais ignorer silencieusement les exceptions — toujours logger :
```kotlin
} catch (e: Exception) {
    log("ERREUR: ${e.javaClass.simpleName}: ${e.message}")
}
```

### 7. IntArray? n'a pas isNullOrEmpty() → erreur de compilation
`mtpDevice.storageIds` retourne `IntArray?`. `isNullOrEmpty()` n'existe que sur `Collection?` et `String?`.
```kotlin
// FAUX — ne compile pas
if (storageIds.isNullOrEmpty()) ...

// CORRECT
if (storageIds == null || storageIds.isEmpty()) ...
// Kotlin smart-cast : storageIds est IntArray (non-null) après ce bloc
```

## CI/CD

- Workflow : `.github/workflows/build.yml` — déclenché sur push vers `claude/clever-wozniak-5vxdsu` et `main`
- Release tag : `latest-build` (supprimée et recrée à chaque build)
- APK : `6700-transfer-debug.apk`
- `version.properties` à la racine doit être mis à jour à chaque incrément de version
- UpdateChecker lit : `https://raw.githubusercontent.com/LeandreSimioni/6700-tranfert/claude/clever-wozniak-5vxdsu/version.properties`

## Permissions Android

| Permission | Quand |
|---|---|
| `READ_MEDIA_IMAGES` | API 33+ — lire images depuis volume USB |
| `READ_MEDIA_VIDEO` | API 33+ — lire vidéos depuis volume USB |
| `READ_EXTERNAL_STORAGE` | API 26-32 — lire stockage externe |
| `WRITE_EXTERNAL_STORAGE` | API < 29 seulement |
| `FOREGROUND_SERVICE_DATA_SYNC` | Obligatoire API 34+ pour service foreground dataSync |
| `INTERNET` | UpdateChecker |
| `POST_NOTIFICATIONS` | API 33+ — notifications transfert |

## Sony a6700 — VID/PID

- VID Sony : `0x054C`
- Deux devices apparaissent à la connexion : hub USB (PID=0x96F/0xD68) + ILCE-6700 (PID=0xE76/0xE77)
- `UsbReceiver` filtre sur `device.vendorId == 0x054C` — le hub échoue à l'ouverture MTP (normal, pas un crash)
- En MSC, pas de filtre VID nécessaire — on filtre sur le path (`/storage/emulated/` = stockage interne à ignorer)
