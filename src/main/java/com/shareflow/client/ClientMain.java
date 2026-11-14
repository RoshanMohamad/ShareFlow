package com.shareflow.client;

import java.io.IOException;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import com.shareflow.client.ui.ConnectView;
import com.shareflow.client.ui.DashboardView;

/**
 * JavaFX entry point: switches between the connect screen and the dashboard.
 *
 * @author Member 5
 */
public final class ClientMain extends Application {
    private Stage stage;
    private volatile DashboardView dashboard;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        stage.setTitle("ShareFlow");
        stage.setOnCloseRequest(e -> {
            if (dashboard != null) dashboard.shutdown();
            Platform.exit();
        });
        showConnect(null);
        stage.show();
    }

    private void showConnect(String notice) {
        dashboard = null;
        ConnectView view = new ConnectView(this::connect, this::showDashboard);
        stage.setScene(styled(new Scene(view.root(), 600, 640)));
        if (notice != null) {
            Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION, notice).show());
        }
    }

    /** Runs on a background thread. The dashboard exists before the first server event. */
    private ClientConnection connect(String host, int port, String user) throws IOException {
        DashboardView dash = new DashboardView(stage, this::showConnect);
        ClientConnection conn = ClientConnection.connect(host, port, user, dash.listener());
        dash.attach(conn);
        dashboard = dash;
        return conn;
    }

    private void showDashboard(ClientConnection conn) {
        stage.setScene(styled(new Scene(dashboard.root(), 1100, 720)));
    }

    private static Scene styled(Scene scene) {
        var css = ClientMain.class.getResource("/shareflow.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        return scene;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
