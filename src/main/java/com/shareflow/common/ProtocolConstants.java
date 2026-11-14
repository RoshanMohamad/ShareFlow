package com.shareflow.common;

/**
 * Ports, buffer sizes and magic strings shared by client and server.
 *
 * @author Member 1
 */
public final class ProtocolConstants {
    private ProtocolConstants() {}

    public static final int DEFAULT_TCP_PORT = 5050;
    public static final int DISCOVERY_PORT = 5051;
    public static final int BUFFER_SIZE = 8 * 1024;
    /** Chunk size used by clients when streaming file bytes. */
    public static final int CLIENT_CHUNK = 64 * 1024;
    public static final int HEADER_SIZE = 5;
    /** Largest payload the client may receive (FILE_LIST can be big). */
    public static final int MAX_CLIENT_PAYLOAD = 64 * 1024 * 1024;

    public static final int HASH_LENGTH = 32;
    /** Default upload size limit (2 GB). */
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    public static final int MAX_CONNECTIONS = 50;
    public static final int DISCOVERY_ATTEMPTS = 3;
    public static final int DISCOVERY_WAIT_MS = 1000;
    public static final int TRANSFER_THREADS = 3;
    public static final int CONNECT_TIMEOUT_MS = 5000;

    public static final String DISCOVER_MESSAGE = "SHAREFLOW_DISCOVER";
    public static final String SERVER_REPLY_PREFIX = "SHAREFLOW_SERVER";
}
