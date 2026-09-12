# Sergio Talk relay server (internet mode)

This is the piece that lets two phones text/call each other over their own
internet connections when they're NOT physically near each other. It's
intentionally tiny: it forwards messages between exactly the two people
talking, and doesn't store anything.

This is the only server-side piece of Sergio Talk - the app itself is
otherwise just a client that connects here for both chat and calls.

## Deploy it for free on Render

1. Push this `server/` folder to its own GitHub repo (or a repo where this
   is the root - Render lets you point at a subfolder too).
2. Go to https://render.com, sign up (no credit card needed for the free
   tier), and choose **New > Web Service**.
3. Connect the GitHub repo.
4. Settings:
   - **Root Directory**: `server` (if this folder isn't already the repo root)
   - **Runtime**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Instance Type**: Free
5. Deploy. Render gives you a URL like `https://sergiotalk-relay.onrender.com`.
   Use `wss://sergiotalk-relay.onrender.com` (note `wss`, not `https`) as
   the server address you paste into the app.

## Free tier caveats (be aware of these, they're not bugs in this code)

- **Spins down after 15 minutes idle.** If nobody's connected for 15
  minutes, Render puts the server to sleep. The next connection takes
  roughly 30-60 seconds to "wake it up" - so the first message/call after
  a quiet period will be slow to connect, then normal after that.
- **One free service is fine for personal use** (you + whoever you're
  calling) but this is not built to scale to many simultaneous users -
  there's no database, no accounts, no persistence. That's on purpose,
  it keeps it free and simple for exactly your use case.
- Free-tier limits and platform behavior can change - check
  https://render.com/docs before relying on this long-term.

## Running it locally (for testing before you deploy)

```
cd server
npm install
npm start
```

Then use `ws://<your-computer's-LAN-IP>:8080` from a phone on the same
Wi-Fi to test the relay logic without deploying anywhere yet. (This local
test still isn't "real internet mode" between two separate networks - it's
just a way to check the server code works before you deploy it.)
