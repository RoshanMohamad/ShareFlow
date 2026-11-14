package com.shareflow.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import com.shareflow.common.ProtocolConstants;

/**
 * Headless server entry point.
 * <pre>Usage: ServerMain [tcpPort] [serverName] [sharedDir]</pre>
 *
 * @author Member 2
 */
public final class ServerMain {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ServerMain() {}

    public static synchronized void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : ProtocolConstants.DEFAULT_TCP_PORT;
        String name = args.length > 1 ? args[1] : hostName();
        Path shared = Path.of(args.length > 2 ? args[2] : "shared");

        EventLoop loop = new EventLoop(port, ProtocolConstants.DISCOVERY_PORT, name, shared);
        System.out.println("=== ShareFlow Server '" + name + "' ===");
        System.out.println("Shared folder : " + shared.toAbsolutePath());
        System.out.println("TCP port      : " + loop.tcpPort() + "   UDP discovery port: " + ProtocolConstants.DISCOVERY_PORT);
        System.out.println("Clients can connect to:");
        printLanAddresses(loop.tcpPort());
        System.out.println("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(loop::stop));
        loop.run(); // the one and only I/O thread
    }

    private static void printLanAddresses(int port) throws Exception {
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
            for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                if (a instanceof Inet4Address) {
                    System.out.println("   " + a.getHostAddress() + ":" + port + "   (" + nif.getDisplayName() + ")");
                }
            }
        }
    }

    private static String hostName() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "ShareFlow"; }
    }
}
