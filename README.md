# Settle

An Android gallery that treats your phone and your own Google Drive as one photo and video
library. New photos wait a grace period before backup, so throwaway shots never reach the cloud.
Originals are uploaded byte-for-byte and verified by checksum. Older items keep only a preview on
the phone and download on tap. Deleting once removes an item everywhere, with a recycle bin.

Status: early development, see [docs/phases.md](docs/phases.md). English and Turkish.

## Build and install

Needs JDK 21 and the Android SDK with platform 37. The phone must have USB debugging on.

```bash
./gradlew installDebug        # installs "Settle" as com.iltan.settle.debug
./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug
```

## Documentation

- [docs/spec.md](docs/spec.md): what the app must do.
- [docs/decisions/](docs/decisions/): why it is built this way.
- [docs/phases.md](docs/phases.md): what each development phase delivered and verified.
