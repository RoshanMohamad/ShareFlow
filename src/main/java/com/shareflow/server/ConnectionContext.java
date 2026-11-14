package com.shareflow.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;

import com.shareflow.common.ProtocolConstants;

/**
 * Per-connection state, attached to each {@link SelectionKey}. Only touched by the
 * event-loop thread, so no locking is needed.
 *
 * @author Member 2
 */
public final class ConnectionContext {

    public enum State {
        /** Accepted; first frame decides whether this is a control or data connection. */
        NEW,
        /** Logged-in control connection. */
        CONTROL,
        UPLOADING,
        DOWNLOADING,
        /** Flushing the last reply, then closing. */
        CLOSING
    }

    final SocketChannel channel;
    final SelectionKey key;
    final String ip;
    final ByteBuffer readBuf = ByteBuffer.allocate(ProtocolConstants.BUFFER_SIZE);
    final Deque<ByteBuffer> writeQueue = new ArrayDeque<>();

    State state = State.NEW;
    boolean closeAfterWrite;
    boolean closed;

    // control connection
    String username;
    int sessionId;

    // upload state
    FileChannel uploadChannel;
    FileRepository.Reservation reservation;
    String uploader;
    long uploadRemaining;
    long uploadTotal;

    // download state
    FileChannel downloadChannel;
    long downloadPos;
    long downloadSize;
    String downloadName;

    ConnectionContext(SocketChannel channel, SelectionKey key, String ip) {
        this.channel = channel;
        this.key = key;
        this.ip = ip;
    }

    /** Queues a buffer for sending and asks the selector for OP_WRITE. */
    void enqueue(ByteBuffer data) {
        if (closed) return;
        writeQueue.add(data);
        if (key.isValid()) key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    /** After the queued data is flushed the connection is closed; reading stops now. */
    void closeAfterFlush() {
        state = State.CLOSING;
        closeAfterWrite = true;
        if (key.isValid()) key.interestOps(SelectionKey.OP_WRITE);
    }

    String describe() {
        return username != null ? username + "@" + ip : ip;
    }

    /** Releases files. Deletes an unfinished .part file. */
    void releaseResources() {
        closeQuietly(uploadChannel);
        uploadChannel = null;
        closeQuietly(downloadChannel);
        downloadChannel = null;
    }

    private static void closeQuietly(FileChannel fc) {
        if (fc == null) return;
        try { fc.close(); } catch (IOException ignored) { }
    }
}
