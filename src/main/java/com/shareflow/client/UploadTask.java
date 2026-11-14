package com.shareflow.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;

import javafx.concurrent.Task;

import com.shareflow.common.Formats;
import com.shareflow.common.Frame;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;
import com.shareflow.common.ProtocolConstants;

/**
 * Uploads one file over its own data connection: {@code FileChannel -> ByteBuffer ->
 * SocketChannel} in 64 KB chunks. The transfer logic is in {@link #upload} so it can
 * also run without JavaFX (tests, console use).
 *
 * @author Member 3
 */
public final class UploadTask extends Task<Integer> {
    private final String host;
    private final int port;
    private final int sessionId;
    private final Path file;

    public UploadTask(String host, int port, int sessionId, Path file) {
        this.host = host;
        this.port = port;
        this.sessionId = sessionId;
        this.file = file;
    }

    @Override
    protected Integer call() throws Exception {
        updateMessage("Starting...");
        return upload(host, port, sessionId, file, (done, total) -> {
            updateProgress(done, total);
            updateMessage(Formats.size(done) + " / " + Formats.size(total));
        }, this::isCancelled);
    }

    /** @return the server-assigned file id */
    public static int upload(String host, int port, int sessionId, Path file,
                             TransferProgress progress, BooleanSupplier cancelled) throws IOException {
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ);
             SocketChannel ch = SocketChannel.open()) {
            long size = in.size();
            ch.socket().connect(new InetSocketAddress(host, port), 5000);

            String name = file.getFileName().toString();
            FrameCodec.writeFrame(ch, MessageType.UPLOAD_START, Payload.writer()
                    .writeInt(sessionId).writeString(name).writeLong(size).toBytes());

            ByteBuffer buf = ByteBuffer.allocate(ProtocolConstants.CLIENT_CHUNK);
            long sent = 0;
            progress.update(0, size);
            while (sent < size) {
                if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                buf.clear();
                if (in.read(buf) < 0) throw new IOException("File changed during upload");
                buf.flip();
                while (buf.hasRemaining()) sent += ch.write(buf);
                progress.update(sent, size);
            }

            Frame reply = FrameCodec.readFrame(ch);
            if (reply.type() == MessageType.ERROR) {
                throw new IOException(new Payload.Reader(reply.payload()).readString());
            }
            if (reply.type() != MessageType.UPLOAD_DONE) throw new IOException("Unexpected reply " + reply.type());
            return new Payload.Reader(reply.payload()).readInt();
        }
    }
}
