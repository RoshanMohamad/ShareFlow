package com.shareflow.common;

import java.io.IOException;

/**
 * One-byte frame type codes (protocol section 5).
 *
 * @author Member 1
 */
public enum MessageType {
    HELLO(0x01), WELCOME(0x02), LIST_FILES(0x03), FILE_LIST(0x04), USER_LIST(0x05),
    USER_JOINED(0x06), USER_LEFT(0x07), FILE_ADDED(0x08), ERROR(0x09), BYE(0x0A),
    UPLOAD_START(0x20), UPLOAD_DONE(0x21), DOWNLOAD_REQ(0x22), DOWNLOAD_START(0x23);

    public final byte code;

    MessageType(int code) { this.code = (byte) code; }

    public static MessageType fromCode(byte code) throws IOException {
        for (MessageType t : values()) {
            if (t.code == code) return t;
        }
        throw new IOException("Unknown message type 0x" + Integer.toHexString(code & 0xFF));
    }
}
