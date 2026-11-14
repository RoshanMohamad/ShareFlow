package com.shareflow.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

import com.shareflow.common.FileEntry;
import com.shareflow.common.Formats;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;

/**
 * Server side of an upload: socket bytes go through a {@link ByteBuffer} into a
 * {@code name.part} {@link FileChannel}; the file is renamed only when all bytes arrived.
 *
 * @author Member 3
 */
final class UploadHandler {
    private UploadHandler() {}

    /** Handles UPLOAD_START; bytes already in the read buffer belong to the file. */
    static void start(EventLoop loop, ConnectionContext ctx, byte[] payload) throws IOException {
        Payload.Reader r = new Payload.Reader(payload);
        int sessionId = r.readInt();
        String fileName = r.readString();
        long size = r.readLong();

        ConnectionContext owner = loop.registry().bySession(sessionId);
        if (owner == null) { reject(loop, ctx, "Unknown session"); return; }
        if (size < 0) { reject(loop, ctx, "Invalid file size"); return; }

        FileRepository.Reservation res = loop.repository().reserve(fileName);
        ctx.reservation = res;
        ctx.uploader = owner.username;
        ctx.uploadChannel = FileChannel.open(res.partPath(), StandardOpenOption.WRITE);
        ctx.uploadRemaining = size;
        ctx.uploadTotal = size;
        ctx.state = ConnectionContext.State.UPLOADING;
        ServerMain.log(owner.username + " uploading '" + res.finalName() + "' (" + Formats.size(size) + ")");
        process(loop, ctx); // flush bytes that arrived together with the header
    }

    /** Called after every socket read while UPLOADING: buffer is in write mode. */
    static void process(EventLoop loop, ConnectionContext ctx) throws IOException {
        ByteBuffer buf = ctx.readBuf;
        buf.flip();
        if (buf.remaining() > ctx.uploadRemaining) {
            buf.limit(buf.position() + (int) ctx.uploadRemaining); // ignore surplus bytes
        }
        while (buf.hasRemaining()) {
            ctx.uploadRemaining -= ctx.uploadChannel.write(buf);
        }
        buf.clear();
        if (ctx.uploadRemaining == 0) {
            complete(loop, ctx);
        } else if (ctx.uploadRemaining < buf.capacity()) {
            buf.limit((int) ctx.uploadRemaining); // never read past the end of this file
        }
    }

    private static void complete(EventLoop loop, ConnectionContext ctx) throws IOException {
        ctx.uploadChannel.force(false);
        ctx.uploadChannel.close();
        ctx.uploadChannel = null;
        FileRepository.Reservation res = ctx.reservation;
        FileEntry entry = loop.repository().commit(res, ctx.uploader);
        ctx.reservation = null;
        ServerMain.log(ctx.uploader + " uploaded '" + entry.name() + "' (" + Formats.size(entry.size()) + ")");

        loop.broadcaster().send(ctx, MessageType.UPLOAD_DONE, Payload.writer().writeInt(entry.id()).toBytes());
        ctx.closeAfterFlush();
        loop.broadcaster().toAll(MessageType.FILE_ADDED, entry.toPayload());
    }

    private static void reject(EventLoop loop, ConnectionContext ctx, String message) {
        loop.broadcaster().sendError(ctx, message);
        ctx.closeAfterFlush();
    }

    /** Used by EventLoop when a connection dies: drop the half-written file. */
    static void abort(EventLoop loop, ConnectionContext ctx) {
        if (ctx.reservation != null) {
            ctx.releaseResources();
            loop.repository().discard(ctx.reservation);
            ServerMain.log("Upload aborted, removed " + ctx.reservation.partPath().getFileName());
            ctx.reservation = null;
        }
    }
}
