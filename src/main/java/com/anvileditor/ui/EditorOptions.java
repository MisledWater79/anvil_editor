package com.anvileditor.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;

/**
 * The options for what a user wants to do
 * with their world in Anvil Editor.
 * They have the option to open a world,
 * save it, or create a backup of it.
 */
public class EditorOptions extends BorderPane {

    private final Button openWorld = new Button("Open World");
    private final Button saveWorld = new Button("Save");
    private final Button backupWorld = new Button("Backup");

    /**
     * Open, save, and backup options
     */
    public EditorOptions() {
        // Apply the  styling to the options section
        getStyleClass().add("editor-options");

        // Grid to arrange the buttons
        GridPane buttonOptions = new GridPane();
        buttonOptions.setAlignment(Pos.CENTER);
        buttonOptions.setHgap(10);
        buttonOptions.setVgap(10);
        buttonOptions.setPadding(new Insets(10));

        // Apply the fonts to the buttons
        openWorld.getStyleClass().add("option-button");
        saveWorld.getStyleClass().add("option-button");
        backupWorld.getStyleClass().add("option-button");

        // Nothing to save or back up until a world is open
        saveWorld.setDisable(true);
        backupWorld.setDisable(true);

        // Add buttons to the grid
        buttonOptions.add(openWorld, 0, 0);
        buttonOptions.add(saveWorld, 1, 0);
        buttonOptions.add(backupWorld, 2, 0);

        // Use the grid as the main content of this component to center the buttons
        setCenter(buttonOptions);
    }

    public void setOnOpen(Runnable action) {
        openWorld.setOnAction(e -> action.run());
    }

    public void setOnSave(Runnable action) {
        saveWorld.setOnAction(e -> action.run());
    }

    public void setOnBackup(Runnable action) {
        backupWorld.setOnAction(e -> action.run());
    }

    /** Save is only useful when there are unsaved changes */
    public void setSaveEnabled(boolean enabled) {
        saveWorld.setDisable(!enabled);
    }

    public void setBackupEnabled(boolean enabled) {
        backupWorld.setDisable(!enabled);
    }
}
