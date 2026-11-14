package com.shareflow.common;

/**
 * Small formatting helpers.
 *
 * @author Member 1
 */
public final class Formats {
    private Formats() {}

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }
}
