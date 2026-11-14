package com.shareflow.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Builders/readers for frame payloads. Integers are big-endian, strings are
 * {@code [length: 2B][UTF-8 bytes]}.
 *
 * @author Member 1
 */
public final class Payload {
    private Payload() {}

    public static Writer writer() { return new Writer(); }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer writeInt(int v) {
            out.write(v >>> 24); out.write(v >>> 16); out.write(v >>> 8); out.write(v);
            return this;
        }

        public Writer writeLong(long v) {
            writeInt((int) (v >>> 32));
            return writeInt((int) v);
        }

        public Writer writeString(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 0xFFFF) throw new IllegalArgumentException("String too long");
            out.write(b.length >>> 8); out.write(b.length);
            out.writeBytes(b);
            return this;
        }

        public Writer writeBytes(byte[] b) {
            out.writeBytes(b);
            return this;
        }

        public byte[] toBytes() { return out.toByteArray(); }
    }

    public static final class Reader {
        private final ByteBuffer buf;

        public Reader(byte[] data) { this.buf = ByteBuffer.wrap(data); }

        public int readInt() throws IOException {
            need(4);
            return buf.getInt();
        }

        public long readLong() throws IOException {
            need(8);
            return buf.getLong();
        }

        public byte[] readBytes(int n) throws IOException {
            need(n);
            byte[] b = new byte[n];
            buf.get(b);
            return b;
        }

        public String readString() throws IOException {
            need(2);
            int len = buf.getShort() & 0xFFFF;
            need(len);
            byte[] b = new byte[len];
            buf.get(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        private void need(int n) throws IOException {
            if (buf.remaining() < n) throw new IOException("Malformed payload");
        }
    }
}
