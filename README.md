# Enhanced Network-Based Chat System — Java

A Java TCP chat application built for a networking class. Multiple people can connect to **one running server** from separate computers, chat in rooms, send direct messages, and retrieve saved history. You can use either the **Java Swing GUI** or a terminal client.

> **For classmates:** You do **not** each start a server. One person hosts `ChatServer`; everybody else runs `ChatGUI` (or `ChatClient`) and enters the host's IP address. Start with a trusted local network or an approved private VPN. This classroom build uses plain TCP and username-only access; **do not forward port 5555 to the open internet** or share sensitive messages.

## What you need

- **JDK 17 or newer** installed on each participating computer. Check in a terminal:
  ```powershell
  java -version
  javac -version
  ```
- Windows PowerShell (instructions below), or a macOS/Linux terminal.
- The project source code from **[GitHub: officallyariana/java-chatSystem](https://github.com/officallyariana/java-chatSystem)**.
- The **server host's IP address** and **port** (default `5555`) if connecting from another computer.

No third-party Java libraries or database installation are required. The desktop GUI uses Java Swing.

## 1. Download the project (every classmate)

**Option A — No Git needed:** Open the [repository](https://github.com/officallyariana/java-chatSystem), select **Code → Download ZIP**, extract it, and open PowerShell in the extracted `java-chatSystem` folder.

**Option B — Using Git:**

```powershell
git clone https://github.com/officallyariana/java-chatSystem.git
cd java-chatSystem
```

Make sure `src` contains `ChatServer.java`, `ChatClient.java`, `ChatGUI.java`, `Protocol.java`, and `HistoryStore.java`. If `ChatGUI.java` is missing, ask the project maintainer to push the latest files to GitHub before continuing.

## 2. Compile the project (every classmate)

Run these commands **inside the project folder**:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out (Get-ChildItem .\src\*.java).FullName
```

**macOS/Linux** equivalent:

```bash
mkdir -p out
javac -encoding UTF-8 -d out src/*.java
```

If compilation succeeds, compiled `.class` files appear under `out/`.

> PowerShell tip: Use `(Get-ChildItem .\src\*.java).FullName`, not just `src/*.java`, to avoid `Invalid filename` errors.

## 3. Pick a connection setup

### A. One computer, multiple windows (quick practice)

The person testing starts the server in one terminal:

```powershell
java -cp out ChatServer
```

Keep that terminal open. Open two **new** terminals in the same folder and launch:

```powershell
java -cp out ChatGUI
```

Run the GUI command a second time to open another client window. Connect as `Alice` and `Bob`, both using **Host `127.0.0.1` / Port `5555`**.

### B. Different computers on the same trusted Wi-Fi/LAN

**Server host only:**

1. Compile the project and run `java -cp out ChatServer`.
2. Find the host's IPv4 address in PowerShell:
   ```powershell
   ipconfig
   ```
   Look for the **IPv4 Address** on the active network adapter, for example `192.168.1.50` (**example only**).
3. If Windows Firewall blocks connections, permit **inbound TCP 5555 only on the trusted Private network** (Windows Defender Firewall → Advanced settings → Inbound Rules). Do not create an unrestricted public-facing rule.
4. Tell classmates the host's **actual private IP** and port `5555`.

**Classmates:**

1. Join the same trusted network as the host.
2. Launch the GUI:
   ```powershell
   java -cp out ChatGUI
   ```
3. Enter the **host's private IP** (not `127.0.0.1`), port `5555`, and a **unique username**.
4. Click **Connect**.

**Remember:** `127.0.0.1` always refers to *your own* computer. A classmate entering it will not reach somebody else's server.

### C. Different locations / different networks (private demonstration)

Use a **private VPN such as Tailscale** if the host and classmates are in different locations. Each participating device must be authorized to reach the host through Tailscale. The host starts `ChatServer`, and classmates enter the **host's Tailscale IP** in ChatGUI. Follow the VPN's device-sharing and access instructions, and limit firewall access to the VPN interface where practical.

This does **not** turn the app into a secure, public messaging service: the classroom server still lacks individual account authentication and application-level TLS. **Do not use router port forwarding or publish the plain-TCP service on a cloud public IP.** A public deployment requires TLS, proper account authentication, and authorization for private-message history.

## 4. Connect in ChatGUI

After starting `ChatGUI`, use these settings:

| Field | On same computer | Classmate on another computer |
|---|---|---|
| Host | `127.0.0.1` | Server host's LAN/VPN IP |
| Port | `5555` | `5555` (unless changed by host) |
| Username | `Alice` | Choose a unique name, e.g. `Bob` |

Click **Connect**. You should see your connection status change to connected. A duplicate username (including different capitalization) will be rejected.

**GUI tips:**
- Type a message and press **Enter** or **Send** to chat in your current room.
- Use **+ Join** or double-click a room in the room list to join/switch rooms.
- Double-click a user to open private messaging; use **Room chat** to return to the room.
- Use **Refresh users**, **Refresh rooms**, and **Load history** as needed.
- The newer GUI may display a server acknowledgment and round-trip time after sending a message. An `ACK` indicates server processing, **not** proof that every recipient saw the message.
- Click **Disconnect** before closing the window.

## 5. Optional: terminal client

From the project folder, connect to a server on your own machine:

```powershell
java -cp out ChatClient --username Alice
```

Connect to a server on another trusted machine:

```powershell
java -cp out ChatClient --host 192.168.1.50 --port 5555 --username Bob
```

Replace the example IP address with the **actual host's IP**. Useful terminal commands:

| Command | Purpose |
|---|---|
| Just type a message | Send to your current room |
| `/join project` | Create/join the `project` room |
| `/rooms` | Show rooms |
| `/users` | Show online users |
| `/dm Alice Hello!` | Send Alice a private message |
| `/history` | Show recent room history |
| `/dmhistory Alice` | Show recent private-message history |
| `/help` | Show commands |
| `/quit` | Disconnect |

## 6. A 5-minute test with three classmates

1. **Host:** Start `ChatServer` and keep the server terminal visible.
2. **Alice, Bob, Charlie:** Launch separate GUIs and connect with unique usernames.
3. **Alice + Bob:** Join the `project` room. **Charlie:** Remain in `lobby`.
4. **Alice:** Send a room message; **Bob** should see it, **Charlie** should not.
5. **Alice:** Double-click Charlie and send a private message. Only Alice and Charlie should see the DM.
6. **Bob:** Click **Load history** or reconnect and retrieve previous room messages.
7. **Charlie:** Disconnect; the host should observe the disconnection without the server stopping.
8. **Host:** Show the server logs, then run integration tests **in a separate terminal after stopping the manually running server if desired**:
   ```powershell
   java -cp out ChatIntegrationTest
   ```
   In the updated test build, the expected final line is `PASS: 36 integration assertions.` Actual results depend on the version downloaded and must be verified on your machine.

## 7. Troubleshooting

| Problem | What to check |
|---|---|
| `javac` not recognized | Install JDK 17+; reopen terminal; check `javac -version` |
| `Invalid filename: src/*.java` | Use the PowerShell `Get-ChildItem` compile command above |
| `Could not find or load main class` | Be in the project folder; compile first; run `java -cp out ChatServer` or `ChatGUI` |
| `Connection refused` | Host server running? Correct IP and port? Firewall allowing trusted network traffic? |
| Connection times out | Check both devices' network/VPN access and server firewall |
| Username already online | Choose a different username; usernames are case-insensitive |
| Chat window opens but won't connect | Confirm `ChatServer` is running and you entered the **server host's**, not your own, IP |
| History missing | The server stores history on the **host's computer**; check which server instance you connected to |
| GUI isn't in cloned repo | Ask maintainer to commit/push `src/ChatGUI.java` and other updated files |

**Windows network diagnostic (on a classmate's PC):**

```powershell
Test-NetConnection SERVER_IP -Port 5555
```

Replace `SERVER_IP`. A `TcpTestSucceeded : True` result confirms the TCP port is reachable, but does not guarantee the chat login succeeds.

## 8. Technical overview (for the networking assignment)

```text
  Alice: ChatGUI/ChatClient ----\
  Bob:   ChatGUI/ChatClient -----+--- TCP port 5555 ---> ChatServer
  Charlie: ChatGUI/ChatClient --/                         |
                                     sessions / rooms / routing
                                                        |
                                                    HistoryStore
                                                        |
                                                    local files
```

- **TCP:** Provides an ordered, reliable stream of bytes. The application separates messages with **newlines**; each message contains **tab-separated Base64 fields** carrying UTF-8 strings.
- **Concurrency:** The server handles multiple client sockets using a thread pool and coordinates shared session/room state.
- **Validation:** The updated build checks usernames, rooms, messages, and malformed/oversized frames.
- **Acknowledgments:** Optional request IDs let the updated terminal/GUI client measure the time until the server processes a request.
- **Testing:** `ChatIntegrationTest` exercises client connections, room routing, private messaging, history, duplicate names, malformed input, TCP framing, concurrency, and disconnects.

## 9. Security and privacy limitations

This is a **course demonstration project**, not production chat software. The classroom version accepts usernames without passwords and uses plain TCP. File-based history can be retrieved by someone reconnecting under a previously used username. Do not transmit passwords, private documents, or sensitive information. Only run it within a trusted environment or a carefully restricted private VPN.

## For project maintainers: publish updates

If the README or source files were updated locally, commit and push them so classmates downloading from GitHub receive the newest version:

```powershell
git add README.md src/
git commit -m "Document classmate setup and remote LAN access"
git push origin main
```