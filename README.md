# VANTA — Android AI Assistant

VANTA is a clean, mobile-first Android AI assistant built with Kotlin and Jetpack Compose.

Included:
- Unified AI chat
- Automatic research routing
- Optional Research mode
- Tap-to-talk voice input
- Spoken replies
- Encrypted local API-key storage
- Configurable model
- GitHub Actions debug APK build

Build locally in Android Studio or AndroidIDE. GitHub Actions builds the debug APK automatically on pushes to main and exposes it as an artifact.

VANTA does not hard-code an API key. Add your own key in Settings. For production distribution, use a secure backend instead of shipping a provider key in the APK.
