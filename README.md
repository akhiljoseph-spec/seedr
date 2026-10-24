# SeedrStreamer - Android & Android TV App

This repository contains the complete Android application for streaming media directly from Seedr.cc with genuine device OAuth authentication and ExoPlayer (Media3).

## Automated GitHub Releases
Whenever you push a git tag starting with `v` (e.g. `v1.0.0`), GitHub Actions will:
1. Compile the APK.
2. Create a GitHub Release with auto-generated release notes.
3. Attach `SeedrStreamer-v1.0.0.apk` directly to the release page.

### How to trigger a release:
```bash
git tag v1.0.0
git push origin v1.0.0
```
