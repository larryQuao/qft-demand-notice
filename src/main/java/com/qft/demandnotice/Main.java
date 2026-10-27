package com.qft.demandnotice;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/** JavaFX entry point: loads the form and shows the primary stage. */
public class Main extends Application {

    static final String APP_TITLE = "QFT Demand Notice Generator";

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                Objects.requireNonNull(getClass().getResource("ui/MainView.fxml")));
        Scene scene = new Scene(loader.load());
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());

        stage.setTitle(APP_TITLE);
        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(720);
        fitToWorkArea(stage);
        stage.show();
    }

    /**
     * Sizes the window to the desktop work area (the screen minus the taskbar)
     * so the whole application is always visible on first open.
     */
    private static void fitToWorkArea(Stage stage) {
        Rectangle2D work = Screen.getPrimary().getVisualBounds();
        stage.setX(work.getMinX());
        stage.setY(work.getMinY());
        stage.setWidth(work.getWidth());
        stage.setHeight(work.getHeight());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
