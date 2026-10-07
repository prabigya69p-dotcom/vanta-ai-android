# VANTA — Android AI Assistant

VANTA is a clean, adaptive Android AI assistant built with Kotlin + Jetpack Compose.

## Feature set in this build

- Smart routing with Research / Study / Create / Developer / Mission / Focus / Fact-check / Compare modes
- Persistent conversation context with temporary-chat support
- Encrypted API-key storage and configurable model
- Photo attachments and camera capture
- File picker with image, text-file and first-page PDF vision support
- Multimodal image analysis
- Streaming normal-chat responses
- Web research with source links
- Long-term local memory: “remember …”, “what do you remember”, “forget …”
- Local actions: open common apps, timer, alarm, time, calculator, media-control request
- Interruptible voice input and configurable TTS speed/pitch
- Optional visible “Hey VANTA” foreground wake listener
- Image generation through the current GPT Image model
- Android share-in for images
- Project / Space context
- Dark-blue VANTA HUD-style interface
- GitHub Actions debug APK build

The implementation intentionally keeps sensitive OS actions permission-gated and uses Android system surfaces where possible.