# Eting Android

This repository contains the Android phone app and Wear OS reminder companion. The app shares authenticated chat history and reminders with the Eting web client.

## Build on the Eting server

The production build host has the Android SDK, Gradle dependency cache, and Google Maven redirector configured. From `/root/eting-android`, run:

```sh
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

The APKs are written to `mobile/build/outputs/apk/debug/mobile-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk`. The phone APK is served from the Eting login page at `/downloads/android`.

Firebase project configuration and server push credentials are private and must not be committed. The Google Services plugin is applied when `mobile/google-services.json` is present on the build host.
