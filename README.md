# Enhanced Network-Based Chat System — Java

A Java TCP networking project with a multithreaded chat server, command-line client, Java Swing GUI, chat rooms, private messages, message history, and integration tests.

**For contributors:** You can **download and test the entire system independently on your own computer** (run both server and clients), or **connect several computers to one shared server** for a group demonstration.

## 1. What to install

- Java Development Kit **JDK 17 or newer**. Verify in PowerShell:
  ```powershell
  java -version
  javac -version
  ```
- Windows PowerShell (steps below), or a terminal on macOS/Linux.
- No database installation or third-party Java libraries are required.

## 2. Download the source on your own computer

**No Git required:** Open [the GitHub repository], choose **Code → Download ZIP**, extract it, and open PowerShell inside the extracted folder containing `src`.

**Using Git:**

```powershell
git clone 
cd java-chatSystem
```

Verify your source files are present:

```powershell
Get-ChildItem .\src\*.java
```

Expected files include `ChatServer.java`, `ChatClient.java`, `Protocol.java`, `HistoryStore.java`, `ChatIntegrationTest.java`, and `ChatGUI.java`. **If the GUI or latest tests are missing, the project maintainer must push the updated files to GitHub.**

## 3. Compile the project (every computer)

In PowerShell, **from the project folder**:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out (Get-ChildItem .\src\*.java).FullName
```

A successful compile returns without errors. It creates `.class` files in `out`.

**macOS/Linux:**

```bash
mkdir -p out
javac -encoding UTF-8 -d out src/*.java
```

> **Windows PowerShell:** Do not use `javac ... src/*.java` directly; PowerShell may not expand the wildcard as expected. Use `Get-ChildItem` above.

## 4. Option A — Test everything on your own computer (recommended first)

Every classmate can do this separately, even at home and without coordinating with others.

### Terminal 1 — Run your own server

```powershell
java -cp out ChatServer
```

Leave this terminal open. The server uses TCP port **5555** by default and saves conversation history in a local `data` folder.

### Terminal 2 — Run your first GUI client

```powershell
java -cp out ChatGUI
```

Enter **Host:** `127.0.0.1`, **Port:** `5555`, **Username:** `Alice`, and click **Connect**.

### Terminal 3 — Run your second GUI client

```powershell
java -cp out ChatGUI
```

Enter **Host:** `127.0.0.1`, **Port:** `5555`, **Username:** `Bob`.

You now have **one server and two independent clients running on your own computer**. Open a third GUI with username `Charlie` if you want to test three clients. Keep the server terminal visible to observe connection and message logs.

**Alternative terminal clients:**

```powershell
java -cp out ChatClient --username Alice
java -cp out ChatClient --username Bob
```

Run each command in a separate terminal. Do not connect two clients with the same username at the same time.

### Independent testing checklist

- [ ] Both Alice and Bob connect successfully.
- [ ] Alice sends a message in `lobby`; Bob sees it.
- [ ] Alice and Bob join `project`; Charlie remains in `lobby` and does not see messages to `project`.
- [ ] Alice sends Bob a private message; only the intended conversation participants see it.
- [ ] Disconnect Bob and reconnect; retrieve room history.
- [ ] Try logging in with `alice` while `Alice` is connected; duplicate username is rejected.
- [ ] Close one client unexpectedly; the server stays running.
- [ ] Observe acknowledgment or response-time information if your copy includes the updated GUI/client.

## 5. Option B — Test together from different computers on the same Wi-Fi/LAN

**Only one student hosts the server** for this mode. Everyone else runs a client; you can still run a GUI on the host computer.

**On the host's computer:**

1. Complete steps 1–3, then run `java -cp out ChatServer`.
2. Run `ipconfig` and find the active network's IPv4 address (example: `192.168.1.50`).
3. Allow inbound **TCP 5555** in Windows Firewall for the **trusted Private network only**, if required. Prefer limiting access to classmates' devices or the local subnet.
4. Share the **actual host LAN IP** and port `5555` with classmates.

**On each classmate's computer:**

1. Complete steps 1–3 and join the same trusted network.
2. Run `java -cp out ChatGUI`.
3. Enter the **host computer's LAN IP**, **port `5555`**, and a **unique username**.
4. Click **Connect**.

**Example only:** If the host IP is `192.168.1.50`, Bob enters `192.168.1.50` — **not** `127.0.0.1`. `127.0.0.1` always means *this computer*.

**Different locations:** A restricted private VPN (such as Tailscale) can connect approved devices without exposing port 5555 publicly. The host runs the server; clients enter the host's VPN IP. A public internet deployment needs encryption and proper authentication/authorization beyond this classroom build.

## 6. How to use the chat

**Java Swing GUI:**

- Type into the message field and press **Enter** or **Send**.
- Select **+ Join**, or double-click a room, to enter another room.
- Double-click an online username to start a private conversation.
- Select **Room chat** to return to room messages.
- Use **Load history**, **Refresh rooms**, and **Refresh users** as needed.
- Select **Disconnect** when finished.

**Terminal client commands:**

| Command | Description |
| --- | --- |
| `Hello everyone` | Send to the current room |
| `/join project` | Create/join the `project` room |
| `/rooms` | List rooms |
| `/users` | List online users |
| `/dm Bob Hello!` | Send a private message |
| `/history` | Show recent room messages |
| `/dmhistory Bob` | Show recent private messages |
| `/help` | Show commands |
| `/quit` | Disconnect |

## 7. Run automated networking tests on your own computer

Each classmate can run the integration suite **without anyone else's server**. Open a separate terminal in the project folder and run:

```powershell
java -cp out ChatIntegrationTest
```

The suite starts a temporary server for its tests. In the updated test version, the expected result is:

```text
PASS: 36 integration assertions.
```

Your actual result depends on the source version you downloaded. The tests cover connections, username validation, room routing, private messages, saved history, malformed/oversized messages, TCP framing, concurrency, acknowledgments, and disconnection handling. For independent testing, you can stop the manually launched server first to avoid confusion about which server you are connecting to.

## 8. How the networking works

```text
   ChatGUI / ChatClient (Alice) ----\
   ChatGUI / ChatClient (Bob) -------+---- TCP :5555 ----> ChatServer
   ChatGUI / ChatClient (Charlie) --/                       |
                                             sessions, rooms, routing
                                                            |
                                                       HistoryStore
                                                            |
                                                        local data/
```

- **TCP sockets:** Provide reliable, ordered byte streams between the server and each client.
- **Framing:** Each application message is newline-terminated; fields are Base64-encoded UTF-8 and separated by tabs. TCP itself does not provide message boundaries.
- **Concurrency:** The server handles multiple client connections using threads and coordinates shared user and room state.
- **Validation and error handling:** The improved server handles invalid requests, oversized frames, duplicate usernames, and unexpected disconnections.
- **Acknowledgments:** Updated clients can attach request IDs and measure how long a server acknowledgment takes. An ACK is **not** proof that every recipient displayed a message.
- **History:** Conversation history is stored **on the machine running that particular server**. Independent servers have separate histories.

## 9. Troubleshooting

| Problem | What to do |
| --- | --- |
| `javac` not recognized | Install JDK 17+, reopen PowerShell, run `javac -version` |
| `Invalid filename: src/*.java` | Use the `Get-ChildItem` command in step 3 |
| `Could not find or load main class` | Compile from the project folder; use `java -cp out ChatServer` or `ChatGUI` |
| `Address already in use` | Another server is using port 5555; stop it or choose another port with `java -cp out ChatServer --port 5556` |
| `Connection refused` | Start the server; confirm host/port and firewall |
| Connect fails between computers | Ensure the same trusted LAN/VPN, correct *host* IP, and firewall access |
| Duplicate username | Use a different name or disconnect the earlier session |
| Missing message history | Verify you connected to the same server and its `data` folder |
| `ChatGUI` missing | Ask the project maintainer to push the latest `src/ChatGUI.java` |

To check reachability from another Windows computer:

```powershell
Test-NetConnection SERVER_IP -Port 5555
```

Replace `SERVER_IP` with the real host IP. `TcpTestSucceeded : True` proves only that the TCP port is reachable, not that chat login succeeded.

---
