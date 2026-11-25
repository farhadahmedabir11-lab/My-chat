const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const rulesUnitDir = path.dirname(require.resolve("@firebase/rules-unit-testing"));
const firebase = require(require.resolve("firebase/compat/app", { paths: [rulesUnitDir] }));
require(require.resolve("firebase/compat/firestore", { paths: [rulesUnitDir] }));

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice123uid";
const BOB_UID = "bob456uid";
const CHARLIE_UID = "charlie789uid";
const CHAT_ID = [ALICE_UID, BOB_UID].sort().join("_");

function sha256(email) {
  return crypto.createHash("sha256").update(email.trim().toLowerCase()).digest("hex");
}

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("publicProfiles").where("discoverable", "==", true).get());
  await assertFails(unauthDb.collection("chats").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("userSettings").doc(ALICE_UID).get());
});

test("User registration: owner creates user, publicProfile, username lock, emailLookup, and settings; PII isolated from others", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 1000);
  const aliceHash = sha256("alice@gmail.com");

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      fullName: "Alice Rahman",
      username: "alice_r",
      email: "alice@gmail.com",
      bio: "Hey there! Using My Chat",
      avatarColorHex: "#1565C0",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("publicProfiles").doc(ALICE_UID).set({
      uid: ALICE_UID,
      fullName: "Alice Rahman",
      username: "alice_r",
      bio: "Hey there! Using My Chat",
      avatarColorHex: "#1565C0",
      discoverable: true,
      online: true,
      lastSeen: now,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_r").set({
      uid: ALICE_UID,
      username: "alice_r",
      createdAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("emailLookup").doc(aliceHash).set({
      uid: ALICE_UID,
      username: "alice_r",
      fullName: "Alice Rahman",
      avatarColorHex: "#1565C0",
      discoverableByEmail: true,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("userSettings").doc(ALICE_UID).set({
      userId: ALICE_UID,
      discoverable: true,
      discoverableByEmail: true,
      showOnlineStatus: true,
      showReadReceipts: true,
      notificationsEnabled: true,
      showNotificationPreview: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Duplicate username reservation by Bob must fail
  await assertFails(
    bobDb.collection("usernames").doc("alice_r").set({
      uid: BOB_UID,
      username: "alice_r",
      createdAt: now,
    })
  );

  // Bob CANNOT read Alice's private user PII or settings
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("userSettings").doc(ALICE_UID).get());

  // Bob CAN search discoverable publicProfiles and lookup Alice by SHA-256 email hash
  await assertSucceeds(bobDb.collection("publicProfiles").where("discoverable", "==", true).get());
  await assertSucceeds(bobDb.collection("emailLookup").doc(aliceHash).get());

  // Unfiltered publicProfiles list query must fail
  await assertFails(bobDb.collection("publicProfiles").get());

  // Shadow update with ghost field must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Private email lookup respects discoverableByEmail privacy setting", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 1000);
  const bobHash = sha256("bob@gmail.com");

  await assertSucceeds(
    bobDb.collection("emailLookup").doc(bobHash).set({
      uid: BOB_UID,
      username: "bob_k",
      fullName: "Bob Karim",
      avatarColorHex: "#0277BD",
      discoverableByEmail: false,
      updatedAt: now,
    })
  );

  // Alice cannot lookup Bob by email when discoverableByEmail is false
  await assertFails(aliceDb.collection("emailLookup").doc(bobHash).get());
  // Non-existent email lookup returns cleanly without error
  await assertSucceeds(aliceDb.collection("emailLookup").doc(sha256("nobody@gmail.com")).get());
});

test("1:1 private chat creation, real-time messaging, status updates, soft delete, and third-party isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const created = new Date(Date.now() - 5000);

  // Non-deterministic chat ID must fail (prevents duplicate chat creation)
  await assertFails(
    aliceDb.collection("chats").doc("random_chat_id").set({
      participantIds: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      participantUsernames: { [ALICE_UID]: "alice_r", [BOB_UID]: "bob_k" },
      participantColors: { [ALICE_UID]: "#1565C0", [BOB_UID]: "#0277BD" },
      lastMessageText: "",
      lastMessageSenderId: "",
      lastMessageStatus: "SENT",
      unreadCounts: { [ALICE_UID]: 0, [BOB_UID]: 0 },
      createdAt: created,
      updatedAt: created,
    })
  );

  // Deterministic chat creation succeeds
  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).set({
      participantIds: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      participantUsernames: { [ALICE_UID]: "alice_r", [BOB_UID]: "bob_k" },
      participantColors: { [ALICE_UID]: "#1565C0", [BOB_UID]: "#0277BD" },
      lastMessageText: "",
      lastMessageSenderId: "",
      lastMessageStatus: "SENT",
      unreadCounts: { [ALICE_UID]: 0, [BOB_UID]: 0 },
      createdAt: created,
      updatedAt: created,
    })
  );

  // Alice sends a message
  const msgRef = aliceDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1");
  await assertSucceeds(
    msgRef.set({
      chatId: CHAT_ID,
      senderId: ALICE_UID,
      recipientId: BOB_UID,
      participantIds: [ALICE_UID, BOB_UID],
      text: "Hello Bob! How are you?",
      status: "SENT",
      deleted: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Bob queries his chats and messages using participantIds filter
  await assertSucceeds(
    bobDb.collection("chats").where("participantIds", "array-contains", BOB_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").where("participantIds", "array-contains", BOB_UID).get()
  );

  // Bob marks Alice's message as DELIVERED then SEEN
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").update({
      status: "SEEN",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob CANNOT edit Alice's message text
  await assertFails(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").update({
      text: "Tampered text",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice soft-deletes her own message
  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").update({
      deleted: true,
      text: "",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Charlie (unauthorized third user) CANNOT read chat, list messages, or write messages
  await assertFails(charlieDb.collection("chats").doc(CHAT_ID).get());
  await assertFails(charlieDb.collection("chats").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(
    charlieDb.collection("chats").doc(CHAT_ID).collection("messages").where("participantIds", "array-contains", ALICE_UID).get()
  );
  await assertFails(
    charlieDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_hack").set({
      chatId: CHAT_ID,
      senderId: CHARLIE_UID,
      recipientId: BOB_UID,
      participantIds: [ALICE_UID, BOB_UID],
      text: "Intruder!",
      status: "SENT",
      deleted: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Future timestamp manipulation rejected
  await assertFails(
    aliceDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_future").set({
      chatId: CHAT_ID,
      senderId: ALICE_UID,
      recipientId: BOB_UID,
      participantIds: [ALICE_UID, BOB_UID],
      text: "Future message",
      status: "SENT",
      deleted: false,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Reply message with replyTo metadata + chat unreadCounts update
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_reply").set({
      chatId: CHAT_ID,
      senderId: BOB_UID,
      recipientId: ALICE_UID,
      participantIds: [ALICE_UID, BOB_UID],
      text: "Doing great, thanks!",
      status: "SENT",
      deleted: false,
      replyToMessageId: "msg_1",
      replyToText: "Hello Bob! How are you?",
      replyToSenderName: "Alice Rahman",
      createdAt: new Date(Date.now() - 300),
      updatedAt: new Date(Date.now() - 300),
    })
  );

  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).update({
      lastMessageText: "Doing great, thanks!",
      lastMessageSenderId: BOB_UID,
      lastMessageStatus: "SENT",
      lastMessageTime: new Date(Date.now() - 300),
      [`unreadCounts.${ALICE_UID}`]: 1,
      [`unreadCounts.${BOB_UID}`]: 0,
      updatedAt: new Date(Date.now() - 300),
    })
  );
});

test("Contacts subcollection and profile/settings updates are owner-isolated", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc(BOB_UID).set({
      contactUid: BOB_UID,
      fullName: "Bob Karim",
      username: "bob_k",
      avatarColorHex: "#0284C7",
      createdAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").get()
  );

  // Bob cannot read or write Alice's contacts
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("contacts").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("contacts").doc(CHARLIE_UID).set({
      contactUid: CHARLIE_UID,
      fullName: "Charlie",
      username: "charlie_c",
      avatarColorHex: "#0D6EFD",
      createdAt: now,
    })
  );
});

test("Phase 2: Private media metadata & chunks are strictly participant-locked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = new Date(Date.now() - 1000);
  const mediaId = "media_photo_1";
  const sampleBytes = firebase.firestore.Blob.fromUint8Array(new Uint8Array([1, 2, 3, 4, 5, 6]));

  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId).set({
      mediaId,
      chatId: CHAT_ID,
      senderId: ALICE_UID,
      recipientId: BOB_UID,
      participantIds: [ALICE_UID, BOB_UID],
      mediaType: "IMAGE",
      mimeType: "image/jpeg",
      fileName: "sunset.jpg",
      sizeBytes: 6,
      chunkCount: 1,
      width: 1080,
      height: 720,
      thumbnailBytes: sampleBytes,
      uploadComplete: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId)
      .collection("chunks").doc("chunk_0").set({
        chunkIndex: 0,
        senderId: ALICE_UID,
        participantIds: [ALICE_UID, BOB_UID],
        data: sampleBytes,
        createdAt: now,
      })
  );

  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId).update({
      uploadComplete: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_media_1").set({
      chatId: CHAT_ID,
      senderId: ALICE_UID,
      recipientId: BOB_UID,
      participantIds: [ALICE_UID, BOB_UID],
      text: "Photo",
      status: "SENT",
      messageType: "IMAGE",
      mediaId,
      mediaMimeType: "image/jpeg",
      mediaSizeBytes: 6,
      mediaWidth: 1080,
      mediaHeight: 720,
      mediaChunkCount: 1,
      deleted: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob (participant) can read media metadata and chunks
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId).get()
  );
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId)
      .collection("chunks").where("participantIds", "array-contains", BOB_UID).get()
  );

  // Charlie (non-participant) cannot read or write media or chunks
  await assertFails(
    charlieDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId).get()
  );
  await assertFails(
    charlieDb.collection("chats").doc(CHAT_ID).collection("media").doc(mediaId)
      .collection("chunks").where("participantIds", "array-contains", ALICE_UID).get()
  );
});

test("Phase 2: WebRTC call signaling, ICE candidates, cleanup, and call history isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const callId = "call_12345";

  // Unauthorized third party cannot initiate a call impersonating Alice
  await assertFails(
    charlieDb.collection("calls").doc(callId).set({
      chatId: CHAT_ID,
      callerId: ALICE_UID,
      callerName: "Alice Rahman",
      callerUsername: "alice_r",
      callerColorHex: "#0D6EFD",
      calleeId: BOB_UID,
      calleeName: "Bob Karim",
      calleeUsername: "bob_k",
      calleeColorHex: "#0284C7",
      participantIds: [ALICE_UID, BOB_UID],
      callType: "VIDEO",
      status: "RINGING",
      offerSdp: "v=0...",
      durationSeconds: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alice creates WebRTC call offer
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).set({
      chatId: CHAT_ID,
      callerId: ALICE_UID,
      callerName: "Alice Rahman",
      callerUsername: "alice_r",
      callerColorHex: "#0D6EFD",
      calleeId: BOB_UID,
      calleeName: "Bob Karim",
      calleeUsername: "bob_k",
      calleeColorHex: "#0284C7",
      participantIds: [ALICE_UID, BOB_UID],
      callType: "VIDEO",
      status: "RINGING",
      offerSdp: "v=0...",
      durationSeconds: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alice adds ICE candidate
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).collection("candidates").doc("cand_1").set({
      senderId: ALICE_UID,
      participantIds: [ALICE_UID, BOB_UID],
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:1 1 UDP 2122252543 192.168.1.2 51234 typ host",
      createdAt: now,
    })
  );

  // Charlie cannot listen to call or ICE candidates
  await assertFails(charlieDb.collection("calls").doc(callId).get());
  await assertFails(
    charlieDb.collection("calls").doc(callId).collection("candidates")
      .where("participantIds", "array-contains", ALICE_UID).get()
  );

  // Bob accepts and sets answer SDP
  await assertSucceeds(
    bobDb.collection("calls").doc(callId).update({
      status: "ACCEPTED",
      answerSdp: "v=0 answer...",
      startedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // End call -> terminal state locked against reopening
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).update({
      status: "ENDED",
      endedAt: new Date(Date.now() - 500),
      durationSeconds: 42,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("calls").doc(callId).update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Record persistent call history and clean up temporary signaling docs
  await assertSucceeds(
    aliceDb.collection("callHistory").doc(callId).set({
      callId,
      callerId: ALICE_UID,
      callerName: "Alice Rahman",
      callerColorHex: "#0D6EFD",
      calleeId: BOB_UID,
      calleeName: "Bob Karim",
      calleeColorHex: "#0284C7",
      participantIds: [ALICE_UID, BOB_UID],
      callType: "VIDEO",
      outcome: "COMPLETED",
      durationSeconds: 42,
      createdAt: now,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).collection("candidates").doc("cand_1").delete()
  );
  await assertSucceeds(aliceDb.collection("calls").doc(callId).delete());
  await assertFails(charlieDb.collection("callHistory").doc(callId).get());
});

