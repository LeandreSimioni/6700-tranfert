# CLAUDE.md — 6700 Transfer

Contexte projet et règles à NE PAS oublier entre sessions.

## Projet

Application Android (Kotlin) qui transfère automatiquement les photos/vidéos d'un Sony a6700 vers un Pixel Android via câble USB.

- **Package** : `fr.simioni.a6700transfer`
- **minSdk** : 26 / **targetSdk** : 35
- **Branche de dev** : `main` (travailler directement sur main)
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
| `version.properties` | `versionCode=N` — lu par UpdateChecker (URL: raw.githubusercontent.com/.../main/version.properties) |

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
<!-- FAUX -->
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
// CORRECT
val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
else
    PendingIntent.FLAG_UPDATE_CURRENT
```

### 3. Release GitHub créée en draft → APK 404
`gh release create latest-build ... "apk#nom.apk"` suffit sans `--draft`.

### 4. BuildConfig.VERSION_NAME non disponible sans buildConfig=true
```kotlin
buildFeatures { buildConfig = true }
```

### 5. MTP FORMAT_EXIF_JPEG seulement → 0 photo si RAW
Toujours scanner aussi `0x3800` + formats vidéo.

### 6. catch (_: Exception) swallow les erreurs
```kotlin
} catch (e: Exception) {
    log("ERREUR: ${e.javaClass.simpleName}: ${e.message}")
}
```

### 7. IntArray? n'a pas isNullOrEmpty() → erreur de compilation
```kotlin
// FAUX
if (storageIds.isNullOrEmpty()) ...

// CORRECT
if (storageIds == null || storageIds.isEmpty()) ...
```

## CI/CD

- Workflow : `.github/workflows/build.yml` — déclenché sur push vers `main`
- Release tag : `latest-build` (supprimée et recrée à chaque build)
- APK : `6700-transfer-debug.apk`
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

## Sony a6700 — VID/PID

- VID Sony : `0x054C`
- Deux devices à la connexion : hub USB (PID=0x96F/0xD68) + ILCE-6700 (PID=0xE76/0xE77)
- `UsbReceiver` filtre sur `device.vendorId == 0x054C`
- En MSC, filtrer `/storage/emulated/` = stockage interne à ignorer
