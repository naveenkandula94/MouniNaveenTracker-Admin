# Admin_App: multiple phone dashboard

This version installs under the launcher label **Admin_App**, with a blue administrator shield icon, while retaining the existing package ID and Firebase account.

## Add an Android phone
1. Sign in to Admin_App. Open **Settings > Add or manage phones**.
2. Enter a **unique phone name**, e.g., OnePlus or Samsung S24.
3. Enter the intended Collector Authentication UID. Leave it blank to use the **same UID as Admin** for controlled same-account owner backup.
4. Tap **Add phone**. This creates a new `devices/{id}` Firestore enrollment; it does not create new Firebase Auth accounts.
5. On the second Android phone open **Personal_App > Account > This phone > Refresh linked phones**, then tap **Use this phone** on that new name.
6. In Admin_App, select that phone in the selector at the top of Home, Calls, Live or Logs.

## What each tab shows

**Home:** named device list with last sync; selected phone's lock/unlock and audio-session history. Date filters and newest-first ordering. Selected or filtered records can be deleted when authorized.

**Calls:** 
- **My automatic backup**: current phone's `users/{signedInUid}/deviceCallBackups/{selectedDeviceId}/calls`, only if Admin is authenticated as the same owner UID. Newest first, 100 at a time, with date filters, selection and deletion.
- **Shared records**: this phone's `devices/{selectedDeviceId}/callLogs`. This is separate from automatic private backups.
- **Legacy records**: `users/{signedInUid}/callBackups`, populated before per-device IDs. Cannot reliably tell which phone created the legacy records; do not assign them to OnePlus or Samsung. These records are shown separately for verification and clean-up.

**Live:** a live-audio session started explicitly on the selected phone. Audio uses WebRTC; Firebase stores signaling, not microphone recording.

**Logs:** selected phone's non-sensitive `devices/{selectedDeviceId}/syncLogs`, with latest first, plus the Admin app's local troubleshooting log. The Collector's older local-only diagnostics remain only on that phone.

**Settings:** Admin account, Add phone, and Firebase permission diagnostics.

## Security and deletion

Publish the latest **complete** `firebase/firestore.rules` in Firebase Console before new phone backups or Admin record deletion. Owner-private call backups have UID-only read and delete permission. Changing the selected phone never grants access to a different user's private backup. If a second phone has a different Collector UID, Admin can see only the authorized device metadata and separately shared records, not its owner-private full backup.

Firebase document deletion is permanent. Disable private backup on Personal_App before deleting all its backup records or the worker may create them again. Live session signaling deletion requires the session to be ended.

**The compiled build does not establish Play Store compliance, continuous real-time execution, or end-to-end reliability. Test each phone and the published rules.**
