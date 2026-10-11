# Vanta integrations setup

## Google Workspace: Gmail, Calendar, and Tasks
1. Open https://console.cloud.google.com/ and create/select a Google Cloud project.
2. Enable **Gmail API**, **Google Calendar API**, and **Google Tasks API**.
3. Configure Google Auth Platform / OAuth consent screen. Add your own Google account as a test user while the app is in testing.
4. Create an **Android OAuth client** with package name `com.vanta.app` and the SHA-1 fingerprint for the certificate signing the APK. The GitHub Actions debug APK and any release APK can have different fingerprints.
5. Add the key to GitHub without committing it: open the repository → **Settings → Secrets and variables → Actions → New repository secret**. Set the name to `GOOGLE_API_KEY` and paste the key as the secret value. The Android workflow passes it to Gradle only during the APK build. If the repository is public, restrict the key in Google Cloud and remember that a key embedded in an APK can be extracted.
6. Install Vanta, open Settings → Google Workspace → **Connect Google Workspace**, and approve Gmail, Calendar, and Tasks permissions.
7. Gmail modify/send, Calendar event, and Tasks scopes are sensitive. Public distribution may require Google's verification. Keep the OAuth app in test mode for personal testing until verification is complete.

**Important:** the API key is not a login and cannot access private Gmail, calendars, or tasks by itself. The user-consented OAuth token authorizes access. Vanta stores the key and OAuth token in Android Keystore-backed encrypted preferences. If access expires or permissions are missing, reconnect Google in Settings.

## Supported Google voice commands
- Read: “check my email”, “what's on my calendar”, “show my Google Tasks”.
- Create a task: “add Google task buy notebooks”.
- Send an email using the explicit format: “send email to person@example.com subject Hello body Your message”.
- Reply to the latest inbox email using the explicit format: “reply to latest email with Thanks, I'll check”.
- Create a Calendar event with 24-hour times: “schedule calendar event Project meeting on 2026-10-12 from 15:00 to 16:00”.

Sending email/replying and creating events are real account changes; use these commands only when you intend the action. The reply command targets the latest email in the inbox, so check it first if there is any ambiguity.

## Notion
1. Open https://www.notion.so/profile/integrations and create an internal integration.
2. Paste its secret into Vanta Settings → Notion. Never paste it into chat or commit it to GitHub.
3. Open the target database, choose **••• → Connections**, and add your integration.
4. Copy the database ID from its URL and save it in Vanta Settings.
5. Say “check my Notion tasks” to list page titles from the connected database.

The token and database ID are stored in Android Keystore-backed encrypted preferences. The integration can access only pages/databases you explicitly share with it.

## Hands-free note
Enable microphone permission and keep the ongoing Argon notification visible. Android and some phone manufacturers may stop long-running microphone services; this is not a guaranteed OS-level assistant replacement.
