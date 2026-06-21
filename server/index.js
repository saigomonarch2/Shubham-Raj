/**
 * Ultra-low Latency Live Audio Monitoring WebSocket Server
 * Running on Node.js using 'ws'
 */

const { WebSocketServer, WebSocket } = require('ws');

// Configure listening port (default to 8080)
const PORT = process.env.PORT || 8080;
const wss = new WebSocketServer({ port: PORT });

console.log(`====================================================`);
console.log(`🎙️   Live Audio Monitor Server started on Port: ${PORT}`);
console.log(`🔗  WebSocket URL: ws://YOUR_LOCAL_IP:${PORT}`);
console.log(`====================================================\n`);

// Keep track of connected clients
const senders = new Set();
const receivers = new Set();

wss.on('connection', (ws, req) => {
    // Detect role from standard Header, Query Parameter (?role=X), or URL Path
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const rxHeader = req.headers['x-role'] || '';
    const queryRole = url.searchParams.get('role') || '';
    const pathRole = url.pathname === '/sender' ? 'sender' : (url.pathname === '/receiver' ? 'receiver' : '');

    let role = 'receiver'; // default to receiver for safe fallback
    if (
        rxHeader.toLowerCase() === 'sender' || 
        queryRole.toLowerCase() === 'sender' || 
        pathRole === 'sender'
    ) {
        role = 'sender';
    }

    ws.roleName = role;
    ws.ipAddress = req.socket.remoteAddress;

    if (role === 'sender') {
        senders.add(ws);
        console.log(`📥 [Sender Connected] Total Senders: ${senders.size} | IP: ${ws.ipAddress}`);
    } else {
        receivers.add(ws);
        console.log(`📤 [Receiver Connected] Total Receivers: ${receivers.size} | IP: ${ws.ipAddress}`);
    }

    // Handle incoming messages
    ws.on('message', (data, isBinary) => {
        // Broadcaster Rule: Forward binary stream frames (PCM chunks) instantly from Sender to Receivers
        if (ws.roleName === 'sender') {
            if (isBinary) {
                // Instantly forward binary audio byte array to all receivers, skipping buffer or stores
                receivers.forEach(client => {
                    if (client.readyState === WebSocket.OPEN) {
                        client.send(data, { binary: true });
                    }
                });
            } else {
                // If sender emits diagnostic text: log or broadcast
                console.log(`🗣️  [Sender Info]: ${data.toString()}`);
            }
        } else {
            // Receivers shouldn't usually stream audio back, but if they send messages we handle or log it
            console.log(`💬 [Receiver Message]: ${data.toString()}`);
        }
    });

    // Handle disconnection
    ws.on('close', (code, reason) => {
        if (ws.roleName === 'sender') {
            senders.delete(ws);
            console.log(`❌ [Sender Disconnected] Total Senders: ${senders.size} | Reason: ${reason} (${code})`);
        } else {
            receivers.delete(ws);
            console.log(`❌ [Receiver Disconnected] Total Receivers: ${receivers.size} | Reason: ${reason} (${code})`);
        }
    });

    ws.on('error', (err) => {
        console.error(`⚠️  [Socket Error] ${ws.roleName}: ${err.message}`);
    });
});

// Periodic heartbeat to clean broken TCP connections
const interval = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.readyState === WebSocket.CLOSED) {
            senders.delete(ws);
            receivers.delete(ws);
            return;
        }
    });
}, 30000);

wss.on('close', () => {
    clearInterval(interval);
});
