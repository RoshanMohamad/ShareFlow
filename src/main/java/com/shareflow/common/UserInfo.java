package com.shareflow.common;

/**
 * A connected user as shown in the dashboard.
 *
 * @author Member 5
 */
public record UserInfo(String username, String ip) {
    @Override
    public String toString() { return username + "  (" + ip + ")"; }
}
