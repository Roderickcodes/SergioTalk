# Sergio Talk (prototype)

Chat and voice calling over the internet — each person uses their own
internet connection (Wi-Fi or mobile data), routed through a small relay
server you deploy for free. Doesn't matter if you're in different places or
on different networks; the relay server is the only thing both phones need
to reach.

Supports a public "Everyone" broadcast channel and **private 1:1
conversations**, with contacts discovered automatically as soon as they
connect to the same relay — no need to swap ids by hand.

## What's here
- `MainActivity.kt` — Compose UI: Chat tab (connect to your relay, contact
  chips, conversation view) and Call tab (contacts + in-call controls)
- `net/InternetManager.kt` — the whole transport: connects to the relay
  server over WebSocket, auto-discovers online contacts (presence), sends/
  receives chat, and handles call signaling + real-time PCM audio streaming
- `data/Message.kt` — message format (id, sender, destination, text)
- `data/MessageStore.kt` — dedup, per-recipient display filtering, contacts
- `call/CallState.kt` — shared call state (idle/connecting/ringing/in
  call/ended)
- `server/` — the relay server (see below) — the only server-side piece of
  this project

## How it works
1. You deploy `server/` somewhere (instructions below) and get a URL like
   `wss://your-relay.onrender.com`.
2. Both phones enter that same URL on the Chat tab and tap Connect. Each
   phone uses its *own* internet connection. As soon as you're connected,
   everyone else currently connected to that same relay shows up
   automatically as a contact chip.
3. The server is a dumb pipe: it doesn't store messages or call audio, it
   just forwards them to whichever connected phone they're addressed to (or
   everyone, for "ALL" broadcasts). If the other phone isn't connected right
   now, whatever you send is simply dropped — they need to be online at the
   same time, like most simple chat relays.
4. Calls: tap Call next to a contact on the Call tab. The other person sees
   an incoming-call prompt with Accept/Decline, then two-way audio streams
   as raw 16-bit PCM over the same WebSocket connection.

### Deploying the relay server (free)
Using [Render](https://render.com)'s free web service tier — no card
needed, 750 free hours/month. One real trade-off: it goes to sleep after 15
minutes idle and takes 30-60 seconds to wake up on the next connection
(the app's "Connecting…" state accounts for this).

1. Push the `server/` folder to a GitHub repo (or use Render's
   "Deploy from a public Git repo" pointing at wherever you host it).
2. On [render.com](https://render.com) → **New** → **Web Service** → connect
   that repo.
3. Settings: **Runtime** = Node, **Build Command** = `npm install`,
   **Start Command** = `npm start`. Leave everything else default, choose
   the **Free** instance type.
4. Deploy. Render gives you a URL like `https://your-relay.onrender.com` —
   in the app, use `wss://your-relay.onrender.com` (swap `https` → `wss`).
5. To test locally first without deploying anything: run `npm install &&
   npm start` in `server/` on your computer, then on the app use
   `ws://<your computer's local IP>:8080` — note plain `ws://` only works
   for local testing on the same network; real deployment needs `wss://`
   (encrypted) since Android blocks unencrypted connections to the internet
   by default.

## Getting an APK without Android Studio (GitHub Actions)
This repo includes `.github/workflows/build-apk.yml`, which builds a debug
APK automatically and lets you download it — no local Android Studio setup
needed.

1. Push this whole project to a GitHub repo (the `app/`, `server/`, and
   `.github/` folders all at the repo root).
2. Push to your `main` branch (or open the repo's **Actions** tab and click
   **Run workflow** on "Build APK" to trigger it manually, any time).
3. Wait for the run to finish (a few minutes) — the **Actions** tab shows
   progress.
4. Open that finished run, scroll to **Artifacts**, and download
   `sergio-talk-debug-apk`. It's a zip containing `app-debug.apk`.
5. Copy `app-debug.apk` to your phone and open it to install (allow
   "install from unknown sources" when prompted, since it's not from the
   Play Store).

This is a **debug** build (unsigned, fine for installing on your own
device, not meant for distributing to others or the Play Store).

## Setup — you'll need Android Studio
This can't run inside this chat; it's a real native app project. To build it:

1. Install [Android Studio](https://developer.android.com/studio) (free).
2. Open Android Studio → **Open** → select the `SergioTalk` folder (this is
   the `app/` project — the sibling `server/` folder is separate,
   deployed independently as described above). The Android project doesn't
   include the Gradle wrapper binary — Android Studio will offer to
   generate it automatically on first open; accept that prompt.
3. Let Gradle sync (needs internet once, for the build itself).
4. Run on a phone or emulator (API 26+/Android 8.0+) — since everything
   goes through the internet relay, an emulator works fine here, unlike
   local-radio-based approaches.
5. Grant the microphone permission prompt (needed for calls).
6. Both phones/emulators: connect to the same relay URL on the Chat tab.
   Contacts appear automatically once connected.

## Known limitations of this pass
- No encryption yet (chat or call audio) — don't use this for anything
  sensitive as-is. The relay server sees everything that passes through it
  in plain text.
- No authentication — anyone who knows someone's id could claim to be them.
  Fine for a personal prototype between people who trust each other; not
  fine beyond that.
- No persistent identity — a fresh random ID is generated each app launch,
  so contacts don't carry over between sessions yet.
- No reconnect-on-drop handling — if the relay connection drops mid-call or
  mid-chat, you'll need to tap Connect again.
- Free relay hosting sleeps after 15 minutes idle (see deploy notes above).

None of these are hard blockers, they're just not built yet. Encryption is
probably the single most important thing to add next, before trusting this
with anything you wouldn't say in public.
