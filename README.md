# Copilot Android

Initial Android source snapshot imported from `.server-stage/eting-android`.

The snapshot contains the `mobile` app module sources for chat, reminders, authentication, and push notifications. It is not currently a standalone build: root Gradle settings/build files, the Gradle wrapper, `AndroidManifest.xml`, and Firebase `google-services.json` are not present in the source snapshot.

Do not commit Firebase credentials. Add the project configuration and provide `google-services.json` through the appropriate private build/deployment channel before building the app.
