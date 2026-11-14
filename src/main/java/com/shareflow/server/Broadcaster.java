package com.shareflow.server;

import java.nio.ByteBuffer;

import com.shareflow.common.FrameCodec;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;

/**
 * Sends frames to one or all control connections. Each frame is encoded once and every
 * recipient gets its own {@code duplicate()} so write positions stay independent.
 *
 * @author Member 5
 */
public final class Broadcaster {
    private final ClientRegistry registry;

    public Broadcaster(ClientRegistry registry) { this.registry = registry; }

    public void send(ConnectionContext ctx, MessageType type, byte[] payload) {
        ctx.enqueue(FrameCodec.encode(type, payload));
    }

    public void sendError(ConnectionContext ctx, String message) {
        send(ctx, MessageType.ERROR, Payload.writer().writeString(message).toBytes());
    }

    public void toAll(MessageType type, byte[] payload) {
        toAllExcept(null, type, payload);
    }

    public void toAllExcept(ConnectionContext except, MessageType type, byte[] payload) {
        ByteBuffer frame = FrameCodec.encode(type, payload);
        for (ConnectionContext c : registry.all()) {
            if (c != except) c.enqueue(frame.duplicate());
        }
    }
}
