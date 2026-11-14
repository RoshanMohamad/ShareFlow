package com.shareflow.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

import com.shareflow.common.Frame;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.Hashing;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;
import com.shareflow.common.ProtocolConstants;

/**
 * Downloads one file over its own data connection (a blocking {@link Socket}) and checks
 * its SHA-256 against the hash announced by the server. A corrupted or failed download
 * is deleted. Runs as a {@link Callable} on the transfer thread pool.
 *
 * @author Member 4
 */
public final class DownloadTask implements Callable<Boolean> {
    private final String host;
    private final int port;
    private final int sessionId;
    private final int fileId;
    private final Path dest;
    private final TransferProgress progress;
    private final BooleanSupplier cancelled;

    public DownloadTask(String host, int port, int sessionId, int fileId, Path dest,
                        TransferProgress progress, BooleanSupplier cancelled) {
        this.host = host;
        this.port = port;
        this.sessionId = sessionId;
        this.fileId = fileId;
        this.dest = dest;
        this.progress = progress;
        this.cancelled = cancelled;
    }

    @Override
    public Boolean call() throws IOException {
        return download(host, port, sessionId, fileId, dest, progress, cancelled);
    }

    /** @return true if the SHA-256 of the received file matches (verified), false if corrupted */
    public static boolean download(String host, int port, int sessionId, int fileId, Path dest,
                                   TransferProgress progress, BooleanSupplier cancelled) throws IOException {
        boolean verified = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), ProtocolConstants.CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(60_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), ProtocolConstants.CLIENT_CHUNK));

            FrameCodec.writeFrame(out, MessageType.DOWNLOAD_REQ,
                    Payload.writer().writeInt(sessionId).writeInt(fileId).toBytes());
            Frame start = FrameCodec.readFrame(in);
            if (start.type() == MessageType.ERROR) {
                throw new IOException(new Payload.Reader(start.payload()).readString());
            }
            if (start.type() != MessageType.DOWNLOAD_START) throw new IOException("Unexpected reply " + start.type());
            Payload.Reader r = new Payload.Reader(start.payload());
            long size = r.readLong();
            byte[] expected = r.readBytes(ProtocolConstants.HASH_LENGTH);

            MessageDigest digest = Hashing.sha256();
            byte[] buf = new byte[ProtocolConstants.CLIENT_CHUNK];
            long received = 0;
            progress.update(0, size);
            try (OutputStream fileOut = Files.newOutputStream(dest)) {
                while (received < size) {
                    if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                    int n = in.read(buf, 0, (int) Math.min(buf.length, size - received));
                    if (n < 0) throw new IOException("Connection closed before the download finished");
                    fileOut.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    received += n;
                    progress.update(received, size);
                }
            }
            verified = MessageDigest.isEqual(digest.digest(), expected);
            return verified;
        } catch (IOException e) {
            throw NetErrors.friendly(e, host, port);
        } finally {
            if (!verified) Files.deleteIfExists(dest); // failed, cancelled or corrupted
        }
    }
}
