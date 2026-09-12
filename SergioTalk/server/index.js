// Sergio Talk relay server — the ONLY server-side piece of this app.
//
// What it does: nothing more than pass messages and call audio between
// phones that are each already connected to it. It does not store chat
// history, does not require accounts, and doesn't persist anything to disk.
//
// Protocol (JSON text frames unless noted):
//   -> {"type":"register","id":"...","name":"..."}
//        First message a client must send. Registers this connection under
//        `id`. Server replies to everyone with an updated "presence" list.
//   -> {"type":"chat","id":"...","to":"...","text":"...","ts":...}
//        Chat message. `to` is a peer id, or "ALL" to broadcast to everyone
//        else currently connected. Server stamps `from`/`name` from the
//        sender's registration before forwarding.
//   -> {"type":"call-offer","to":"..."}
//        "I'd like to call this peer." Forwarded to them with from/name
//        stamped on, so their app can show/ring for it.
//   -> {"type":"call-answer","to":"...","accepted":true|false}
//        Forwarded to the caller. If accepted, the server marks these two
//        connections as "in a call together" so it knows how to route their
//        binary audio frames (see below).
//   -> {"type":"call-end","to":"..."}
//        Ends an in-progress call; un-pairs both sides.
//   <- {"type":"presence","online":[{"id":"...","name":"..."}, ...]}
//        Sent to everyone whenever someone connects or disconnects.
//
// BINARY frames carry raw call audio and carry NO routing header — the
// server just forwards a binary frame from one socket to whichever peer
// it's currently call-paired with (set up via call-answer above).

const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = process.env.PORT || 8080;

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('Sergio Talk relay is running.\n');
});

const wss = new WebSocketServer({ server });

// id -> { ws, name, callPeerId: string|null }
const clients = new Map();

function safeSend(ws, data) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(data);
}

function broadcastPresence() {
  const online = [...clients.entries()].map(([id, c]) => ({ id, name: c.name }));
  const payload = JSON.stringify({ type: 'presence', online });
  for (const [, c] of clients) safeSend(c.ws, payload);
}

function unpairCall(id) {
  const me = clients.get(id);
  if (!me || !me.callPeerId) return;
  const peer = clients.get(me.callPeerId);
  me.callPeerId = null;
  if (peer) {
    peer.callPeerId = null;
    safeSend(peer.ws, JSON.stringify({ type: 'call-end', to: id, from: id }));
  }
}

wss.on('connection', (ws) => {
  let myId = null;

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (!myId) return;
      const me = clients.get(myId);
      const peer = me?.callPeerId ? clients.get(me.callPeerId) : null;
      if (peer) safeSend(peer.ws, data);
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    if (msg.type === 'register') {
      myId = String(msg.id || '').trim();
      if (!myId) {
        ws.close(4000, 'Missing id');
        return;
      }
      clients.set(myId, { ws, name: msg.name || myId, callPeerId: null });
      console.log(`+ ${msg.name || myId} (${myId}) connected — ${clients.size} online`);
      broadcastPresence();
      return;
    }

    if (!myId || typeof msg.to !== 'string') return; // must register first
    const me = clients.get(myId);
    const target = clients.get(msg.to);

    switch (msg.type) {
      case 'chat': {
        const outgoing = JSON.stringify({ ...msg, from: myId, name: me.name });
        if (msg.to === 'ALL') {
          for (const [otherId, c] of clients) {
            if (otherId !== myId) safeSend(c.ws, outgoing);
          }
        } else if (target) {
          safeSend(target.ws, outgoing);
        }
        break;
      }
      case 'call-offer': {
        if (target) safeSend(target.ws, JSON.stringify({ ...msg, from: myId, name: me.name }));
        break;
      }
      case 'call-answer': {
        if (msg.accepted && target) {
          me.callPeerId = msg.to;
          target.callPeerId = myId;
        }
        if (target) safeSend(target.ws, JSON.stringify({ ...msg, from: myId }));
        break;
      }
      case 'call-end': {
        me.callPeerId = null;
        if (target) {
          target.callPeerId = null;
          safeSend(target.ws, JSON.stringify({ ...msg, from: myId }));
        }
        break;
      }
    }
  });

  ws.on('close', () => {
    if (!myId) return;
    unpairCall(myId);
    clients.delete(myId);
    console.log(`- ${myId} disconnected — ${clients.size} online`);
    broadcastPresence();
  });

  ws.on('error', (err) => {
    console.error(`Socket error (${myId ?? 'unregistered'}):`, err.message);
  });
});

server.listen(PORT, () => {
  console.log(`Sergio Talk relay listening on port ${PORT}`);
});
