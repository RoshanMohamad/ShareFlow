package com.shareflow.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.shareflow.common.ProtocolConstants;

/**
 * Finds ShareFlow servers on the LAN: broadcasts {@code SHAREFLOW_DISCOVER} over UDP and
 * collects replies for two seconds.
 *
 * @author Member 5
 */
public final class DiscoveryClient {
    private static final int TIMEOUT_MS = 2000;

    public record ServerInfo(String host, int port, String name) {
        @Override
        public String toString() { return name + "  (" + host + ":" + port + ")"; }
    }

    private DiscoveryClient() {}

    public static List<ServerInfo> discover() throws IOException {
        return discover(ProtocolConstants.DISCOVERY_PORT, true);
    }

    /** @param includeLoopback also probe 127.0.0.1 (finds a server on this machine) */
    public static List<ServerInfo> discover(int udpPort, boolean includeLoopback) throws IOException {
        List<ServerInfo> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] probe = ProtocolConstants.DISCOVER_MESSAGE.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : targets(includeLoopback)) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, target, udpPort));
                } catch (IOException ignored) {
                    // this interface may not allow broadcast; try the others
                }
            }

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            byte[] buf = new byte[512];
            while (true) {
                int left = (int) (deadline - System.currentTimeMillis());
                if (left <= 0) break;
                socket.setSoTimeout(left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                ServerInfo info = parse(p);
                if (info != null && seen.add(info.host() + ":" + info.port())) found.add(info);
            }
        }
        return found;
    }

    private static ServerInfo parse(DatagramPacket p) {
        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
        String[] parts = msg.split(";", 3);
        if (parts.length < 2 || !parts[0].equals(ProtocolConstants.SERVER_REPLY_PREFIX)) return null;
        try {
            int port = Integer.parseInt(parts[1]);
            String name = parts.length > 2 ? parts[2] : "ShareFlow";
            return new ServerInfo(p.getAddress().getHostAddress(), port, name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<InetAddress> targets(boolean includeLoopback) throws IOException {
        Set<InetAddress> out = new LinkedHashSet<>();
        out.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) continue;
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
            }
        }
        if (includeLoopback) out.add(InetAddress.getLoopbackAddress());
        return new ArrayList<>(out);
    }
}
