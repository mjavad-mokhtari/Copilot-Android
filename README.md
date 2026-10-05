# Eting Android

The repository includes a standalone Android app module for Eting chat, account sessions, reminders, and Firebase push notifications.

## Build

Install JDK 17 or newer and Android SDK Platform 36 / Build Tools 36.0.0, accept the Android SDK licenses, then run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `mobile/build/outputs/apk/debug/mobile-debug.apk`.

## Firebase push notifications

Chat, account, and reminder APIs build without Firebase project credentials. To enable push-token registration and notification delivery, provide the app-specific `mobile/google-services.json` through a private configuration channel. Do not commit Firebase credentials or signing keys. The Google Services Gradle plugin is applied only when that file exists.

## Shared account data

The app uses the authenticated account session for chat history and reminders. Chat history is loaded from the server on app/tab entry and refreshed in the background. Reminder lists are refreshed on entry and retried in the background when the API is unavailable.
