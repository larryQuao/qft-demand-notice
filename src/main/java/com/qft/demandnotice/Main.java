package com.qft.demandnotice;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
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
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
