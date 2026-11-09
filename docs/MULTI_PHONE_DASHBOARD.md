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

## Dashboard tabs and live status

Seven horizontally scrollable navigation tabs: **Home, Calls, Messages, Lock, Live, Logs, Settings**. The global phone dropdown at the top selects the enrollment used by all device-specific screens. The selection is not a way to access private records belonging to another Firebase UID.

- **Home** listens to the selected Admin's `devices` query in realtime to show each phone's latest reported lock-sync time. **Checked recently** reflects the latest received sync timestamp; it is not a guarantee that the phone is online now.
- **Lock** shows the selected phone's keyguard transition events newest-first and can refresh automatically from a Firestore snapshot listener; date-filter, select, delete features are present.
- **Logs** shows device-scoped `syncLogs` (lock-sync and private-backup counts/status only) alongside locally recorded Admin troubleshooting events.
- **Messages** views opt-in, owner-private SMS backups by selected device under `users/{uid}/deviceSmsBackups/{deviceId}/messages`. The backup is created only by Personal_App when an eligible Android READ_SMS grant exists and the owner separately enables the feature. Admin_App itself has no READ_SMS permission. Different owner UIDs remain isolated.

## Why 15 seconds is not continuous background realtime

Personal_App checks for lock events roughly every 15 seconds **while the Activity is visible**, and queues checks for some screen transitions while its process is alive. Android may restrict or delay background work after the device locks. WorkManager periodic tasks are scheduled at 15 minutes minimum, not 15 seconds. Admin sees data as Firestore receives it; a timestamp is not a proof of present online state.

## Microphone playback controls and session recovery

In Admin_App > **Live**, select the named phone from the global dropdown and tap **Listen now** on an owner-started Personal_App session. Admin's **Loudspeaker** toggle chooses built-in speaker or earpiece/default route, and **Mute this phone** silences local received playback only (it does not mute or control another phone's microphone). The stream continues on the source phone after the owner switches apps or locks their screen because Personal_App uses an explicit user-started Android microphone foreground service with persistent notification and Stop action. When a publisher network handover replaces an active session, Admin will attempt to rejoin the new offer within a two-minute recovery window after the Admin user initially joined; this is not guaranteed across all networks, background restrictions, or process death.

Never claim microphone reactivation after force-stop or boot. On Android 14+ microphone FGS cannot be launched from BOOT_COMPLETED; microphone while-in-use restrictions apply. Owner must tap Start again after a restart. WebRTC audio stats meters show sampled input on Personal_App and inbound audio on Admin_App, not proof of real-world audibility.

## Compact dashboard (November 2026)

Home now uses simple colored tiles for linked phones and recent last-check timestamps, followed by compact selectable device cards. Calls, Messages and Lock use concise headings and retain newer-first sorting, date filters, pagination, and confirmed owner-authorized Firebase deletions. The per-device dropdown controls visible datasets. A recent sync timestamp is an observation, **not proof of continuous online status**. Long permissions and sync explanations belong in Personal_App's Access screen and diagnostic Logs rather than every Admin page.
