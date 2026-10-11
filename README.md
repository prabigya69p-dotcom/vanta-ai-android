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
- Fish Audio text-to-speech with optional reference voice ID, plus Android system TTS fallback; configurable system TTS speed/pitch
- Optional visible “Hey VANTA” foreground wake listener
- Image generation through the current GPT Image model
- Android share-in for images
- Project / Space context
- Dark-blue VANTA HUD-style interface
- GitHub Actions debug APK build

The implementation intentionally keeps sensitive OS actions permission-gated and uses Android system surfaces where possible.

## Connected accounts and hands-free Argon

- Open VANTA Settings and choose **Connect Google Workspace**. Approve the requested read-only Gmail and Calendar scopes. Voice commands: "check my unread emails" and "what's on my calendar". Reconnect if Google authorization expires.
- For Notion, create an internal integration in your workspace, grant it read/insert content access, and share the target task database with the integration. Paste its token and database ID in VANTA Settings. The token is stored encrypted on-device; do not put it in source code or GitHub.
- Voice commands: "show my Notion tasks" and "add task to Notion <task name>".
- Enable **Hey Argon hands-free** and grant microphone permission. Saying only "Hey Argon" arms a follow-up utterance without opening chat; VANTA opens after the next command. Android battery and microphone restrictions can still interrupt recognition.
- Wake detection currently uses Android SpeechRecognizer, not a dedicated always-on wake-word model. Gmail is read-only; Calendar reads upcoming events only; Notion can list and create task titles in the configured database.
