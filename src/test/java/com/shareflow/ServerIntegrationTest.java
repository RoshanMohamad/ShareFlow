package com.shareflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shareflow.client.ClientConnection;
import com.shareflow.client.DiscoveryClient;
import com.shareflow.client.DownloadTask;
import com.shareflow.client.UploadTask;
import com.shareflow.common.FileEntry;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;
import com.shareflow.common.UserInfo;
import com.shareflow.server.EventLoop;

/** Starts a real server on loopback and drives it with real clients. */
class ServerIntegrationTest {
    @TempDir Path tmp;
    EventLoop server;
    Thread loopThread;
    Path shared;
    int port;

    /** Collects events as strings. */
    static class Events implements ClientConnection.Listener {
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        final BlockingQueue<FileEntry> added = new LinkedBlockingQueue<>();
        @Override public void onUserList(List<UserInfo> u) { q.add("list:" + u.size()); }
        @Override public void onUserJoined(UserInfo u) { q.add("joined:" + u.username()); }
        @Override public void onUserLeft(String n) { q.add("left:" + n); }
        @Override public void onFileAdded(FileEntry f) { added.add(f); }
        String await(String prefix) throws InterruptedException {
            while (true) {
                String s = q.poll(5, TimeUnit.SECONDS);
                assertNotNull(s, "timed out waiting for " + prefix);
                if (s.startsWith(prefix)) return s;
            }
        }
    }

    @BeforeEach
    void start() throws Exception {
        shared = tmp.resolve("shared");
        server = new EventLoop(0, 0, "TestServer", shared);
        port = server.tcpPort();
        loopThread = new Thread(server, "test-loop");
        loopThread.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
        loopThread.join(5000);
    }

    @Test
    void handshakeJoinLeaveAndDuplicateUsername() throws Exception {
        Events ea = new Events(), eb = new Events();
        ClientConnection a = ClientConnection.connect("127.0.0.1", port, "alice", ea);
        ea.await("list:1");
        ClientConnection b = ClientConnection.connect("127.0.0.1", port, "bob", eb);
        assertEquals("joined:bob", ea.await("joined"));
        eb.await("list:2");

        IOException dup = assertThrows(IOException.class,
                () -> ClientConnection.connect("127.0.0.1", port, "Alice", new Events()));
        assertTrue(dup.getMessage().contains("already in use"), dup.getMessage());

        b.close();
        assertEquals("left:bob", ea.await("left"));
        a.close();
    }

    @Test
    void uploadDownloadLargeFileKeepsChecksumAndNotifiesEveryone() throws Exception {
        Events ea = new Events(), eb = new Events();
        ClientConnection a = ClientConnection.connect("127.0.0.1", port, "alice", ea);
        ClientConnection b = ClientConnection.connect("127.0.0.1", port, "bob", eb);

        Path src = tmp.resolve("big.bin");
        byte[] chunk = new byte[1 << 20];
        new Random(1).nextBytes(chunk);
        try (var out = Files.newOutputStream(src)) {
            for (int i = 0; i < 40; i++) out.write(chunk); // 40 MB
        }
        long[] last = {0};
        int id = UploadTask.upload("127.0.0.1", port, a.sessionId(), src, (d, t) -> last[0] = d, () -> false);
        assertEquals(Files.size(src), last[0]);

        FileEntry fa = ea.added.poll(5, TimeUnit.SECONDS);
        FileEntry fb = eb.added.poll(5, TimeUnit.SECONDS);
        assertNotNull(fa);
        assertNotNull(fb);
        assertEquals(id, fb.id());
        assertEquals("alice", fb.uploader());
        assertEquals("big.bin", fb.name());

        Path dest = tmp.resolve("copy.bin");
        DownloadTask.download("127.0.0.1", port, b.sessionId(), id, dest, (d, t) -> { }, () -> false);
        assertArrayEquals(sha(src), sha(dest));

        try (Stream<Path> s = Files.list(shared)) {
            assertTrue(s.noneMatch(p -> p.toString().endsWith(".part")));
        }
        a.close();
        b.close();
    }

    @Test
    void duplicateNamesGetSuffixAndPathTraversalIsStripped() throws Exception {
        ClientConnection a = ClientConnection.connect("127.0.0.1", port, "alice", new Events());
        Path f = tmp.resolve("notes.pdf");
        Files.writeString(f, "hello");
        UploadTask.upload("127.0.0.1", port, a.sessionId(), f, (d, t) -> { }, () -> false);
        UploadTask.upload("127.0.0.1", port, a.sessionId(), f, (d, t) -> { }, () -> false);
        assertTrue(Files.exists(shared.resolve("notes.pdf")));
        assertTrue(Files.exists(shared.resolve("notes (1).pdf")));

        // raw client sending a hostile file name
        try (SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            FrameCodec.writeFrame(ch, MessageType.UPLOAD_START, Payload.writer()
                    .writeInt(a.sessionId()).writeString("../../evil.txt").writeLong(3).toBytes());
            ch.write(ByteBuffer.wrap("abc".getBytes()));
            assertEquals(MessageType.UPLOAD_DONE, FrameCodec.readFrame(ch).type());
        }
        assertTrue(Files.exists(shared.resolve("evil.txt")));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
        a.close();
    }

    @Test
    void clientDroppingMidUploadLeavesNoPartFileAndServerSurvives() throws Exception {
        Events ea = new Events();
        ClientConnection a = ClientConnection.connect("127.0.0.1", port, "alice", ea);

        try (SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            FrameCodec.writeFrame(ch, MessageType.UPLOAD_START, Payload.writer()
                    .writeInt(a.sessionId()).writeString("half.bin").writeLong(10_000_000).toBytes());
            ch.write(ByteBuffer.wrap(new byte[100_000]));
            long deadline = System.currentTimeMillis() + 3000;
            while (!Files.exists(shared.resolve("half.bin.part")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(shared.resolve("half.bin.part")));
        } // socket closed here without finishing

        long deadline = System.currentTimeMillis() + 5000;
        while (Files.exists(shared.resolve("half.bin.part")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(Files.exists(shared.resolve("half.bin.part")));
        assertFalse(Files.exists(shared.resolve("half.bin")));

        // server still serves
        ClientConnection b = ClientConnection.connect("127.0.0.1", port, "bob", new Events());
        b.close();
        a.close();
    }

    @Test
    void udpDiscoveryFindsServer() throws Exception {
        var found = DiscoveryClient.discover(server.udpPort(), true);
        assertTrue(found.stream().anyMatch(s -> s.port() == port && s.name().equals("TestServer")), found.toString());
    }

    private static byte[] sha(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return md.digest();
    }
}
