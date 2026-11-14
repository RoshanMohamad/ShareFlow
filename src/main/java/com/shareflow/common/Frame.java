package com.shareflow.common;

/**
 * A decoded protocol frame.
 *
 * @author Member 1
 */
public record Frame(MessageType type, byte[] payload) {}
