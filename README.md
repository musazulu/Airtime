# Airtime Scanner

Android prototype that:
- captures a scratch card photo
- runs OCR with ML Kit
- extracts a likely airtime PIN
- opens the phone dialer with `*121*PIN#`

## Notes
- The app uses `ACTION_DIAL`, so the user confirms before dialing.
- OCR is heuristic-based. If the scan misses, the user can edit the detected PIN manually.
- You can test with a new camera photo or a gallery photo.

## Phone-only testing
1. Put the project in a GitHub repo from your phone browser.
2. Push a commit so GitHub Actions runs.
3. Download the `app-debug.apk` artifact from the workflow run.
4. Install the APK on the phone and test with the camera or gallery picker.
