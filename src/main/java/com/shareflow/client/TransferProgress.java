package com.shareflow.client;

/**
 * Progress callback for transfers.
 *
 * @author Member 3
 */
@FunctionalInterface
public interface TransferProgress {
    void update(long done, long total);
}
