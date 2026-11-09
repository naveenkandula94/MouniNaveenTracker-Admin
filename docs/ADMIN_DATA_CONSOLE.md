# Admin Data Console — Firebase collections, date filters, and deletion

## Why Admin showed 0 calls while Collector backed up more than 1,400

The previous Admin screen read only `devices/{deviceId}/callLogs`, a collection populated by explicitly shared call records. Collector's automatic private backup instead writes to `users/{ownerUid}/callBackups`.

The new **Admin > Calls > My backups** screen reads the second collection using **the Firebase UID of the account currently signed in to Admin**. Your Firebase screenshots show the same UID signed into both Android apps, so this account can view its own private backup. **This does not grant a different administrator account access to a Collector owner's private backups.**

## Admin navigation

- **Home**: count aggregation for the signed-in account's automatic call backups and linked devices' shared calls, lock/unlock events, and live audio sessions. Refresh to update totals.
- **Calls**: switch between **My backups** (`users/{currentAuthUid}/callBackups`) and **Shared calls** (`devices/{selectedDeviceId}/callLogs`).
- **Events**: lock/unlock records under `devices/{selectedDeviceId}/usageEvents`.
- **Live**: owner-started, consented WebRTC microphone listening.
- **More**: local Admin diagnostic logs, Firebase connection status, authorized device linking, and audio session records/status (`devices/{deviceId}/liveAudio`). SDP and ICE message contents are not displayed.

## Viewing and filtering

The Calls, Events, and Audio Sessions lists offer **All, Today, 7 days, 30 days, Dates**. The custom date range uses `YYYY-MM-DD` start and through dates inclusive in the Android device's local timezone.

The screen subscribes to the most recent 50 matching documents via a Firestore snapshot listener. Use **Load older records** to page through the entire result set instead of loading 1,400+ rows into memory at once. The count summary is a server-side Firestore aggregation. The number of loaded records and the total matching count are intentionally different.

## Deleting from Firebase

- **Select loaded** selects the currently displayed page(s).
- **Delete selected** permanently removes chosen documents from the selected collection.
- **Delete all matching** permanently removes *all* matching documents in the active source/date range, even those not yet loaded. This is not limited to the first 50.
- The app asks for a separate confirmation. Firestore permissions are enforced by the signed-in UID and linked device ownership.
- Audio session deletion also purges the two `publisherIce` and `listenerIce` subcollections before deleting the session document.
- Admin local diagnostic logs can be cleared independently.

### Important backup caveat

Deleting Firebase automatic backup documents does **not** delete calls from the Android handset. If the Collector's automatic private backup is still enabled, matching calls that are checked again may be backed up again. To keep them gone, first disable backup in **Collector > Calls**. Backup suppression across devices is not yet implemented.

Deletion does not remove Firebase Authentication users or device enrollment records.

## Required setup

1. Open Firebase Console for project `mouninaveentracker`.
2. Ensure `admins/{current Firebase Auth UID}` has `role: "admin"`.
3. Open **Cloud Firestore > Rules** and publish the **complete** `firebase/firestore.rules` from this branch, which adds device-owner delete rights for `usageEvents`, `liveAudio`, and nested ICE documents. `users/{uid}/callBackups` remains owner-only.
4. Download the latest Admin APK from this branch's successful GitHub Actions build and install it over the previous Admin APK.
5. Open Admin > Calls > My backups. It will display records when Admin and Collector are using the **same Firebase Authentication UID**.
6. To inspect call records stored under a different owner UID, that owner must sign into their own account. A linked Admin enrollment by itself does not authorize reading that owner's private backup.

## Limitations

- Firestore query results can be temporarily unavailable offline. Deletion requires authorized network access; failure messages are shown in the UI.
- A success in GitHub Actions proves compilation/lint/tests, not real-device connectivity or installed Firebase rules.
- Large deletions require multiple Firestore batches and may partially complete if the network fails. Repeat after resolving any error.
- The app only shows data from supported project collections and metadata; it does not claim to display unrelated Firebase Authentication settings, inaccessible user documents, or audio recordings (which are not stored).
