# Changelog

## 2.0.1 — Global profile transaction repair

- Route Battery, Balanced, and Performance profile changes through one serialized Shizuku transaction service, shared by the dashboard and floating Edge Panel.
- Read and preserve each setting's original value, empty value, or unset state using a verified structured backup before applying changes.
- Verify applied settings, restore transaction values if a required write fails, and skip unsupported optional settings without blocking the rest of a profile.
- Report Shizuku service, authorization, shell, settings, backup, and per-profile diagnostics with failed operation details and exit codes.
- Avoid launching Roblox or marking a gaming session active when required profile changes fail.
- Stop inventing refresh-rate modes when the display does not report supported modes.

The release-variant APK is debug-signed for testing and is not intended for Play Store distribution.
