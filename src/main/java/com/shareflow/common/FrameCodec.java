package com.shareflow.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Length-prefixed framing: {@code [type 1B][length 4B][payload]}.
 * <p>
 * {@link #decode} is built for non-blocking reads: the accumulation buffer keeps
 * incomplete bytes until the whole frame has arrived, so a frame split across any
 * number of reads (even one byte at a time) decodes correctly.
 *
 * @author Member 1
 */
public final class FrameCodec {
    private FrameCodec() {}

    public static ByteBuffer encode(MessageType type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE + payload.length);
        b.put(type.code).putInt(payload.length).put(payload);
        b.flip();
        return b;
    }

    public static ByteBuffer encode(MessageType type) {
        return encode(type, new byte[0]);
    }

    /**
     * Tries to extract one frame from {@code acc}, which must be in write mode
     * (i.e. just after a channel read). Consumed bytes are removed; leftover bytes stay
     * in the buffer, still in write mode.
     *
     * @return the frame, or {@code null} if more bytes are needed
     * @throws IOException on an unknown type or a payload larger than {@code maxPayload}
     */
    public static Frame decode(ByteBuffer acc, int maxPayload) throws IOException {
        acc.flip();
        try {
            if (acc.remaining() < ProtocolConstants.HEADER_SIZE) return null;
            acc.mark();
            byte code = acc.get();
            int len = acc.getInt();
            if (len < 0 || len > maxPayload) throw new IOException("Frame too large: " + len);
            if (acc.remaining() < len) {
                acc.reset(); // frame incomplete: keep every byte for the next read
                return null;
            }
            MessageType type = MessageType.fromCode(code);
            byte[] payload = new byte[len];
            acc.get(payload);
            return new Frame(type, payload);
        } finally {
            acc.compact();
        }
    }

    /** Client side (java.net): blocks until the whole frame has arrived. */
    public static Frame readFrame(DataInputStream in) throws IOException {
        byte code = in.readByte();
        int len = in.readInt();
        if (len < 0 || len > ProtocolConstants.MAX_CLIENT_PAYLOAD) throw new IOException("Bad frame length " + len);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Frame(MessageType.fromCode(code), payload);
    }

    public static void writeFrame(DataOutputStream out, MessageType type, byte[] payload) throws IOException {
        out.writeByte(type.code);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /** Blocking read of one frame from a channel. */
    public static Frame readFrame(ReadableByteChannel ch) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE);
        readFully(ch, header);
        header.flip();
        byte code = header.get();
        int len = header.getInt();
        if (len < 0 || len > ProtocolConstants.MAX_CLIENT_PAYLOAD) throw new IOException("Bad frame length " + len);
        ByteBuffer body = ByteBuffer.allocate(len);
        readFully(ch, body);
        return new Frame(MessageType.fromCode(code), body.array());
    }

    public static void writeFrame(WritableByteChannel ch, MessageType type, byte[] payload) throws IOException {
        writeFully(ch, encode(type, payload));
    }

    public static void readFully(ReadableByteChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new IOException("Connection closed");
        }
    }

    public static void writeFully(WritableByteChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }
}
