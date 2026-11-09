# Owner Admin dashboard: all relevant Firebase data

## Why the old Admin Calls screen showed zero

There are **two distinct Firestore collections**:

- `users/{firebaseAuthUid}/callBackups/{callId}`: automatic owner-private call backups, written by the Collector worker; only the **same authenticated UID** can read and delete them.
- `devices/{deviceId}/callLogs/{callId}`: calls individually approved for sharing with the enrolled Admin. Deleting these does not delete the private backup.

The new Admin **Calls** screen offers **My automatic backup** (same-account only), and **Shared records**. This does not expose another account's communications to a separate Admin identity.

## Data views

- **Home**: linked device summary; **Show lock/unlock and audio-session data** opens a device data explorer.
- **Calls -> My automatic backup**: existing and newer owner-private backup documents under `users/{signedInUid}/callBackups`. Filters: All, Today, 7 days, 30 days, Custom YYYY-MM-DD range. Loads 100 results per page. Select records, delete selected, or confirm deletion of all records matching the date filter.
- **Calls -> Shared records**: older user-approved calls under `devices/{deviceId}/callLogs`. Select and delete records, or confirm deleting all shared call records.
- **Home -> Device activity -> Lock/unlock**: metadata stored in `usageEvents`, date windows and selection/deletion.
- **Home -> Device activity -> Audio sessions**: WebRTC session signaling **metadata only**. End live audio on Collector first; only ended session records can be deleted. Related ICE candidate documents are removed before the session document.
- **Live**: join user-started live microphone streams. Firebase contains signaling, not audio.
- **Logs**: recent app-local troubleshooting messages.

## Required Firebase rules

1. Open the Admin repository's `firebase/firestore.rules` file on branch `feature/owner-dashboard-complete`.
2. In Firebase Console for `mouninaveentracker`, open Firestore Database -> Rules.
3. Paste the COMPLETE rules, then click **Publish**.
4. Preserve the `admins/{UID}` document whose `role` is `admin`, and the enrolled `devices/{deviceId}` document.

The rules allow deletion of `usageEvents` by the enrolled Admin, deletion of `callLogs` by its Admin, and deletion of ended `liveAudio` sessions and their ICE candidates by the same enrolled Admin. Private `users/{uid}/callBackups` remain accessible **only** to an authenticated user with `request.auth.uid == uid`.

**Note:** A document like `users/{UID}` may not exist as a root document. This is normal when only `users/{UID}/callBackups` has records. The Firestore console may say the parent document does not exist; the nested callBackups documents still exist and are queryable.

## Safe deletion

- Deleting records in Firestore is permanent. Confirm in the app before deletion.
- If automatic Collector backup remains enabled, subsequent backup jobs may recreate records; disable backup first if you want cleanup to persist.
- Firebase deletes of a parent document do **not** recursively delete all descendant subcollections, which is why the Audio Session feature explicitly deletes ICE candidates first.
- Deleting Firebase records does **not** delete the phone's local call history.

## Limitations

- This is the same-account owner view, not a general license to read any other Collector account's private backups.
- Android call-log permission remains restricted and background backup intervals are OS-controlled.
- The latest APK must be tested with the actual Firebase project and Android devices; a green CI build verifies compilation and tests, not full production assurance.
