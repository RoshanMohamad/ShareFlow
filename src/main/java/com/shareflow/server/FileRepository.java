package com.shareflow.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.shareflow.common.FileEntry;
import com.shareflow.common.Hashing;

/**
 * The shared/ folder and its index. Handles name sanitising, duplicate names and the
 * {@code .part} lifecycle. Used only from the event-loop thread.
 *
 * @author Member 3
 */
public final class FileRepository {

    /** A name reserved for an in-progress upload. */
    public record Reservation(String finalName, Path partPath, Path finalPath) {}

    private final Path root;
    private final Map<Integer, FileEntry> entries = new LinkedHashMap<>();
    private int nextId = 1;

    public FileRepository(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        scan();
    }

    private void scan() throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            List<Path> files = new ArrayList<>(s.filter(Files::isRegularFile).toList());
            files.sort(null);
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (name.endsWith(".part")) {
                    Files.deleteIfExists(p); // leftovers of a crashed upload
                    continue;
                }
                FileEntry e = new FileEntry(nextId++, Files.size(p), name, "server",
                        Files.getLastModifiedTime(p).toMillis(), Hashing.sha256(p));
                entries.put(e.id(), e);
            }
        }
    }

    public Path root() { return root; }

    public List<FileEntry> list() { return new ArrayList<>(entries.values()); }

    public FileEntry find(int id) { return entries.get(id); }

    public Path pathOf(FileEntry e) { return root.resolve(e.name()); }

    /** Keeps only the base name and removes anything dangerous. */
    public static String sanitize(String raw) {
        String s = raw == null ? "" : raw.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replace("..", "");
        s = s.replaceAll("[\\p{Cntrl}<>:\"|?*]", "");
        s = s.strip();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).strip();
        if (s.length() > 200) s = s.substring(0, 200);
        if (s.isEmpty() || s.equalsIgnoreCase("unnamed.part")) s = "unnamed";
        if (s.endsWith(".part")) s = s + "_";
        return s;
    }

    /** Picks a free name ({@code notes (1).pdf}) and creates the empty .part file. */
    public Reservation reserve(String requestedName) throws IOException {
        String name = sanitize(requestedName);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        String candidate = name;
        for (int n = 1; isTaken(candidate); n++) {
            candidate = base + " (" + n + ")" + ext;
        }
        Path part = root.resolve(candidate + ".part");
        Files.createFile(part);
        return new Reservation(candidate, part, root.resolve(candidate));
    }

    private boolean isTaken(String name) {
        return Files.exists(root.resolve(name)) || Files.exists(root.resolve(name + ".part"));
    }

    /** Renames .part to the final name and indexes the file. */
    public FileEntry commit(Reservation r, String uploader, byte[] sha256) throws IOException {
        try {
            Files.move(r.partPath(), r.finalPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(r.partPath(), r.finalPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        FileEntry e = new FileEntry(nextId++, Files.size(r.finalPath()), r.finalName(), uploader,
                System.currentTimeMillis(), sha256);
        entries.put(e.id(), e);
        return e;
    }

    /** Removes the .part file of an aborted upload. */
    public void discard(Reservation r) {
        try { Files.deleteIfExists(r.partPath()); } catch (IOException ignored) { }
    }
}
