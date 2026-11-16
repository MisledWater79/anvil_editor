package com.anvileditor;

// Plain entry point for running from an IDE without JavaFX module flags.
// The JVM refuses to start a main class that extends javafx.application.Application
// when JavaFX is on the classpath instead of the module path; delegating from a
// class that doesn't extend Application avoids that check.
public class Launcher {
    public static void main(String[] args) {
        AnvilEditorApp.main(args);
    }
}
