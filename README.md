# Unpackr

Unpackr is an open-source Android application designed for inspecting, unpacking, and extracting flashable partition images (`boot.img`, `init_boot.img`, `vbmeta.img`, `vendor_boot.img`, `super.img`, `system.img`, etc.) directly on Android devices.

It operates entirely in user-space with **zero native binary dependencies (`.so` / NDK)**. The entire decoding engine is written in Kotlin and Android ART-compatible Java standard libraries.

---

## Technical Highlights

- **User-Space Sandbox & Zero Native Binaries**: Runs inside the standard Android application sandbox using high-performance NIO channels (`FileChannel`, `ByteBuffer`). Does not bundle compiled ELF binaries.
- **HTTP Range Streaming**: Supports inspecting and extracting partitions directly from remote download URLs (e.g., official factory image links or mirrors) using HTTP `Range: bytes=start-end` requests. Eliminates the requirement to download 5–10 GB archives to local storage just to extract a 64 MB kernel image.
- **Selective Extraction**: Pick only the partitions you need (such as `boot.img` for patching or `vbmeta.img` for signing) instead of decompressing the entire multi-gigabyte package.
- **Cryptographic Verification**: Automatically verifies block-level and partition-level SHA-256 hashes against embedded manifests.
- **Background Execution**: Long-running extraction tasks run inside an Android Foreground Service with wake locks and notification progress reporting.
- **Modern Jetpack Compose UI**: Pure Material Design 3 interface designed for clarity, high density, and operational safety.

---

## Supported Formats & Architecture

| Container Format | Key Signatures / Structure | Extraction Pipeline |
| :--- | :--- | :--- |
| **Android A/B Payload** | `CrAU` (v2), `payload.bin`, OTA ZIP | Custom Protobuf wire decoder (`update_metadata.proto`), extent-based block mapping, chunk decompression (LZ4, ZSTD, BZIP2, XZ), per-operation SHA-256 validation. |
| **Android Sparse Images** | `0xED26FF3A` (`SPARSE_HEADER_MAGIC`) | Resolves `CHUNK_TYPE_RAW`, `CHUNK_TYPE_FILL`, and `CHUNK_TYPE_DONT_CARE`. Reconstructs standard unsparse raw block images. |
| **Dynamic Partitions (`super.img`)** | `0x616C0D41` (`LP_METADATA_GEOMETRY_MAGIC`) | Parses `liblp` metadata tables, partition descriptor groups, and extent maps. Assembles contiguous or sparse logical partitions (`system`, `vendor`, `product`, `odm`, `system_ext`). |
| **Fastboot Factory ZIP** | `PK\x03\x04` / `PK\x01\x02` (ZIP32/ZIP64) | Positional Central Directory parser with nested `image-*.zip` discovery and anti-Zip-Slip path traversal verification. |
| **Android Block OTA (`new.dat`)** | `*.transfer.list` + `system.new.dat[.br/.xz]` | Decodes transfer list commands (`new`, `erase`, `zero`), re-orders sparse block extents into linear disk images. |
| **Samsung Binary Archives** | USTAR POSIX TAR (`.tar` / `.tar.md5`) | Parses 512-byte USTAR record headers and streams real-time LZ4 frame decompression (`.img.lz4`) directly to storage. |
| **Proprietary Vendor Archives** | OFP (Oppo/Realme), OZIP, NB0, PAC | Diagnostic probe identifies format, displays technical explanation of vendor proprietary cipher requirements, and suggests standard desktop alternatives. |

---

## Architecture Overview

```
                          ┌──────────────────────────┐
                          │   FirmwareSource (NIO)   │
                          └─────────────┬────────────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
       FileChannelSource (SAF)                       HttpFirmwareSource (Range)
                 │                                             │
                 └──────────────────────┬──────────────────────┘
                                        │
                                        ▼
                             FormatDetector (Probing)
                                        │
         ┌──────────────────────────────┼──────────────────────────────┐
         ▼                              ▼                              ▼
  AbPayloadHandler              SuperImageHandler              SamsungTarHandler
  (CrAU v2 Engine)             (liblp Metadata Engine)         (TAR + LZ4 Frame)
         │                              │                              │
         └──────────────────────────────┼──────────────────────────────┘
                                        │
                                        ▼
                           ExtractionEngine (Flow API)
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
       ExtractionService (Worker)                     UnpackrViewModel
                 │                                             │
                 ▼                                             ▼
      System Notification                           Jetpack Compose UI
```

### IO & Memory Efficiency
Unpackr avoids buffering entire archives in RAM:
- Reads are performed through targeted byte windows (e.g., 64 KB to 2 MB) using zero-copy NIO channels.
- Archive structures (ZIP Central Directory, Protobuf headers, TAR records) are indexed positionally from the source without scanning every intervening byte.
- Streamed decompressors pipe directly into verified output files.

---

## Project Structure

```
unpackr/
├── .github/
│   └── workflows/
│       └── build-apk.yml           # Automated CI workflow building release & debug APKs
├── app/
│   ├── build.gradle.kts            # App module configuration & dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml # Service declarations & permissions
│       │   ├── java/com/unpackr/
│       │   │   ├── MainActivity.kt # Entry point & navigation routing
│       │   │   ├── engine/
│       │   │   │   ├── FormatDetector.kt       # Format identification heuristics
│       │   │   │   ├── Models.kt               # Partition definitions & event types
│       │   │   │   ├── handler/                # Format extraction implementations
│       │   │   │   │   ├── AbPayloadHandler.kt
│       │   │   │   │   ├── FastbootZipHandler.kt
│       │   │   │   │   ├── NewDatHandler.kt
│       │   │   │   │   ├── SamsungTarHandler.kt
│       │   │   │   │   ├── SparseImageHandler.kt
│       │   │   │   │   ├── SuperImageHandler.kt
│       │   │   │   │   └── UnsupportedAndSpecializedHandlers.kt
│       │   │   │   └── source/                 # IO source abstractions
│       │   │   │       ├── FileChannelSource.kt
│       │   │   │       ├── FirmwareSource.kt
│       │   │   │       └── HttpFirmwareSource.kt
│       │   │   ├── service/
│       │   │   │   └── ExtractionService.kt    # Foreground service worker
│       │   │   └── ui/                         # Jetpack Compose UI
│       │   │       ├── screens/                # Screen composables
│       │   │       └── viewmodel/              # State management
│       │   └── res/                            # Resources & string tables
│       └── test/                               # JVM engine unit tests
├── build.gradle.kts                # Root build script
├── gradle/                         # Gradle version catalog & wrapper
└── README.md
```

---

## Building from Source

### Prerequisites
- Android Studio Ladybug / Meerkat or standalone JDK 17+
- Android SDK with Platforms 29 through 36
- Gradle 8.11+ / 9.x

### Build Release APK
To build a signed release APK:
```bash
export KEYSTORE_PATH="/path/to/your/upload-keystore.jks"
export STORE_PASSWORD="your-keystore-password"
export KEY_PASSWORD="your-key-password"

gradle :app:assembleRelease
```
The compiled release APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

### Run Engine Tests
Unpackr features standard JVM unit tests validating Protobuf decoding, sparse header parsing, `liblp` metadata inspection, and ZIP Central Directory operations:
```bash
gradle :app:testDebugUnitTest
```

---

## Continuous Integration (GitHub Actions)

This repository includes a ready-to-use GitHub Actions workflow (`.github/workflows/build-apk.yml`) that:
1. Runs the test suite on every push and pull request.
2. Compiles both **Debug** and **Release APKs** (never AABs).
3. Uploads the generated APK files as workflow artifacts.
4. Automatically attaches the APKs to GitHub Releases when a version tag (`v*.*.*`) is pushed.

---

## Security & Scoped Storage

- **Storage Access Framework (SAF)**: Unpackr adheres strictly to Android Scoped Storage guidelines. It never requests broad file manager permissions (`MANAGE_EXTERNAL_STORAGE`).
- **Path Traversal Protection**: All archive entry filenames are sanitized to prevent directory traversal attacks (Zip-Slip vulnerability).
- **Integrity Validation**: SHA-256 hash checks are calculated inline as bytes are decompressed and written, immediately identifying corrupt blocks.

---

## License

This project is licensed under the Apache License 2.0. See the `LICENSE` file for details.
All format implementations were developed based on publicly available AOSP specifications.
