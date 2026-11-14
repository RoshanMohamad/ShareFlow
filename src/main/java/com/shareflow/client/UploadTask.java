package com.shareflow.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.shareflow.common.Frame;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.Hashing;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;
import com.shareflow.common.ProtocolConstants;

/**
 * Uploads one file over its own data connection (a blocking {@link Socket}). The client
 * first computes the file's SHA-256 and sends it with UPLOAD_START; the server verifies
 * it after the last byte. Runs as a {@link Callable} on the transfer thread pool.
 *
 * @author Member 3
 */
public final class UploadTask implements Callable<Integer> {
    private final String host;
    private final int port;
    private final int sessionId;
    private final Path file;
    private final TransferProgress progress;
    private final Consumer<String> status;
    private final BooleanSupplier cancelled;

    public UploadTask(String host, int port, int sessionId, Path file,
                      TransferProgress progress, Consumer<String> status, BooleanSupplier cancelled) {
        this.host = host;
        this.port = port;
        this.sessionId = sessionId;
        this.file = file;
        this.progress = progress;
        this.status = status;
        this.cancelled = cancelled;
    }

    @Override
    public Integer call() throws IOException {
        return upload(host, port, sessionId, file, progress, status, cancelled);
    }

    public static int upload(String host, int port, int sessionId, Path file,
                             TransferProgress progress, BooleanSupplier cancelled) throws IOException {
        return upload(host, port, sessionId, file, progress, s -> { }, cancelled);
    }

    /** @return the server-assigned file id */
    public static int upload(String host, int port, int sessionId, Path file, TransferProgress progress,
                             Consumer<String> status, BooleanSupplier cancelled) throws IOException {
        status.accept("Calculating SHA-256...");
        byte[] hash = Hashing.sha256(file);
        long size = Files.size(file);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), ProtocolConstants.CONNECT_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), ProtocolConstants.CLIENT_CHUNK));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            FrameCodec.writeFrame(out, MessageType.UPLOAD_START, Payload.writer()
                    .writeInt(sessionId).writeString(file.getFileName().toString())
                    .writeLong(size).writeBytes(hash).toBytes());

            status.accept("Uploading...");
            byte[] buf = new byte[ProtocolConstants.CLIENT_CHUNK];
            long sent = 0;
            progress.update(0, size);
            try (InputStream src = Files.newInputStream(file)) {
                while (sent < size) {
                    if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                    int n = src.read(buf, 0, (int) Math.min(buf.length, size - sent));
                    if (n < 0) throw new IOException("File changed during upload");
                    out.write(buf, 0, n);
                    sent += n;
                    progress.update(sent, size);
                }
            }
            out.flush();

            status.accept("Verifying on server...");
            socket.setSoTimeout(60_000);
            Frame reply = FrameCodec.readFrame(in);
            if (reply.type() == MessageType.ERROR) {
                throw new IOException(new Payload.Reader(reply.payload()).readString());
            }
            if (reply.type() != MessageType.UPLOAD_DONE) throw new IOException("Unexpected reply " + reply.type());
            return new Payload.Reader(reply.payload()).readInt();
        } catch (IOException e) {
            throw NetErrors.friendly(e, host, port);
        }
    }
}
