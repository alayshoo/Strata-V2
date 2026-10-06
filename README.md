# Strata

Native Android app (Kotlin + Jetpack Compose) for personal finance tracking.

## Stack

- Kotlin 2.4, Jetpack Compose (Material 3), single-module Gradle project
- AGP 9.4, Gradle 9.7 (wrapper), JDK 17+
- compileSdk 37, targetSdk 36, minSdk 31

## Build

```bash
./gradlew assembleDebug        # build debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # run unit tests
./gradlew installDebug         # install on a connected device
```

Requires an Android SDK; set `sdk.dir` in `local.properties` or `ANDROID_HOME`.
