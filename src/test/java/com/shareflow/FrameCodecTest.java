package com.shareflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.shareflow.common.Frame;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;

class FrameCodecTest {

    @Test
    void frameFedOneByteAtATimeDecodes() throws IOException {
        byte[] payload = Payload.writer().writeString("héllo wörld").writeInt(42).toBytes();
        ByteBuffer wire = FrameCodec.encode(MessageType.USER_JOINED, payload);
        ByteBuffer acc = ByteBuffer.allocate(8192);

        Frame decoded = null;
        while (wire.hasRemaining()) {
            assertNull(decoded, "frame must only complete on the last byte");
            acc.put(wire.get());
            decoded = FrameCodec.decode(acc, 8000);
        }
        assertNotNull(decoded);
        assertEquals(MessageType.USER_JOINED, decoded.type());
        Payload.Reader r = new Payload.Reader(decoded.payload());
        assertEquals("héllo wörld", r.readString());
        assertEquals(42, r.readInt());
        assertEquals(0, acc.position());
    }

    @Test
    void twoFramesInOneReadDecodeSeparatelyAndLeftoverIsKept() throws IOException {
        ByteBuffer a = FrameCodec.encode(MessageType.LIST_FILES);
        ByteBuffer b = FrameCodec.encode(MessageType.BYE);
        ByteBuffer acc = ByteBuffer.allocate(8192);
        acc.put(a).put(b).put((byte) 0x7F); // plus a stray byte of a future frame

        assertEquals(MessageType.LIST_FILES, FrameCodec.decode(acc, 8000).type());
        assertEquals(MessageType.BYE, FrameCodec.decode(acc, 8000).type());
        assertNull(FrameCodec.decode(acc, 8000));
        assertEquals(1, acc.position());
    }

    @Test
    void oversizedAndUnknownFramesAreRejected() {
        ByteBuffer big = ByteBuffer.allocate(64);
        big.put(MessageType.HELLO.code).putInt(1_000_000);
        assertThrows(IOException.class, () -> FrameCodec.decode(big, 8000));

        ByteBuffer unknown = ByteBuffer.allocate(64);
        unknown.put((byte) 0x7E).putInt(0);
        assertThrows(IOException.class, () -> FrameCodec.decode(unknown, 8000));
    }
}
