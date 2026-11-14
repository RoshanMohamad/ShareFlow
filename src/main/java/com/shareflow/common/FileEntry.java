package com.shareflow.common;

import java.io.IOException;

/**
 * Metadata of one shared file: {@code [fileId 4B][size 8B][name][uploader][uploadedAt 8B]}.
 *
 * @author Member 3
 */
public record FileEntry(int id, long size, String name, String uploader, long uploadedAt) {

    public void writeTo(Payload.Writer w) {
        w.writeInt(id).writeLong(size).writeString(name).writeString(uploader).writeLong(uploadedAt);
    }

    public static FileEntry readFrom(Payload.Reader r) throws IOException {
        int id = r.readInt();
        long size = r.readLong();
        String name = r.readString();
        String uploader = r.readString();
        long at = r.readLong();
        return new FileEntry(id, size, name, uploader, at);
    }

    public byte[] toPayload() {
        Payload.Writer w = Payload.writer();
        writeTo(w);
        return w.toBytes();
    }
}
