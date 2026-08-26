# Changelog

## 1.4

### Version detection fix
- Fixed version checker reporting an outdated version by parsing the version number from the download URL redirect chain instead of scraping page text. The page text on pgsharp.com can be stale (e.g. showing 0.425.0 when the actual download is for 0.425.1), while the download URL always contains the correct version encoded in the filename.

### Alarm permission UX
- Added an explanation dialog before requesting the "Alarms & reminders" permission, so users understand why it's needed before being sent to system settings.
- Removed the unconditional alarm permission request that fired on every app launch regardless of auto-check state.

## 1.3
- i18n, scheduler & worker improvements
