package com.shareflow.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
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
 * Downloads one file over its own data connection. The partial destination file is
 * removed if the transfer fails or is cancelled.
 *
 * @author Member 4
 */
public final class DownloadTask extends Task<Path> {
    private final String host;
    private final int port;
    private final int sessionId;
    private final int fileId;
    private final Path dest;

    public DownloadTask(String host, int port, int sessionId, int fileId, Path dest) {
        this.host = host;
        this.port = port;
        this.sessionId = sessionId;
        this.fileId = fileId;
        this.dest = dest;
    }

    @Override
    protected Path call() throws Exception {
        updateMessage("Starting...");
        download(host, port, sessionId, fileId, dest, (done, total) -> {
            updateProgress(done, total);
            updateMessage(Formats.size(done) + " / " + Formats.size(total));
        }, this::isCancelled);
        return dest;
    }

    public static void download(String host, int port, int sessionId, int fileId, Path dest,
                                TransferProgress progress, BooleanSupplier cancelled) throws IOException {
        boolean ok = false;
        try (SocketChannel ch = SocketChannel.open();
             FileChannel out = FileChannel.open(dest, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.socket().connect(new InetSocketAddress(host, port), 5000);
            FrameCodec.writeFrame(ch, MessageType.DOWNLOAD_REQ,
                    Payload.writer().writeInt(sessionId).writeInt(fileId).toBytes());

            Frame start = FrameCodec.readFrame(ch);
            if (start.type() == MessageType.ERROR) {
                throw new IOException(new Payload.Reader(start.payload()).readString());
            }
            if (start.type() != MessageType.DOWNLOAD_START) throw new IOException("Unexpected reply " + start.type());
            long size = new Payload.Reader(start.payload()).readLong();

            ByteBuffer buf = ByteBuffer.allocate(ProtocolConstants.CLIENT_CHUNK);
            long received = 0;
            progress.update(0, size);
            while (received < size) {
                if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                buf.clear();
                if (size - received < buf.capacity()) buf.limit((int) (size - received));
                if (ch.read(buf) < 0) throw new IOException("Connection closed before download finished");
                buf.flip();
                while (buf.hasRemaining()) received += out.write(buf);
                progress.update(received, size);
            }
            ok = true;
        } finally {
            if (!ok) Files.deleteIfExists(dest);
        }
    }
}
