package com.qft.demandnotice;

import javafx.application.Application;

/**
 * Entry point for the shaded (fat) JAR.
 *
 * <p>The launcher deliberately does NOT extend {@link Application}; pointing the
 * JAR manifest at this class avoids the "JavaFX runtime components are missing"
 * error that occurs when the main class itself extends Application inside a
 * shaded JAR.</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}
