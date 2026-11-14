package com.shareflow.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Iterator;

import com.shareflow.common.Frame;
import com.shareflow.common.FrameCodec;
import com.shareflow.common.MessageType;
import com.shareflow.common.Payload;
import com.shareflow.common.ProtocolConstants;

/**
 * The single-threaded NIO event loop: one {@link Selector} handles accepts, reads,
 * writes and UDP discovery for every client.
 *
 * @author Member 2
 */
public final class EventLoop implements Runnable, AutoCloseable {
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final DiscoveryResponder discovery;
    private final FileRepository repository;
    private final ClientRegistry registry = new ClientRegistry();
    private final Broadcaster broadcaster = new Broadcaster(registry);
    private volatile boolean running = true;

    public EventLoop(int tcpPort, int udpPort, String serverName, Path sharedDir) throws IOException {
        repository = new FileRepository(sharedDir);
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(tcpPort));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        discovery = new DiscoveryResponder(selector, udpPort, tcpPort(), serverName);
    }

    public int tcpPort() { return ((InetSocketAddress) localAddress()).getPort(); }

    public int udpPort() throws IOException { return discovery.localPort(); }

    private java.net.SocketAddress localAddress() {
        try { return serverChannel.getLocalAddress(); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    FileRepository repository() { return repository; }
    ClientRegistry registry() { return registry; }
    Broadcaster broadcaster() { return broadcaster; }

    @Override
    public void run() {
        try {
            while (running) {
                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    dispatch(key);
                }
            }
        } catch (IOException e) {
            if (running) ServerMain.log("Event loop failed: " + e);
        } finally {
            shutdown();
        }
    }

    private void dispatch(SelectionKey key) {
        Object att = key.attachment();
        try {
            if (att instanceof ConnectionContext ctx) {
                try {
                    if (key.isValid() && key.isReadable()) handleRead(ctx);
                    if (key.isValid() && !ctx.closed && key.isWritable()) handleWrite(ctx);
                } catch (IOException e) {
                    close(ctx, e.getMessage());
                } catch (RuntimeException e) {
                    ServerMain.log("Error handling " + ctx.describe() + ": " + e);
                    close(ctx, "internal error");
                }
            } else if (att instanceof DiscoveryResponder d) {
                if (key.isReadable()) d.onReadable();
            } else if (key.isAcceptable()) {
                accept();
            }
        } catch (CancelledKeyException ignored) {
            // connection closed while processing
        } catch (IOException e) {
            ServerMain.log("I/O error: " + e);
        }
    }

    private void accept() throws IOException {
        SocketChannel ch;
        while ((ch = serverChannel.accept()) != null) {
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
            String ip = ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress();
            key.attach(new ConnectionContext(ch, key, ip));
        }
    }

    // ---------------------------------------------------------------- reading

    private void handleRead(ConnectionContext ctx) throws IOException {
        int n = ctx.channel.read(ctx.readBuf);
        if (n < 0) {
            close(ctx, "disconnected");
            return;
        }
        switch (ctx.state) {
            case UPLOADING -> UploadHandler.process(this, ctx);
            case NEW, CONTROL -> {
                Frame f;
                while ((ctx.state == ConnectionContext.State.NEW || ctx.state == ConnectionContext.State.CONTROL)
                        && (f = FrameCodec.decode(ctx.readBuf, ctx.readBuf.capacity() - ProtocolConstants.HEADER_SIZE)) != null) {
                    onFrame(ctx, f);
                    if (ctx.closed) return;
                }
                // a data connection may have started: bytes already buffered belong to it
                if (ctx.state == ConnectionContext.State.UPLOADING && ctx.readBuf.position() > 0) {
                    UploadHandler.process(this, ctx);
                }
            }
            case DOWNLOADING -> ctx.readBuf.clear(); // client should send nothing; discard
            case CLOSING -> { }
        }
    }

    private void onFrame(ConnectionContext ctx, Frame f) throws IOException {
        if (ctx.state == ConnectionContext.State.NEW) {
            switch (f.type()) {
                case HELLO -> login(ctx, f.payload());
                case UPLOAD_START -> UploadHandler.start(this, ctx, f.payload());
                case DOWNLOAD_REQ -> DownloadHandler.start(this, ctx, f.payload());
                default -> {
                    broadcaster.sendError(ctx, "Expected HELLO");
                    ctx.closeAfterFlush();
                }
            }
            return;
        }
        switch (f.type()) {
            case LIST_FILES -> sendFileList(ctx);
            case BYE -> close(ctx, "said goodbye");
            default -> broadcaster.sendError(ctx, "Unexpected message " + f.type());
        }
    }

    private void login(ConnectionContext ctx, byte[] payload) throws IOException {
        String name = new Payload.Reader(payload).readString().strip();
        if (name.isEmpty() || name.length() > 32) {
            broadcaster.sendError(ctx, "Username must be 1-32 characters");
            ctx.closeAfterFlush();
            return;
        }
        if (registry.isTaken(name)) {
            broadcaster.sendError(ctx, "Username '" + name + "' is already in use");
            ctx.closeAfterFlush();
            return;
        }
        ctx.username = name;
        ctx.sessionId = registry.nextSessionId();
        ctx.state = ConnectionContext.State.CONTROL;
        registry.add(ctx);

        broadcaster.send(ctx, MessageType.WELCOME, Payload.writer().writeInt(ctx.sessionId).toBytes());
        broadcaster.send(ctx, MessageType.USER_LIST, registry.userListPayload());
        sendFileList(ctx);
        broadcaster.toAllExcept(ctx, MessageType.USER_JOINED,
                Payload.writer().writeString(ctx.username).writeString(ctx.ip).toBytes());
        ServerMain.log(ctx.username + " joined from " + ctx.ip + " (" + registry.size() + " online)");
    }

    private void sendFileList(ConnectionContext ctx) {
        var files = repository.list();
        Payload.Writer w = Payload.writer().writeInt(files.size());
        files.forEach(e -> e.writeTo(w));
        broadcaster.send(ctx, MessageType.FILE_LIST, w.toBytes());
    }

    // ---------------------------------------------------------------- writing

    private void handleWrite(ConnectionContext ctx) throws IOException {
        ByteBuffer b;
        while ((b = ctx.writeQueue.peek()) != null) {
            ctx.channel.write(b);
            if (b.hasRemaining()) return; // socket full: stay on OP_WRITE
            ctx.writeQueue.poll();
        }
        if (ctx.state == ConnectionContext.State.DOWNLOADING) {
            if (!DownloadHandler.pump(ctx)) return; // more to send later
            close(ctx, null);
            return;
        }
        if (ctx.closeAfterWrite) {
            close(ctx, null);
            return;
        }
        ctx.key.interestOps(ctx.key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    // ---------------------------------------------------------------- cleanup

    void close(ConnectionContext ctx, String reason) {
        if (ctx.closed) return;
        ctx.closed = true;
        ctx.key.cancel();
        try { ctx.channel.close(); } catch (IOException ignored) { }

        if (ctx.state == ConnectionContext.State.UPLOADING) UploadHandler.abort(this, ctx);
        ctx.releaseResources();
        ctx.writeQueue.clear();

        if (registry.remove(ctx)) {
            broadcaster.toAll(MessageType.USER_LEFT, Payload.writer().writeString(ctx.username).toBytes());
            ServerMain.log(ctx.username + " left" + (reason != null ? " (" + reason + ")" : "")
                    + " - " + registry.size() + " online");
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public void stop() {
        running = false;
        selector.wakeup();
    }

    private void shutdown() {
        for (SelectionKey k : selector.keys()) {
            if (k.attachment() instanceof ConnectionContext c) {
                if (c.state == ConnectionContext.State.UPLOADING) UploadHandler.abort(this, c);
                c.releaseResources();
            }
            try { k.channel().close(); } catch (IOException ignored) { }
        }
        try { discovery.close(); } catch (IOException ignored) { }
        try { selector.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() { stop(); }
}
