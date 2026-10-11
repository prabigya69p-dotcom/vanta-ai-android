# Vanta integrations setup

## Google Gmail and Calendar
1. Open https://console.cloud.google.com/ and create/select a project.
2. Enable **Gmail API** and **Google Calendar API**.
3. Configure Google Auth Platform / OAuth consent screen. Add your own Google account as a test user while the app is in testing.
4. Create an **Android OAuth client** with package name `com.vanta.app` and the SHA-1 fingerprint for the certificate signing the APK. The GitHub Actions debug APK and any release APK can have different fingerprints.
5. Install Vanta, open Settings → Integrations → **Connect Google account**, and approve Gmail and Calendar permissions.
6. Gmail modify and Calendar event scopes are sensitive. Public distribution may require Google's verification. Keep this in test mode for personal testing until verification is complete.

The short-lived Google access token is stored in Android Keystore-backed encrypted preferences. If it expires, reconnect Google in Settings. This pass reads unread Gmail counts/subject metadata and today's primary-calendar events; outgoing mail is not sent automatically.

## Notion
1. Open https://www.notion.so/profile/integrations and create an internal integration.
2. Paste its secret into Vanta Settings → Integrations → Notion token. Never paste it into chat or commit it to GitHub.
3. Open the target database, choose **••• → Connections**, and add your integration.
4. Copy the database ID from its URL and save it in Vanta Settings.
5. Say “check my Notion tasks” to list page titles from the connected database.

The token is stored in Android Keystore-backed encrypted preferences. The integration can access only pages/databases you explicitly share with it.

## Voice commands
After connecting Google, try “Hey Argon, check my email”, “Hey Argon, what's on my calendar today”, or “Hey Argon, check my Notion tasks”. Enable microphone permission and keep the ongoing Argon notification visible. Android and some phone manufacturers may stop long-running microphone services; this is not a guaranteed OS-level assistant replacement.
