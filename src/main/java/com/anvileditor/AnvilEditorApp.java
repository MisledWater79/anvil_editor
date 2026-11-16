package com.anvileditor;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.scene.image.Image;

import java.io.InputStream;

import com.anvileditor.ui.ChunkViewer;
import com.anvileditor.ui.EditorController;
import com.anvileditor.ui.EditorOptions;
import com.anvileditor.ui.NbtTreeView;
import com.anvileditor.ui.StatusBar;

// Main entry point for the Anvil Editor JavaFX application
public class AnvilEditorApp extends Application {

    // Initializes and displays the application's main window
    @Override
    public void start(Stage stage) {
        // Load the app icon from the resources folder
        var iconStream = getClass().getResourceAsStream("/com/anvileditor/ui/anvileditoricon.png");

        // Set the app window icon if the image exists
        if (iconStream != null) {
            stage.getIcons().add(new Image(iconStream));
        }

        // Load the Minecraft font from resources
        try (InputStream fontStream = getClass().getResourceAsStream("/fonts/MinecraftDefault-Regular.ttf")) {
            if (fontStream != null) {
                @SuppressWarnings("unused")
                Font minecraftFont = Font.loadFont(fontStream, 20);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        /* 
         * The world options are located on the top of the window;
         * the chunk viewer is on the upper "half" of the window,
         * and the NBT tree viewer is on the lower "half"
         */
        EditorOptions editorOptions = new EditorOptions();
        ChunkViewer chunkViewer = new ChunkViewer();
        NbtTreeView nbtTreeView = new NbtTreeView();
        StatusBar statusBar = new StatusBar();

        VBox mainLayout = new VBox(editorOptions, chunkViewer, nbtTreeView);
        VBox.setVgrow(nbtTreeView, Priority.ALWAYS);

        // The status bar stays usable while the main layout is disabled during background work
        VBox window = new VBox(mainLayout, statusBar);
        VBox.setVgrow(mainLayout, Priority.ALWAYS);

        // Connects the buttons, world tree, chunk map and NBT editor to an opened world
        new EditorController(stage, mainLayout, editorOptions, chunkViewer, nbtTreeView, statusBar);

        // The window size is 900x760 pixels
        Scene scene = new Scene(window, 900, 760);

        // Load the app's stylesheet
        var css = getClass().getResource("/styles.css");

        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        // Configure the window (the controller keeps the title up to date)
        stage.setScene(scene);

        // Load the window
        stage.show();
    }

    // Application entry point; launch() initializes JavaFX components and calls start()
    public static void main(String[] args) {
        launch(args);
    }
}
