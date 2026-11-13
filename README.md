# Network-Based Chat System

A university network-programming project using **Java 17+**, TCP sockets, threads, and persistent chat logs. No third-party libraries needed.

## Requirements
- JDK 17 or newer (`java -version` and `javac -version`)
- Windows PowerShell, macOS Terminal, or Linux shell

## Compile
From the extracted `EnhancedJavaChat` directory:

```powershell
javac -encoding UTF-8 -d out src/*.java
```

On Windows PowerShell this works as written. Run once after edits.

## Run server
In terminal 1:

```powershell
java -cp out ChatServer
```

Default server address is `127.0.0.1:5555` (the server listens on port 5555 on all interfaces). Logs persist in `data/` and survive server restarts.

## Run multiple clients
Open separate terminals in the same project directory:

```powershell
java -cp out ChatClient --username Alice
java -cp out ChatClient --username Bob
java -cp out ChatClient --username Charlie
```

To connect across computers on a trusted LAN, use `--host SERVER_LAN_IP` on each client. The server host firewall must allow TCP port 5555. Do **not** port-forward this demonstration server to the public internet; it has no authentication or encryption.

## Client commands
- Type a message (without `/`) to send to your current room
- `/join project` (or `/use project`) creates/joins the room and switches you to it
- `/rooms` shows all rooms created since server startup
- `/users` shows connected usernames
- `/dm Bob Hello there` sends a direct message to Bob
- `/history` displays up to 20 messages from your current room
- `/dmhistory Bob` displays up to 20 direct messages with Bob
- `/quit` disconnects
- `/help` prints commands

## Live demo walkthrough
1. Start server and connect Alice, Bob, and Charlie.
2. Run `/users` to show online users.
3. Alice and Bob run `/join project`; Charlie stays in the `lobby`.
4. Alice sends `Welcome to our project room!`; only Alice and Bob see it.
5. Alice runs `/dm Charlie Hi privately!`; only Alice and Charlie see it.
6. Bob runs `/history` to retrieve room history.
7. Alice disconnects and reconnects with the same username, joins `project`, and runs `/history`. Previously stored messages appear.
8. Alice runs `/dmhistory Charlie` to retrieve private messages.

## Design
- `ChatServer.java`: accepts connections; one thread per user; routes room/direct messages; monitors presence.
- `ChatClient.java`: reads console input and prints incoming notifications in a dedicated receiver thread.
- `Protocol.java`: Base64-encoded, tab-separated line protocol to carry arbitrary UTF-8 message text safely.
- `HistoryStore.java`: synchronized append-only files for room and private conversation histories.
- `ChatIntegrationTest.java`: automated socket integration tests.

## Testing

```powershell
java -cp out ChatIntegrationTest
```

Tests start a temporary server and use temporary storage on a random localhost port.

## Known limitations
- Educational/local-network demonstration only: user names are not authenticated and traffic is not encrypted.
- Private message history is keyed by username pair; anyone impersonating a disconnected username could read its history. Do not store confidential information.
- Rooms are public (no invite permissions), and there is no GUI.
- Only the last 20 messages are displayed per request (all messages are retained in files).
- Existing room names reset on server restart, but joining the same room name restores its history.
- Persistence is file-based rather than SQLite to keep the application dependency-free.
