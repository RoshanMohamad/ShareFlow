package com.shareflow.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;

import com.shareflow.common.ProtocolConstants;

/**
 * Answers UDP discovery broadcasts. The {@link DatagramChannel} is registered with the
 * same {@link Selector} as the TCP channels.
 *
 * @author Member 5
 */
public final class DiscoveryResponder implements AutoCloseable {
    private final DatagramChannel channel;
    private final byte[] reply;
    private final ByteBuffer buf = ByteBuffer.allocate(512);

    public DiscoveryResponder(Selector selector, int udpPort, int tcpPort, String serverName) throws IOException {
        channel = DatagramChannel.open();
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.bind(new InetSocketAddress(udpPort));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ, this);
        reply = (ProtocolConstants.SERVER_REPLY_PREFIX + ";" + tcpPort + ";" + serverName)
                .getBytes(StandardCharsets.UTF_8);
    }

    public int localPort() throws IOException {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    /** Called by the event loop when the datagram channel is readable. */
    public void onReadable() throws IOException {
        while (true) {
            buf.clear();
            SocketAddress from = channel.receive(buf);
            if (from == null) return;
            buf.flip();
            String msg = StandardCharsets.UTF_8.decode(buf).toString().trim();
            if (msg.equals(ProtocolConstants.DISCOVER_MESSAGE)) {
                channel.send(ByteBuffer.wrap(reply), from);
                ServerMain.log("Discovery request from " + from);
            }
        }
    }

    @Override
    public void close() throws IOException { channel.close(); }
}
