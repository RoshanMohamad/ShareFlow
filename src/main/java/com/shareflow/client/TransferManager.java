package com.shareflow.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.shareflow.common.ProtocolConstants;

/**
 * Runs uploads and downloads on a fixed pool of {@value ProtocolConstants#TRANSFER_THREADS}
 * threads. Each transfer is a {@link Callable} submitted to an {@link ExecutorService} and
 * tracked through its {@link Future}. The transfer list and all state changes are guarded
 * by this object's monitor, so worker threads and the UI thread never race. Extra transfers
 * wait in the pool's queue (state QUEUED).
 * <p>
 * Listeners are called on worker threads; UI code must use {@code Platform.runLater}.
 *
 * @author Member 4
 */
public final class TransferManager implements AutoCloseable {

    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /** One transfer. Fields are only changed while holding the manager's lock. */
    public static final class Transfer {
        private final int id;
        private final boolean upload;
        private final String name;
        private State state = State.QUEUED;
        private long done;
        private long total;
        private String message = "Queued";
        private boolean cancelRequested;
        private Future<?> future;
        private long lastNotifyNanos;

        private Transfer(int id, boolean upload, String name) {
            this.id = id;
            this.upload = upload;
            this.name = name;
        }

        public int id() { return id; }
        public boolean isUpload() { return upload; }
        public String name() { return name; }
        public boolean isCancelRequested() { return cancelRequested; }
        public boolean isFinished() { return state != State.QUEUED && state != State.RUNNING; }
    }

    /** Immutable view of a transfer, safe to hand to the UI. */
    public record Snapshot(Transfer transfer, State state, long done, long total, String message) {
        public double fraction() { return total <= 0 ? 0 : (double) done / total; }
        public boolean finished() { return state != State.QUEUED && state != State.RUNNING; }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(ProtocolConstants.TRANSFER_THREADS, r -> {
        Thread t = new Thread(r, "shareflow-transfer");
        t.setDaemon(true);
        return t;
    });
    private final List<Transfer> transfers = new ArrayList<>();
    private Consumer<Snapshot> listener = s -> { };
    private int nextId = 1;

    public synchronized void setListener(Consumer<Snapshot> listener) {
        this.listener = listener;
    }

    public synchronized List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>();
        for (Transfer t : transfers) out.add(snap(t));
        return out;
    }

    public Transfer submitUpload(String host, int port, int sessionId, Path file) {
        Transfer t = newTransfer(true, file.getFileName().toString());
        UploadTask task = new UploadTask(host, port, sessionId, file,
                (d, tot) -> progress(t, d, tot), s -> message(t, s), t::isCancelRequested);
        submit(t, () -> {
            task.call();
            return "Uploaded - SHA-256 verified by the server";
        });
        return t;
    }

    public Transfer submitDownload(String host, int port, int sessionId, int fileId, String name, Path dest) {
        Transfer t = newTransfer(false, name);
        DownloadTask task = new DownloadTask(host, port, sessionId, fileId, dest,
                (d, tot) -> progress(t, d, tot), t::isCancelRequested);
        submit(t, () -> {
            if (!task.call()) throw new java.io.IOException("Corrupted: SHA-256 mismatch, file deleted");
            return "Downloaded - SHA-256 verified";
        });
        return t;
    }

    public void cancel(Transfer t) {
        Snapshot s;
        synchronized (this) {
            if (t.isFinished()) return;
            t.cancelRequested = true;
            if (t.future != null) t.future.cancel(false);
            if (t.state == State.QUEUED) { // never started: finish it right here
                t.state = State.CANCELLED;
                t.message = "Cancelled";
            }
            s = snap(t);
        }
        listener.accept(s);
    }

    // ------------------------------------------------------------------ internals

    private synchronized Transfer newTransfer(boolean upload, String name) {
        Transfer t = new Transfer(nextId++, upload, name);
        transfers.add(t);
        return t;
    }

    private void submit(Transfer t, Callable<String> job) {
        Snapshot first;
        synchronized (this) {
            t.future = pool.submit(() -> run(t, job));
            first = snap(t);
        }
        listener.accept(first);
    }

    private Void run(Transfer t, Callable<String> job) {
        synchronized (this) {
            if (t.cancelRequested) return null; // cancelled while queued
            t.state = State.RUNNING;
            t.message = "Starting...";
        }
        notifyNow(t);
        String result;
        State end;
        try {
            result = job.call();
            end = State.SUCCEEDED;
        } catch (Exception e) {
            boolean wasCancelled;
            synchronized (this) { wasCancelled = t.cancelRequested; }
            end = wasCancelled ? State.CANCELLED : State.FAILED;
            result = wasCancelled ? "Cancelled" : (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        synchronized (this) {
            t.state = end;
            t.message = result;
            if (end == State.SUCCEEDED) t.done = t.total;
        }
        notifyNow(t);
        return null;
    }

    private void progress(Transfer t, long done, long total) {
        boolean notify;
        synchronized (this) {
            t.done = done;
            t.total = total;
            long now = System.nanoTime();
            notify = done >= total || now - t.lastNotifyNanos > 100_000_000L; // at most ~10 updates/s
            if (notify) t.lastNotifyNanos = now;
        }
        if (notify) notifyNow(t);
    }

    private void message(Transfer t, String text) {
        synchronized (this) { t.message = text; }
        notifyNow(t);
    }

    private void notifyNow(Transfer t) {
        Snapshot s;
        Consumer<Snapshot> l;
        synchronized (this) {
            s = snap(t);
            l = listener;
        }
        l.accept(s);
    }

    private Snapshot snap(Transfer t) {
        return new Snapshot(t, t.state, t.done, t.total, t.message);
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
