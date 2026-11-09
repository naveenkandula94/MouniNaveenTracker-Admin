# Admin: owner-started live microphone listening

**Prerequisites:**
- Admin Firebase Authentication account exists.
- Firestore document `admins/{adminUid}` has field `role = "admin"`.
- The Collector is enrolled under `devices/{deviceId}` by the Admin app.
- The corresponding `firebase/firestore.rules` are published in Firebase Console.
- Both apps use the same Firebase project.

**Steps:**
1. On Collector, sign in, accept overall sharing consent, tick the separate live microphone consent, grant Android microphone permission, and press **Start live microphone**.
2. While Collector stays open, on Admin sign in and open **Live audio**.
3. Select enrolled device, press **Find live session**, then **Listen now**. Use **Disconnect** to stop listening.
4. On Collector, press **Stop microphone sharing** to stop transmitting. Collector can revoke overall consent at any time.

WebRTC carries encrypted audio. Firebase stores SDP/ICE session signaling data, not audio. For connections across restrictive carrier NAT networks, this initial implementation requires configuring TURN; STUN alone does not guarantee connectivity. Android restricts microphone sharing from the background. No remote start or hidden capture is implemented.

To view calls: Admin > Calls > Select device > Refresh. Only explicitly approved call records appear. For mobile call-log access Android requires `READ_CALL_LOG` eligibility and installer allowlisting; a user checkbox alone cannot grant it.

For setup instructions see the matching Collector repository document `docs/LIVE_AUDIO_AND_CALL_LOG_SETUP.md`.
