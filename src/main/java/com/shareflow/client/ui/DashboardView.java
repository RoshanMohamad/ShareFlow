package com.shareflow.client.ui;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.shareflow.client.ClientConnection;
import com.shareflow.client.DownloadTask;
import com.shareflow.client.UploadTask;
import com.shareflow.common.FileEntry;
import com.shareflow.common.Formats;
import com.shareflow.common.UserInfo;

/**
 * Main screen: shared files (with search), online users, transfers with progress bars,
 * history, activity log and live notifications. All network events arrive on a
 * background thread and are moved to the JavaFX thread with {@code Platform.runLater}.
 * Files can also be uploaded by dragging them onto the window.
 *
 * @author Member 5
 */
public final class DashboardView {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Stage stage;
    private final Consumer<String> onLeave;
    private volatile ClientConnection connection;

    private final ObservableList<FileEntry> files = FXCollections.observableArrayList();
    private final ObservableList<UserInfo> users = FXCollections.observableArrayList();
    private final ObservableList<String> history = FXCollections.observableArrayList();
    private final ObservableList<String> activity = FXCollections.observableArrayList();
    private final VBox transfers = new VBox(4);
    private final Label emptyTransfers = new Label("No transfers yet. Upload a file or drop one here.");
    private final Label toast = new Label();
    private final PauseTransition toastTimer = new PauseTransition(Duration.seconds(5));
    private final Label headerSub = new Label("Connecting...");
    private final Label usersChip = new Label("0 online");
    private final BorderPane content = new BorderPane();
    private final StackPane root = new StackPane(content, toastHolder());

    public DashboardView(Stage stage, Consumer<String> onLeave) {
        this.stage = stage;
        this.onLeave = onLeave;
        build();
    }

    public Parent root() { return root; }

    public void attach(ClientConnection conn) {
        this.connection = conn;
        Platform.runLater(() -> {
            headerSub.setText("Signed in as " + conn.username() + "  •  " + conn.host() + ":" + conn.port());
            users.setAll(List.copyOf(users)); // refresh cells so "(you)" shows
        });
    }

    public void shutdown() {
        ClientConnection c = connection;
        if (c != null) c.close();
    }

    /** Server events -> UI updates. */
    public ClientConnection.Listener listener() {
        return new ClientConnection.Listener() {
            @Override public void onUserList(List<UserInfo> list) {
                Platform.runLater(() -> users.setAll(list));
            }
            @Override public void onUserJoined(UserInfo u) {
                Platform.runLater(() -> {
                    users.add(u);
                    announce("👤 " + u.username() + " joined");
                });
            }
            @Override public void onUserLeft(String name) {
                Platform.runLater(() -> {
                    users.removeIf(u -> u.username().equals(name));
                    announce("👋 " + name + " left");
                });
            }
            @Override public void onFileList(List<FileEntry> list) {
                Platform.runLater(() -> files.setAll(list));
            }
            @Override public void onFileAdded(FileEntry f) {
                Platform.runLater(() -> {
                    if (files.stream().noneMatch(x -> x.id() == f.id())) files.add(f);
                    announce("📄 " + f.uploader() + " shared \"" + f.name() + "\" (" + Formats.size(f.size()) + ")");
                });
            }
            @Override public void onError(String message) {
                Platform.runLater(() -> announce("⚠ Server: " + message));
            }
            @Override public void onDisconnected(String reason) {
                Platform.runLater(() -> onLeave.accept("Disconnected from server: " + reason));
            }
        };
    }

    // ------------------------------------------------------------------ layout

    private Node toastHolder() {
        toast.getStyleClass().add("toast");
        toast.setVisible(false);
        toast.setMaxWidth(380);
        toast.setWrapText(true);
        toastTimer.setOnFinished(e -> toast.setVisible(false));
        StackPane holder = new StackPane(toast);
        holder.setPickOnBounds(false);
        StackPane.setAlignment(toast, Pos.TOP_CENTER);
        toast.setTranslateY(16);
        toast.setTranslateX(-90);
        return holder;
    }

    private void build() {
        // app bar
        Label title = new Label("ShareFlow");
        title.getStyleClass().add("app-title");
        headerSub.getStyleClass().add("app-sub");
        usersChip.getStyleClass().add("chip");
        usersChip.textProperty().bind(Bindings.size(users).asString().concat(" online"));
        Button upload = new Button("⬆  Upload file");
        upload.getStyleClass().addAll("button", "primary");
        upload.setOnAction(e -> chooseAndUpload());
        Button disconnect = new Button("Disconnect");
        disconnect.getStyleClass().addAll("button", "ghost");
        disconnect.setOnAction(e -> {
            shutdown();
            onLeave.accept(null);
        });
        HBox bar = new HBox(14, new VBox(1, title, headerSub), grow(), usersChip, upload, disconnect);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("app-bar");
        content.setTop(bar);

        // files card
        TextField search = new TextField();
        search.setPromptText("🔍  Search by name or uploader...");
        FilteredList<FileEntry> filtered = new FilteredList<>(files, f -> true);
        search.textProperty().addListener((o, a, q) -> {
            String s = q.toLowerCase(Locale.ROOT).trim();
            filtered.setPredicate(f -> s.isEmpty() || f.name().toLowerCase(Locale.ROOT).contains(s)
                    || f.uploader().toLowerCase(Locale.ROOT).contains(s));
        });
        TableView<FileEntry> table = fileTable(filtered);
        VBox.setVgrow(table, Priority.ALWAYS);
        Label filesCount = new Label();
        filesCount.getStyleClass().add("muted");
        filesCount.textProperty().bind(Bindings.size(filtered).asString().concat(" file(s)"));
        HBox filesHeader = new HBox(8, cardTitle("Shared files"), grow(), filesCount);
        filesHeader.setAlignment(Pos.CENTER_LEFT);
        VBox filesCard = card(filesHeader, search, table);

        // users card
        ListView<UserInfo> userList = new ListView<>(users);
        userList.setPlaceholder(new Label("Nobody else is here yet"));
        userList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(UserInfo u, boolean empty) {
                super.updateItem(u, empty);
                if (empty || u == null) {
                    setGraphic(null);
                    return;
                }
                ClientConnection c = connection;
                boolean me = c != null && u.username().equals(c.username());
                Label avatar = new Label(u.username().substring(0, 1).toUpperCase(Locale.ROOT));
                avatar.getStyleClass().add("avatar");
                if (me) avatar.getStyleClass().add("self");
                Label name = new Label(u.username() + (me ? "  (you)" : ""));
                name.setStyle("-fx-font-weight: bold;");
                Label ip = new Label(u.ip());
                ip.getStyleClass().add("muted");
                HBox cell = new HBox(10, avatar, new VBox(0, name, ip));
                cell.setAlignment(Pos.CENTER_LEFT);
                setGraphic(cell);
            }
        });
        VBox.setVgrow(userList, Priority.ALWAYS);
        VBox usersCard = card(cardTitle("Online users"), userList);
        usersCard.setPrefWidth(250);
        usersCard.setMinWidth(220);

        HBox middle = new HBox(12, filesCard, usersCard);
        HBox.setHgrow(filesCard, Priority.ALWAYS);
        middle.setPadding(new Insets(12, 12, 6, 12));
        content.setCenter(middle);

        // bottom: transfers / history / activity
        ScrollPane sp = new ScrollPane(new VBox(emptyTransfers, transfers));
        emptyTransfers.getStyleClass().add("muted");
        emptyTransfers.setPadding(new Insets(10));
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");
        ListView<String> historyList = new ListView<>(history);
        historyList.setPlaceholder(new Label("No finished transfers yet"));
        ListView<String> activityList = new ListView<>(activity);
        activityList.setPlaceholder(new Label("No activity yet"));
        TabPane tabs = new TabPane(tab("⇅  Transfers", sp), tab("🕒  History", historyList),
                tab("📜  Activity log", activityList));
        VBox bottom = card(tabs);
        bottom.setPrefHeight(210);
        bottom.setPadding(new Insets(6, 14, 10, 14));
        BorderPane.setMargin(bottom, new Insets(6, 12, 12, 12));
        content.setBottom(bottom);

        // drag & drop upload
        content.setOnDragOver((DragEvent e) -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!middle.getStyleClass().contains("drop-active")) middle.getStyleClass().add("drop-active");
            }
            e.consume();
        });
        content.setOnDragExited(e -> middle.getStyleClass().remove("drop-active"));
        content.setOnDragDropped(e -> {
            middle.getStyleClass().remove("drop-active");
            boolean ok = e.getDragboard().hasFiles();
            if (ok) e.getDragboard().getFiles().stream().filter(File::isFile).forEach(this::upload);
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    private TableView<FileEntry> fileTable(FilteredList<FileEntry> items) {
        TableView<FileEntry> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label empty = new Label("No files shared yet.\nUpload one or drag it into this window.");
        empty.getStyleClass().add("muted");
        empty.setStyle("-fx-text-alignment: center;");
        table.setPlaceholder(empty);

        TableColumn<FileEntry, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(icon(c.getValue().name()) + "  " + c.getValue().name()));
        name.setPrefWidth(260);
        TableColumn<FileEntry, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.size(c.getValue().size())));
        size.setPrefWidth(90);
        TableColumn<FileEntry, String> by = new TableColumn<>("Uploader");
        by.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().uploader()));
        by.setPrefWidth(110);
        TableColumn<FileEntry, String> at = new TableColumn<>("Shared at");
        at.setCellValueFactory(c -> new ReadOnlyStringWrapper(TIME.format(Instant.ofEpochMilli(c.getValue().uploadedAt()))));
        at.setPrefWidth(90);
        TableColumn<FileEntry, Void> action = new TableColumn<>("");
        action.setCellFactory(col -> new TableCell<>() {
            private final Button b = new Button("⬇  Download");
            {
                b.getStyleClass().addAll("button", "small", "dl");
                b.setOnAction(e -> download(getTableView().getItems().get(getIndex())));
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : b);
            }
        });
        action.setSortable(false);
        action.setMinWidth(130);
        action.setPrefWidth(130);
        table.getColumns().addAll(List.of(name, size, by, at, action));
        return table;
    }

    private static String icon(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(png|jpe?g|gif|bmp|svg|webp)$")) return "🖼";
        if (n.matches(".*\\.(zip|rar|7z|tar|gz)$")) return "📦";
        if (n.matches(".*\\.(mp4|mkv|avi|mov|webm)$")) return "🎬";
        if (n.matches(".*\\.(mp3|wav|flac|ogg)$")) return "🎵";
        if (n.matches(".*\\.(java|py|js|ts|c|cpp|h|cs|html|css|json|xml|sh)$")) return "💻";
        if (n.matches(".*\\.(pdf|docx?|pptx?|xlsx?|txt|md)$")) return "📄";
        return "📎";
    }

    // ------------------------------------------------------------------ actions

    private void chooseAndUpload() {
        File f = new FileChooser().showOpenDialog(stage);
        if (f != null) upload(f);
    }

    private void upload(File f) {
        ClientConnection c = connection;
        if (c == null) return;
        addTransfer("⬆  " + f.getName(), new UploadTask(c.host(), c.port(), c.sessionId(), f.toPath()));
    }

    private void download(FileEntry entry) {
        ClientConnection c = connection;
        if (c == null) return;
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(entry.name());
        File dest = fc.showSaveDialog(stage);
        if (dest == null) return;
        addTransfer("⬇  " + entry.name(),
                new DownloadTask(c.host(), c.port(), c.sessionId(), entry.id(), dest.toPath()));
    }

    private void addTransfer(String title, Task<?> task) {
        Label name = new Label(title);
        name.setPrefWidth(230);
        name.setStyle("-fx-font-weight: bold;");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.progressProperty().bind(task.progressProperty());
        HBox.setHgrow(bar, Priority.ALWAYS);
        Label pct = new Label("0%");
        pct.setPrefWidth(42);
        pct.textProperty().bind(Bindings.createStringBinding(
                () -> task.getProgress() < 0 ? "" : Math.round(task.getProgress() * 100) + "%", task.progressProperty()));
        Label status = new Label();
        status.getStyleClass().add("muted");
        status.setPrefWidth(180);
        status.textProperty().bind(task.messageProperty());
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().addAll("button", "small");
        cancel.setOnAction(e -> task.cancel());
        HBox row = new HBox(12, name, bar, pct, status, cancel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("transfer-row");
        transfers.getChildren().add(0, row);
        emptyTransfers.setVisible(false);
        emptyTransfers.setManaged(false);

        task.setOnSucceeded(e -> {
            status.textProperty().unbind();
            status.setText("Done at " + TIME.format(Instant.now()));
            bar.getStyleClass().add("done");
            cancel.setDisable(true);
            history.add(0, TIME.format(Instant.now()) + "  " + title + "  - completed");
            announce("✅ " + title.substring(3) + " finished");
        });
        task.setOnFailed(e -> {
            String msg = task.getException().getMessage();
            status.textProperty().unbind();
            bar.progressProperty().unbind();
            bar.setProgress(0.0001);
            bar.getStyleClass().add("failed");
            pct.textProperty().unbind();
            pct.setText("");
            status.setText("Failed: " + msg);
            cancel.setDisable(true);
            history.add(0, TIME.format(Instant.now()) + "  " + title + "  - failed: " + msg);
            announce("❌ " + title.substring(3) + " failed: " + msg);
        });
        task.setOnCancelled(e -> {
            status.textProperty().unbind();
            status.setText("Cancelled");
            cancel.setDisable(true);
            history.add(0, TIME.format(Instant.now()) + "  " + title + "  - cancelled");
        });
        Thread t = new Thread(task, "shareflow-transfer");
        t.setDaemon(true);
        t.start();
    }

    /** Shows a toast and records the event in the activity log. */
    private void announce(String msg) {
        activity.add(0, TIME.format(Instant.now()) + "  " + msg);
        toast.setText(msg);
        toast.setVisible(true);
        toastTimer.playFromStart();
    }

    // ------------------------------------------------------------------ helpers

    private static VBox card(Node... children) {
        VBox box = new VBox(10, children);
        box.getStyleClass().add("card");
        return box;
    }

    private static Label cardTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("card-title");
        return l;
    }

    private static Tab tab(String title, Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static HBox grow() {
        HBox s = new HBox();
        HBox.setHgrow(s, Priority.ALWAYS);
        return s;
    }
}
