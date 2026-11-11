import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Swing frontend for the existing ChatServer wire protocol. JDK 17+. */
public final class ChatGUI extends JFrame {
    private final JTextField hostField = new JTextField("127.0.0.1", 11);
    private final JTextField portField = new JTextField("5555", 5);
    private final JTextField nameField = new JTextField("", 12);
    private final JButton connectButton = new JButton("Connect");
    private final DefaultListModel<String> roomModel = new DefaultListModel<>();
    private final JList<String> roomList = new JList<>(roomModel);
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JTextArea messages = new JTextArea();
    private final JTextField composeField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JLabel channelLabel = new JLabel("# lobby");
    private volatile PrintWriter writer;
    private volatile Socket socket;
    private volatile boolean connected;
    private volatile String username = "";
    private String room = "lobby";
    private String privateWith;
    private int sessionVersion = 0;

    public ChatGUI() {
        super("Enhanced Java Chat");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(970, 650);
        setMinimumSize(new Dimension(680, 440));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JPanel login = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        login.add(new JLabel("Host:")); login.add(hostField);
        login.add(new JLabel("Port:")); login.add(portField);
        login.add(new JLabel("Username:")); login.add(nameField);
        login.add(connectButton);
        add(login, BorderLayout.NORTH);

        JPanel sidebar = new JPanel(new BorderLayout(5, 10));
        sidebar.setPreferredSize(new Dimension(215, 0));
        JPanel roomsPanel = new JPanel(new BorderLayout(4, 4));
        JPanel roomHeader = new JPanel(new BorderLayout());
        roomHeader.add(new JLabel("Chat rooms"), BorderLayout.WEST);
        JButton addRoom = new JButton("+ Join"); roomHeader.add(addRoom, BorderLayout.EAST);
        roomsPanel.add(roomHeader, BorderLayout.NORTH);
        roomsPanel.add(new JScrollPane(roomList), BorderLayout.CENTER);
        JButton refreshRooms = new JButton("Refresh rooms");
        roomsPanel.add(refreshRooms, BorderLayout.SOUTH);
        JPanel usersPanel = new JPanel(new BorderLayout(4, 4));
        usersPanel.add(new JLabel("Online users (double-click to DM)"), BorderLayout.NORTH);
        usersPanel.add(new JScrollPane(userList), BorderLayout.CENTER);
        JButton refreshUsers = new JButton("Refresh users");
        usersPanel.add(refreshUsers, BorderLayout.SOUTH);
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, roomsPanel, usersPanel);
        leftSplit.setResizeWeight(.50);
        leftSplit.setBorder(null);
        sidebar.add(leftSplit, BorderLayout.CENTER);
        sidebar.setBorder(new EmptyBorder(0, 10, 10, 0));

        JPanel conversation = new JPanel(new BorderLayout(5, 8));
        JPanel header = new JPanel(new BorderLayout(8, 4));
        channelLabel.setFont(channelLabel.getFont().deriveFont(Font.BOLD, 19f));
        header.add(channelLabel, BorderLayout.WEST);
        JButton backToRoom = new JButton("Room chat");
        header.add(backToRoom, BorderLayout.EAST);
        conversation.add(header, BorderLayout.NORTH);
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        messages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        messages.setMargin(new Insets(12, 12, 12, 12));
        conversation.add(new JScrollPane(messages), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(8, 6));
        bottom.add(composeField, BorderLayout.CENTER);
        bottom.add(sendButton, BorderLayout.EAST);
        JPanel footer = new JPanel(new BorderLayout());
        JButton historyButton = new JButton("Load history");
        footer.add(historyButton, BorderLayout.EAST);
        footer.add(statusLabel, BorderLayout.CENTER);
        bottom.add(footer, BorderLayout.SOUTH);
        conversation.add(bottom, BorderLayout.SOUTH);
        conversation.setBorder(new EmptyBorder(0, 0, 10, 10));
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, conversation);
        mainSplit.setResizeWeight(0);
        mainSplit.setDividerLocation(240);
        mainSplit.setBorder(null);
        add(mainSplit, BorderLayout.CENTER);

        roomModel.addElement("lobby");
        setConnected(false);
        connectButton.addActionListener(e -> { if (connected) disconnect(); else connect(); });
        composeField.addActionListener(e -> sendMessage());
        sendButton.addActionListener(e -> sendMessage());
        addRoom.addActionListener(e -> {
            if (!connected) return;
            String target = JOptionPane.showInputDialog(this, "Enter room name (letters, numbers, _ or -):", "Join room", JOptionPane.QUESTION_MESSAGE);
            if (target != null && !target.isBlank()) send("JOIN", target.trim());
        });
        roomList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && connected && roomList.getSelectedValue() != null) {
                    privateWith = null;
                    send("JOIN", roomList.getSelectedValue());
                }
            }
        });
        userList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && connected && userList.getSelectedValue() != null) {
                    String selected = userList.getSelectedValue();
                    if (selected.equalsIgnoreCase(username)) return;
                    privateWith = selected;
                    channelLabel.setText("@ " + privateWith);
                    messages.setText("");
                    send("DMHISTORY", privateWith);
                }
            }
        });
        backToRoom.addActionListener(e -> {
            privateWith = null;
            channelLabel.setText("# " + room);
            messages.setText("");
            if (connected) send("HISTORY");
        });
        refreshRooms.addActionListener(e -> send("ROOMS"));
        refreshUsers.addActionListener(e -> send("USERS"));
        historyButton.addActionListener(e -> { if (privateWith != null) send("DMHISTORY", privateWith); else send("HISTORY"); });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { disconnect(); dispose(); }
        });
    }

    private void setConnected(boolean active) {
        connected = active;
        connectButton.setText(active ? "Disconnect" : "Connect");
        hostField.setEnabled(!active);
        portField.setEnabled(!active);
        nameField.setEnabled(!active);
        composeField.setEnabled(active);
        sendButton.setEnabled(active);
        if (active) composeField.requestFocusInWindow();
    }
    private void connect() {
        final String host = hostField.getText().trim();
        final String name = nameField.getText().trim();
        final int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { alert("Port must be a number."); return; }
        if (host.isEmpty() || name.isEmpty() || port < 1 || port > 65535) { alert("Enter a valid host, port and username."); return; }
        connectButton.setEnabled(false);
        statusLabel.setText("Connecting...");
        final int version = ++sessionVersion;
        new Thread(() -> {
            try {
                Socket next = new Socket(host, port);
                BufferedReader input = new BufferedReader(new InputStreamReader(next.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter output = new PrintWriter(new OutputStreamWriter(next.getOutputStream(), StandardCharsets.UTF_8), true);
                output.println(Protocol.encode("HELLO", name));
                String initial = input.readLine();
                if (initial == null) throw new IOException("Server closed the connection.");
                String[] reply = Protocol.decode(initial);
                if (!"WELCOME".equals(reply[0])) {
                    next.close();
                    throw new IOException(reply.length > 1 ? reply[1] : "Login rejected.");
                }
                SwingUtilities.invokeLater(() -> {
                    if (version != sessionVersion) { try { next.close(); } catch (IOException ignored) {} return; }
                    socket = next; writer = output; username = reply[1]; room = reply[2]; privateWith = null;
                    roomModel.clear(); roomModel.addElement(room);
                    messages.setText(""); channelLabel.setText("# " + room);
                    setConnected(true); connectButton.setEnabled(true);
                    statusLabel.setText("Connected as " + username);
                    send("ROOMS"); send("USERS"); send("HISTORY");
                });
                String line;
                while ((line = input.readLine()) != null) {
                    String[] event;
                    try { event = Protocol.decode(line); }
                    catch (IllegalArgumentException ex) { continue; }
                    SwingUtilities.invokeLater(() -> { if (version == sessionVersion) handle(event); });
                }
                next.close();
                SwingUtilities.invokeLater(() -> { if (version == sessionVersion) disconnected("Server disconnected."); });
            } catch (IOException | RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (version == sessionVersion) {
                        disconnected("Connection failed.");
                        alert(ex.getMessage());
                    }
                });
            }
        }, "gui-network-receiver").start();
    }
    private void disconnect() {
        ++sessionVersion;
        PrintWriter w = writer;
        if (w != null) w.println(Protocol.encode("QUIT"));
        Socket s = socket;
        if (s != null) { try { s.close(); } catch (IOException ignored) {} }
        disconnected("Disconnected.");
    }
    private void disconnected(String text) {
        socket = null; writer = null; username = "";
        setConnected(false); connectButton.setEnabled(true);
        statusLabel.setText(text);
        userModel.clear();
    }
    private void send(String... fields) {
        PrintWriter w = writer;
        if (connected && w != null) w.println(Protocol.encode(fields));
    }
    private void sendMessage() {
        String value = composeField.getText().trim();
        if (!connected || value.isEmpty()) return;
        if (privateWith != null) send("DM", privateWith, value);
        else send("SAY", value);
        composeField.setText("");
    }
    private void alert(String text) { JOptionPane.showMessageDialog(this, text, "Chat system", JOptionPane.WARNING_MESSAGE); }
    private void append(String text) {
        messages.append(text + "\n");
        messages.setCaretPosition(messages.getDocument().getLength());
    }
    private void fillModel(DefaultListModel<String> model, String data) {
        model.clear();
        if (!data.isBlank()) Arrays.stream(data.split(", ")).map(String::trim).filter(s -> !s.isEmpty()).forEach(model::addElement);
    }
    private void handle(String[] e) {
        if (e.length == 0) return;
        switch (e[0]) {
            case "ROOM_MSG" -> { if (e.length >= 4 && privateWith == null && e[1].equalsIgnoreCase(room)) append(e[2] + ": " + e[3]); }
            case "DM_MSG" -> {
                if (e.length < 4) break;
                String other = e[1].equalsIgnoreCase(username) ? e[2] : e[1];
                if (privateWith != null && other.equalsIgnoreCase(privateWith)) append(e[1] + ": " + e[3]);
                else { statusLabel.setText("New private message from " + other + " (double-click their name)"); send("USERS"); }
            }
            case "JOINED" -> { if (e.length >= 2) { room = e[1]; privateWith = null; channelLabel.setText("# " + room); messages.setText(""); send("ROOMS"); send("HISTORY"); } }
            case "USERS" -> { if (e.length >= 2) fillModel(userModel, e[1]); }
            case "ROOMS" -> { if (e.length >= 2) fillModel(roomModel, e[1]); }
            case "HISTORY_START" -> {
                if (e.length >= 3 && (("private".equals(e[1]) && privateWith != null && e[2].equalsIgnoreCase(privateWith)) || ("room".equals(e[1]) && privateWith == null && e[2].equalsIgnoreCase(room)))) {
                    messages.setText(""); append("--- Previous messages ---");
                }
            }
            case "HISTORY_MSG" -> { if (e.length >= 4) append(e[1] + " | " + e[2] + ": " + e[3]); }
            case "HISTORY_END" -> append("--- End of history ---");
            case "INFO" -> { if (e.length >= 2) { statusLabel.setText(e[1]); send("USERS"); } }
            case "ERROR" -> { if (e.length >= 2) { statusLabel.setText("Error: " + e[1]); append("[Error] " + e[1]); } }
            default -> { }
        }
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new ChatGUI().setVisible(true);
        });
    }
}
