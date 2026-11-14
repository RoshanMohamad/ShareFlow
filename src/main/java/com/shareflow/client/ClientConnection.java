package com.shareflow.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import com.shareflow.common.FileEntry;
import com.shareflow.common.Frame;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;
import com.shareflow.common.ProtocolConstants;
import com.shareflow.common.UserInfo;

/**
 * The long-lived control connection, built on a blocking {@link Socket}.
 * <p>
 * Lifecycle: connect (with timeout) -> open streams -> HELLO/WELCOME handshake -> a
 * control-reader thread receives server messages -> close. Listener callbacks run on
 * the reader thread, so UI code must hop to the JavaFX thread with
 * {@code Platform.runLater}.
 *
 * @author Member 1
 */
public final class ClientConnection implements AutoCloseable {

    /** Callbacks for server events; all default to no-ops. */
    public interface Listener {
        default void onUserList(List<UserInfo> users) {}
        default void onUserJoined(UserInfo user) {}
        default void onUserLeft(String username) {}
        default void onFileList(List<FileEntry> files) {}
        default void onFileAdded(FileEntry file) {}
        default void onError(String message) {}
        default void onDisconnected(String reason) {}
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String host;
    private final int port;
    private final String username;
    private final int sessionId;
    private final Listener listener;
    private volatile boolean closing;

    private ClientConnection(Socket socket, DataInputStream in, DataOutputStream out, String host, int port,
                             String username, int sessionId, Listener listener) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.host = host;
        this.port = port;
        this.username = username;
        this.sessionId = sessionId;
        this.listener = listener;
    }

    /**
     * Connects and logs in.
     *
     * @throws IOException with a readable message if the host is unknown, unreachable,
     *                     times out, or the server rejects the username
     */
    public static ClientConnection connect(String host, int port, String username, Listener listener)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), ProtocolConstants.CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(ProtocolConstants.CONNECT_TIMEOUT_MS); // handshake must not hang
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            FrameCodec.writeFrame(out, MessageType.HELLO, Payload.writer().writeString(username).toBytes());
            Frame reply = FrameCodec.readFrame(in);
            if (reply.type() == MessageType.ERROR) {
                throw new IOException(new Payload.Reader(reply.payload()).readString());
            }
            if (reply.type() != MessageType.WELCOME) {
                throw new IOException("Unexpected reply " + reply.type());
            }
            int sessionId = new Payload.Reader(reply.payload()).readInt();
            socket.setSoTimeout(0); // reader thread blocks until the server speaks

            ClientConnection c = new ClientConnection(socket, in, out, host, port, username, sessionId, listener);
            Thread t = new Thread(c::readLoop, "shareflow-control-reader");
            t.setDaemon(true);
            t.start();
            return c;
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw NetErrors.friendly(e, host, port);
        } catch (RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void readLoop() {
        String reason = "Connection lost";
        try {
            while (true) {
                dispatch(FrameCodec.readFrame(in));
            }
        } catch (IOException e) {
            if (closing) return;
            reason = e.getMessage() == null ? reason : e.getMessage();
        } finally {
            if (!closing) listener.onDisconnected(reason);
        }
    }

    private void dispatch(Frame f) throws IOException {
        Payload.Reader r = new Payload.Reader(f.payload());
        switch (f.type()) {
            case USER_LIST -> {
                int n = r.readInt();
                List<UserInfo> users = new ArrayList<>(n);
                for (int i = 0; i < n; i++) users.add(new UserInfo(r.readString(), r.readString()));
                listener.onUserList(users);
            }
            case USER_JOINED -> listener.onUserJoined(new UserInfo(r.readString(), r.readString()));
            case USER_LEFT -> listener.onUserLeft(r.readString());
            case FILE_LIST -> {
                int n = r.readInt();
                List<FileEntry> files = new ArrayList<>(n);
                for (int i = 0; i < n; i++) files.add(FileEntry.readFrom(r));
                listener.onFileList(files);
            }
            case FILE_ADDED -> listener.onFileAdded(FileEntry.readFrom(r));
            case ERROR -> listener.onError(r.readString());
            default -> { /* ignore */ }
        }
    }

    public synchronized void requestFileList() throws IOException {
        FrameCodec.writeFrame(out, MessageType.LIST_FILES, new byte[0]);
    }

    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public int sessionId() { return sessionId; }

    @Override
    public synchronized void close() {
        closing = true;
        try { FrameCodec.writeFrame(out, MessageType.BYE, new byte[0]); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
