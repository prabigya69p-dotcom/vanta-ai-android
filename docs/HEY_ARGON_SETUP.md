# Enable the local Hey Argon detector

VANTA now uses Picovoice Porcupine with an AudioRecord PCM pipeline instead of asking Android's general speech recognizer to guess whether the wake phrase was spoken.

## One-time account setup

1. Create or sign in to a Picovoice Console account and copy the AccessKey.
2. Open the VANTA repository on GitHub.
3. Go to **Settings → Secrets and variables → Actions → New repository secret**.
4. Set the secret name to exactly `PICOVOICE_ACCESS_KEY` and paste the AccessKey as its value.
5. Run the Android workflow or push a new commit to the `feat/google-workspace-api-settings` branch.

The key is supplied through the GitHub Actions environment and is not stored as plaintext in the repository source. Because a mobile app must use the SDK key locally, it is also packaged into the APK; treat it as an app credential and rotate it if it is exposed.

## First launch

The first time hands-free mode starts, VANTA prepares a `Hey Argon` keyword model using the Picovoice SDK. This can take longer than later starts and may need internet access. Wake-word inference runs locally after initialization. Keep the foreground-service notification visible while hands-free mode is enabled.

If the notification says the AccessKey is missing, add the secret and rebuild. If it reports a model-generation or audio error, stop and restart hands-free mode after the reported issue is resolved.

## Permissions and device notes

- Grant microphone permission when Android asks.
- Android may require the user to enable **Display over other apps** before the optional floating status pill appears. Wake detection still works without the overlay.
- AEC and NoiseSuppressor are enabled only when the device reports support; vendor audio behavior varies.
- Android can restrict microphone foreground-service starts after reboot. Open VANTA and enable hands-free mode if the service was blocked.
