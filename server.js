const express = require('express');
const http = require('http');
const { Server } = require('socket.io');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
    cors: {
        origin: "*",
        methods: ["GET", "POST"]
    }
});

// Store active users: socket.id -> { name, socketId }
const users = new Map();

io.on('connection', (socket) => {
    console.log('Client connected:', socket.id);

    // ─── User joins ──────────────────────────────
    socket.on('user-join', (data) => {
        const userId = socket.id;
        users.set(userId, { name: data.name, socketId: socket.id });
        socket.join('room');
        io.to('room').emit('user-joined', { userId, name: data.name });
        const existing = Array.from(users.values());
        socket.emit('existing-users', existing);
    });

    // ─── User offers screen/mic ──────────────────
    socket.on('user-offer', (data) => {
        socket.to('room').emit('user-offer', {
            userId: socket.id,
            name: users.get(socket.id)?.name || 'Unknown',
            sdp: data.sdp
        });
    });

    // ─── IT answers ──────────────────────────────
    socket.on('it-answer', (data) => {
        io.to(data.userId).emit('it-answer', { sdp: data.sdp });
    });

    // ─── ICE candidates ──────────────────────────
    socket.on('user-ice-candidate', (data) => {
        socket.to('room').emit('user-ice-candidate', {
            userId: socket.id,
            candidate: data.candidate
        });
    });

    socket.on('it-ice-candidate', (data) => {
        io.to(data.userId).emit('it-ice-candidate', {
            candidate: data.candidate
        });
    });

    // ─── Mic state ───────────────────────────────
    socket.on('user-mic-toggle', (data) => {
        socket.to('room').emit('user-mic-state', {
            userId: socket.id,
            on: data.on
        });
    });

    socket.on('it-mic-state', (data) => {
        socket.to('room').emit('it-mic-state', { on: data.on });
    });

    // ─── Focus / unfocus ─────────────────────────
    socket.on('it-focus', (data) => {
        io.to(data.userId).emit('it-focus', {});
    });

    socket.on('it-unfocus', (data) => {
        io.to(data.userId).emit('it-unfocus', {});
    });

    // ─── Disconnect user ─────────────────────────
    socket.on('it-disconnect-user', (data) => {
        const userId = data.userId;
        const user = users.get(userId);
        if (user) {
            // Notify everyone that user left
            io.to('room').emit('user-left', { userId });
            // Disconnect the user's socket
            const userSocket = io.sockets.sockets.get(userId);
            if (userSocket) {
                userSocket.emit('session-ended');
                userSocket.disconnect(true);
            }
            users.delete(userId);
        }
    });

    // ─── IT refresh ──────────────────────────────
    socket.on('it-refresh', () => {
        const existing = Array.from(users.values());
        socket.emit('existing-users', existing);
    });

    // ─── IT joins ────────────────────────────────
    socket.on('it-join', () => {
        socket.join('room');
        const existing = Array.from(users.values());
        socket.emit('existing-users', existing);
    });

    // ─── Disconnect ──────────────────────────────
    socket.on('disconnect', () => {
        if (users.has(socket.id)) {
            users.delete(socket.id);
            socket.to('room').emit('user-left', { userId: socket.id });
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`);
});
