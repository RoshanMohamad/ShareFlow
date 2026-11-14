package com.shareflow.client.ui;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.shareflow.client.ClientConnection;
import com.shareflow.client.DiscoveryClient;
import com.shareflow.client.DiscoveryClient.ServerInfo;
import com.shareflow.common.ProtocolConstants;

/**
 * Connect screen: discovered servers, manual IP/port, username.
 *
 * @author Member 5
 */
public final class ConnectView {

    /** Opens the control connection (called on a background thread). */
    @FunctionalInterface
    public interface Connector {
        ClientConnection connect(String host, int port, String username) throws IOException;
    }

    private final VBox page = new VBox();
    private final ListView<ServerInfo> servers = new ListView<>();
    private final TextField host = new TextField();
    private final TextField port = new TextField(String.valueOf(ProtocolConstants.DEFAULT_TCP_PORT));
    private final TextField username = new TextField(System.getProperty("user.name", ""));
    private final Button scan = new Button("↻  Scan");
    private final Button connectBtn = new Button("Connect");
    private final Label status = new Label();

    public ConnectView(Connector connector, Consumer<ClientConnection> onConnected) {
        // branded header
        Label title = new Label("ShareFlow");
        title.getStyleClass().add("app-title");
        title.setStyle("-fx-font-size: 28px;");
        Label sub = new Label("Share files across your local network - no internet needed");
        sub.getStyleClass().add("app-sub");
        VBox bar = new VBox(2, title, sub);
        bar.getStyleClass().add("app-bar");
        bar.setPadding(new Insets(22, 28, 22, 28));

        // discovered servers
        Label found = new Label("Servers on this network");
        found.getStyleClass().add("card-title");
        HBox foundHeader = new HBox(found, grow(), scan);
        foundHeader.setAlignment(Pos.CENTER_LEFT);
        scan.getStyleClass().addAll("button", "small");
        scan.setOnAction(e -> scan());

        servers.setPlaceholder(new Label("No servers found yet"));
        servers.setPrefHeight(130);
        servers.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(ServerInfo s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : "🖥  " + s.name() + "   -   " + s.host() + ":" + s.port());
            }
        });
        servers.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> {
            if (s != null) {
                host.setText(s.host());
                port.setText(String.valueOf(s.port()));
            }
        });

        // manual form
        Label manual = new Label("Connection details");
        manual.getStyleClass().add("card-title");
        host.setPromptText("e.g. 192.168.1.20");
        username.setPromptText("Your name");
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.add(label("Server IP"), 0, 0);
        form.add(host, 1, 0);
        form.add(label("Port"), 0, 1);
        form.add(port, 1, 1);
        form.add(label("Username"), 0, 2);
        form.add(username, 1, 2);
        GridPane.setHgrow(host, Priority.ALWAYS);
        GridPane.setHgrow(port, Priority.ALWAYS);
        GridPane.setHgrow(username, Priority.ALWAYS);

        connectBtn.getStyleClass().addAll("button", "primary");
        connectBtn.setDefaultButton(true);
        connectBtn.setMaxWidth(Double.MAX_VALUE);
        connectBtn.setPadding(new Insets(10));
        connectBtn.setOnAction(e -> connect(connector, onConnected));
        status.setWrapText(true);
        status.getStyleClass().add("muted");

        VBox card = new VBox(12, foundHeader, servers, manual, form, connectBtn, status);
        card.getStyleClass().add("card");
        card.setMaxWidth(500);
        card.setPadding(new Insets(20));

        StackPane body = new StackPane(card);
        body.setPadding(new Insets(24));
        VBox.setVgrow(body, Priority.ALWAYS);
        page.getChildren().addAll(bar, body);

        scan();
    }

    public Parent root() { return page; }

    private void scan() {
        scan.setDisable(true);
        setStatus("Scanning the network...", false);
        Task<List<ServerInfo>> task = new Task<>() {
            @Override protected List<ServerInfo> call() throws Exception {
                return DiscoveryClient.discover();
            }
        };
        task.setOnSucceeded(e -> {
            servers.getItems().setAll(task.getValue());
            if (!task.getValue().isEmpty()) servers.getSelectionModel().select(0);
            setStatus(task.getValue().isEmpty()
                    ? "No server found. Enter its IP and port manually."
                    : "Found " + task.getValue().size() + " server(s). Pick one and press Connect.", false);
            scan.setDisable(false);
        });
        task.setOnFailed(e -> {
            setStatus("Scan failed: " + task.getException().getMessage(), true);
            scan.setDisable(false);
        });
        daemon(task);
    }

    private void connect(Connector connector, Consumer<ClientConnection> onConnected) {
        String h = host.getText().trim();
        String user = username.getText().trim();
        int p;
        try {
            p = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException ex) {
            setStatus("Port must be a number", true);
            return;
        }
        if (h.isEmpty() || user.isEmpty()) {
            setStatus("Enter the server IP and a username", true);
            return;
        }
        connectBtn.setDisable(true);
        connectBtn.setText("Connecting...");
        setStatus("Connecting to " + h + ":" + p + "...", false);
        Task<ClientConnection> task = new Task<>() {
            @Override protected ClientConnection call() throws Exception {
                return connector.connect(h, p, user);
            }
        };
        task.setOnSucceeded(e -> onConnected.accept(task.getValue()));
        task.setOnFailed(e -> {
            connectBtn.setDisable(false);
            connectBtn.setText("Connect");
            setStatus("Could not connect: " + task.getException().getMessage(), true);
        });
        daemon(task);
    }

    private void setStatus(String msg, boolean error) {
        status.getStyleClass().removeAll("muted", "error-text");
        status.getStyleClass().add(error ? "error-text" : "muted");
        status.setText(msg);
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    private static HBox grow() {
        HBox s = new HBox();
        HBox.setHgrow(s, Priority.ALWAYS);
        return s;
    }

    private static void daemon(Task<?> t) {
        Thread th = new Thread(t, "shareflow-ui-task");
        th.setDaemon(true);
        th.start();
    }
}
