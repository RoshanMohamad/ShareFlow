package com.shareflow.server;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

import com.shareflow.common.FileEntry;
import com.shareflow.common.Formats;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;

/**
 * Server side of a download. The file goes out with {@link FileChannel#transferTo}
 * (zero-copy). When the socket buffer is full {@code transferTo} returns 0, we simply
 * keep OP_WRITE registered and continue when the selector says the socket is writable
 * again, so a slow client never blocks the event loop.
 *
 * @author Member 4
 */
final class DownloadHandler {
    private static final long CHUNK = 1024 * 1024;

    private DownloadHandler() {}

    static void start(EventLoop loop, ConnectionContext ctx, byte[] payload) throws IOException {
        Payload.Reader r = new Payload.Reader(payload);
        int sessionId = r.readInt();
        int fileId = r.readInt();

        ConnectionContext owner = loop.registry().bySession(sessionId);
        FileEntry entry = loop.repository().find(fileId);
        if (owner == null || entry == null) {
            loop.broadcaster().sendError(ctx, owner == null ? "Unknown session" : "No such file");
            ctx.closeAfterFlush();
            return;
        }
        FileChannel fc;
        try {
            fc = FileChannel.open(loop.repository().pathOf(entry), StandardOpenOption.READ);
        } catch (IOException e) {
            loop.broadcaster().sendError(ctx, "File not available");
            ctx.closeAfterFlush();
            return;
        }
        ctx.downloadChannel = fc;
        ctx.downloadSize = fc.size();
        ctx.downloadPos = 0;
        ctx.downloadName = entry.name();
        ctx.uploader = owner.username; // reused as "who" for logging
        ctx.state = ConnectionContext.State.DOWNLOADING;
        loop.broadcaster().send(ctx, MessageType.DOWNLOAD_START,
                Payload.writer().writeLong(ctx.downloadSize).toBytes());
        ServerMain.log(owner.username + " downloading '" + entry.name() + "' (" + Formats.size(ctx.downloadSize) + ")");
    }

    /**
     * Pushes as much of the file as the socket accepts.
     *
     * @return true when the whole file has been sent
     */
    static boolean pump(ConnectionContext ctx) throws IOException {
        while (ctx.downloadPos < ctx.downloadSize) {
            long n = ctx.downloadChannel.transferTo(ctx.downloadPos,
                    Math.min(CHUNK, ctx.downloadSize - ctx.downloadPos), ctx.channel);
            if (n <= 0) return false; // socket buffer full: wait for OP_WRITE
            ctx.downloadPos += n;
        }
        ServerMain.log(ctx.uploader + " finished downloading '" + ctx.downloadName + "'");
        return true;
    }
}
