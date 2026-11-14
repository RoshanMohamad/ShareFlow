package com.shareflow.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.shareflow.common.Payload;

/**
 * Logged-in users (control connections). Event-loop thread only.
 *
 * @author Member 5
 */
public final class ClientRegistry {
    private final Map<Integer, ConnectionContext> bySession = new HashMap<>();
    private final Map<String, ConnectionContext> byName = new HashMap<>();
    private int nextSession = 1;

    public boolean isTaken(String username) {
        return byName.containsKey(username.toLowerCase(Locale.ROOT));
    }

    public int nextSessionId() { return nextSession++; }

    public void add(ConnectionContext ctx) {
        bySession.put(ctx.sessionId, ctx);
        byName.put(ctx.username.toLowerCase(Locale.ROOT), ctx);
    }

    /** @return true if the context was a registered user */
    public boolean remove(ConnectionContext ctx) {
        if (ctx.username == null || bySession.get(ctx.sessionId) != ctx) return false;
        bySession.remove(ctx.sessionId);
        byName.remove(ctx.username.toLowerCase(Locale.ROOT));
        return true;
    }

    public ConnectionContext bySession(int sessionId) { return bySession.get(sessionId); }

    public List<ConnectionContext> all() { return new ArrayList<>(bySession.values()); }

    public int size() { return bySession.size(); }

    /** USER_LIST payload: count, then (username, ip) pairs. */
    public byte[] userListPayload() {
        Payload.Writer w = Payload.writer().writeInt(bySession.size());
        for (ConnectionContext c : bySession.values()) w.writeString(c.username).writeString(c.ip);
        return w.toBytes();
    }
}
