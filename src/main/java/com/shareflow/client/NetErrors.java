package com.shareflow.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Turns low-level networking exceptions into short, readable messages.
 *
 * @author Member 1
 */
public final class NetErrors {
    private NetErrors() {}

    /** Returns a user-friendly exception for the common {@code java.net} failures. */
    public static IOException friendly(IOException e, String host, int port) {
        String where = host + ":" + port;
        if (e instanceof UnknownHostException) {
            return new IOException("Unknown host '" + host + "'. Check the name or IP address.", e);
        }
        if (e instanceof SocketTimeoutException) {
            return new IOException("Timed out talking to " + where + ". Is the server reachable?", e);
        }
        if (e instanceof ConnectException) {
            return new IOException("Cannot connect to " + where + ". Is the server running and the firewall open?", e);
        }
        if (e instanceof NoRouteToHostException) {
            return new IOException("No route to " + where + ". Are you on the same network?", e);
        }
        return e;
    }
}
