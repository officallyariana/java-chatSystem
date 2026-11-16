# Network-Based Chat System

> **Deployment status:** The instructions below are for localhost or a **trusted local network**. The classroom version uses plain TCP and username-only identification. **Do not expose port 5555 directly to the public internet** or use the application for sensitive communications. Public deployment requires integrating TLS, authentication, and access controls in both server and clients.

## Features

- Multiple simultaneous client connections using TCP sockets and a server-side thread pool
- Unique usernames (case-insensitive duplicate rejection)
- Public chat rooms with room-specific messages
- Direct/private messages between online users
- Online-user and room lists
- Persistent room and direct-message history (file-based `HistoryStore`)
- Command-line client (`ChatClient`) and desktop GUI (`ChatGUI`, Java Swing)
- Protocol validation, bounded newline-delimited messages, and handling of unexpected disconnections
- Optional application-level message acknowledgments (`ACK`) with client-side round-trip timing
- Integration tests covering normal and failure scenarios

## Requirements

- **JDK 17 or newer** (`java -version` and `javac -version`)
- Windows PowerShell, macOS Terminal, or Linux shell
- No external libraries or database server required

## Project structure

```text
java-chatSystem/
├── src/
│   ├── ChatServer.java           # TCP server, sessions, rooms, routing, logs
│   ├── ChatClient.java           # Terminal client and ACK timing
│   ├── ChatGUI.java              # Java Swing desktop client
│   ├── Protocol.java             # UTF-8/Base64 line-based protocol
│   ├── HistoryStore.java         # Persistent room/private histories
│   └── ChatIntegrationTest.java  # Automated networking tests
├── README.md
├── .gitignore
├── out/                         # Generated compiled classes (ignored)
└── data/                        # Generated chat history (ignored)
```

## Getting started (Windows PowerShell)

Open PowerShell in the cloned project directory:

```powershell
cd C:\Users\\java-chatSystem
```

Or clone the repository first:

```powershell
git clone https://github.com/officallyariana/java-chatSystem.git
cd java-chatSystem
```

### 1. Compile the source files

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out (Get-ChildItem .\src\*.java).FullName
```

The `Get-ChildItem` expression explicitly expands `.java` files for Windows PowerShell. A literal `src/*.java` argument may produce `Invalid filename` in some PowerShell setups.

**macOS/Linux:**

```bash
mkdir -p out
javac -encoding UTF-8 -d out src/*.java
```

### 2. Start the server

In terminal 1:

```powershell
java -cp out ChatServer
```

Default TCP port: **5555**. The server stores history in `data/` by default.

To change those settings:

```powershell
java -cp out ChatServer --port 5555 --data data
```

Keep the server terminal running.

### 3. Start the GUI

In a **new** PowerShell terminal in the same directory:

```powershell
java -cp out ChatGUI
```

Enter:

- **Host:** `127.0.0.1`
- **Port:** `5555`
- **Username:** `Alice`

Click **Connect**. Launch a second GUI and connect as `Bob`, and optionally a third as `Charlie`.

In the GUI, use the room panel to join a room; double-click an online user to open a direct conversation. The GUI also provides history retrieval and connection/acknowledgment status.

### 4. Or use terminal clients

Open separate terminals:

```powershell
java -cp out ChatClient --username Alice
java -cp out ChatClient --username Bob
java -cp out ChatClient --username Charlie
```

To connect to a server on another computer on a **trusted LAN**, supply its LAN IP:

```powershell
java -cp out ChatClient --host 192.168.1.50 --port 5555 --username Alice
```

Replace the example address with the server's actual LAN address. The server host must permit inbound TCP connections on the configured port. Do not forward this port to the public internet.

## Terminal client commands

| Command | Purpose |
|---|---|
| Any regular text | Send a message to the current room |
| `/join project` | Create or join the `project` room |
| `/use project` | Alias for `/join` |
| `/rooms` | List available rooms |
| `/users` | List connected users |
| `/dm Bob Hello!` | Send a private message to Bob |
| `/history` | Retrieve the last 20 messages from the current room |
| `/dmhistory Bob` | Retrieve the last 20 private messages with Bob |
| `/help` | Display commands |
| `/quit` | Disconnect |

## Architecture

```text
       ChatGUI / ChatClient       ChatGUI / ChatClient       ChatGUI / ChatClient
              (Alice)                  (Bob)                    (Charlie)
                  \                      |                          /
                   \               TCP sockets                       /
                    \                    |                          /
                       +------------------------------+
                       |         ChatServer           |
                       |  session + room management   |
                       |  command validation/routing  |
                       |  concurrent client handlers  |
                       +------------------------------+
                                      |
                               HistoryStore
                                      |
                              local history files
```

**Server:** Listens for TCP connections, accepts clients, and processes each session concurrently. Shared online-user/room collections are coordinated to avoid concurrent modifications. Room messages go only to members of that room; direct messages go to the sender and the chosen recipient.

**Clients:** The terminal client reads user input and listens for server events in a separate receiver thread. The Swing GUI performs network reads off the Event Dispatch Thread and updates components on the Event Dispatch Thread.

**History:** Room and private messages are stored in files. Users can request the most recent 20 entries and retrieve them after reconnecting. Usernames are not authenticated, so histories are not suitable for sensitive data.

## TCP protocol and message framing

The wire format is a **newline-delimited** series of messages. Each message contains one or more fields encoded with **Base64**, separated by tab characters (`\t`). Each field represents UTF-8 text.

For example, a room message:

```text
Decoded fields:  SAY | Hello Bob!
Wire frame:      U0FZ<TAB>SGVsbG8gQm9iIQ==<NEWLINE>
```

The `<TAB>` and `<NEWLINE>` labels above are explanatory placeholders, not literal protocol characters.

TCP is a **byte stream**; an individual socket read is not guaranteed to correspond to exactly one application message. Newline framing allows the receiver to reconstruct complete frames even when network data arrives in pieces or several frames arrive together. Base64 ensures that tabs/newlines in a user's message do not interfere with framing.

### Client requests

| Request | Fields | Meaning |
|---|---|---|
| `HELLO` | username | Register a username on connection |
| `JOIN` | room | Join or create a room |
| `SAY` | message, optional request ID | Send a room message |
| `DM` | username, message, optional request ID | Send a private message |
| `USERS` | — | Request online users |
| `ROOMS` | — | Request rooms |
| `HISTORY` | — | Request current-room history |
| `DMHISTORY` | username | Request private-message history |
| `QUIT` | — | Disconnect |

### Server events

| Event | Meaning |
|---|---|
| `WELCOME` | Username accepted; initial room provided |
| `ROOM_MSG` | New room message |
| `DM_MSG` | New private message |
| `JOINED` | Room switch confirmed |
| `USERS` / `ROOMS` | User and room lists |
| `HISTORY_START` / `HISTORY_MSG` / `HISTORY_END` | History response |
| `INFO` | Server notification |
| `ERROR` | Invalid command or failed operation |
| `ACK` | Optional request-ID-based application-level acknowledgment |

An `ACK` means the server has processed the identified request. It **does not establish that every recipient rendered the message**. A client can measure *round-trip time (RTT)* from sending the request until its matching `ACK` arrives.

The revised server uses a bounded frame reader (16,384 bytes per encoded line); `Protocol.java` also validates the decoded structure. Adjusting these limits requires consistent changes across server, protocol, and clients.

## Concurrency and reliability

- The server handles multiple socket sessions using an executor service.
- Username uniqueness is enforced case-insensitively.
- Room membership and online-user state are synchronized.
- Invalid requests receive error responses where possible.
- Oversized frames and broken connections are handled without crashing the whole server.
- Server logs show events useful for monitoring a live demonstration.
- `PrintWriter` write success is not proof of end-to-end recipient delivery; the application distinguishes server processing acknowledgments from remote client receipt.

## Testing

Compile first, then run:

```powershell
java -cp out ChatIntegrationTest
```

The expanded integration test is designed to check:

1. Client connections and duplicate-username rejection
2. Room isolation, private messages, and persistence
3. Application acknowledgments
4. Invalid and oversized messages
5. TCP fragmentation and multiple frames received together
6. Concurrent message transmission
7. Unexpected disconnection handling

**Expected output for the updated test suite:**

```text
PASS: 36 integration assertions.
```

This is an expected local test result, **not** a claim that every machine or network environment will pass automatically. Run the suite on your computer, capture the terminal output, and include measured results in your course report.

## Suggested live classroom demonstration

1. Start `ChatServer` and explain its listening port and connection-handling threads.
2. Connect Alice, Bob, and Charlie in separate clients.
3. Show that duplicate usernames are rejected (`Alice` vs. `alice`).
4. Have Alice and Bob join `project` while Charlie stays in `lobby`.
5. Send a room message and show that Charlie does not receive it.
6. Send a private message from Alice to Charlie.
7. Show room history and private-message history after a reconnect.
8. Show an `ACK` and its measured round-trip time.
9. Run `ChatIntegrationTest` to demonstrate fragmented frames, concurrent sends, invalid data, and disconnect resilience.
10. Explain the server logs, observed delivery counts, and limitations.

## Known limitations and next steps

- The current classroom build uses **plain TCP**, with no secure individual account authentication. Do not deploy it publicly as-is.
- Usernames are not trusted identities. Someone reusing a disconnected username could access that username's private history.
- Public rooms have no permission or invitation system.
- History is file-based; this is intentionally simple for the course but not designed for large-scale use.
- A server `ACK` confirms server processing, not confirmed delivery/display by another client.
- Secure public deployment would require **TLS on both server and GUI/terminal clients**, user authentication, authorization for histories, and abuse/rate controls. A separate cloud variant should not be assumed compatible without merging those features and testing them.
