package com.anvileditor.ui;

import com.anvileditor.leveldb.LevelDbException;
import com.anvileditor.nbt.NbtFormatException;
import com.anvileditor.world.BedrockWorld;
import com.anvileditor.world.ChunkPos;
import com.anvileditor.world.RecordContent;
import com.anvileditor.world.WorldRecord;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Connects the window's parts to an opened world: the Open/Save/Backup
 * buttons, the world tree and chunk map, and the NBT editor.
 *
 * Slow work (opening, saving, backing up) runs on a background thread
 * while the window is disabled, with progress shown in the status bar.
 */
public class EditorController {

    private static final String APP_TITLE = "Anvil Editor";

    private final Stage stage;
    private final Node content;
    private final EditorOptions options;
    private final ChunkViewer chunkViewer;
    private final NbtTreeView nbtView;
    private final StatusBar statusBar;

    private BedrockWorld world;

    public EditorController(Stage stage, Node content, EditorOptions options, ChunkViewer chunkViewer,
                            NbtTreeView nbtView, StatusBar statusBar) {
        this.stage = stage;
        this.content = content;
        this.options = options;
        this.chunkViewer = chunkViewer;
        this.nbtView = nbtView;
        this.statusBar = statusBar;

        options.setOnOpen(this::openWorld);
        options.setOnSave(this::save);
        options.setOnBackup(this::backup);
        chunkViewer.setOnRecordSelected(this::showRecord);
        chunkViewer.setOnChunkSelected(this::chunkSelected);
        nbtView.setOnEdited(this::recordEdited);

        stage.setOnCloseRequest(event -> {
            if (!confirmDiscardChanges("closing")) {
                event.consume();
            }
        });

        statusBar.setMessage("Open a Bedrock world to begin.");
        updateTitle();
    }

    // ------------------------------------------------------------------
    // Open

    private void openWorld() {
        if (!confirmDiscardChanges("opening another world")) {
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a Bedrock world folder (it contains level.dat and a db folder)");
        File start = defaultWorldsFolder();
        if (world != null) {
            start = world.getFolder().getParent().toFile();
        }
        if (start != null && start.isDirectory()) {
            chooser.setInitialDirectory(start);
        }

        File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            return;
        }

        runInBackground("Opening world...", progress -> BedrockWorld.open(chosen.toPath(), progress::accept),
                opened -> {
                    world = opened;
                    chunkViewer.setWorld(opened);
                    nbtView.clear("Select a chunk on the map, or level.dat / a record in the world tree.");
                    options.setBackupEnabled(true);
                    updateTitle();

                    String message = "Opened " + opened.getName() + ": " + opened.getChunkCount() + " chunks.";
                    if (opened.isInUse()) {
                        message += " Minecraft has this world open; close it there before saving.";
                        showWarning("World is in use",
                                "Minecraft currently has this world open. You can browse it, "
                                        + "but saving is blocked until you close the world in Minecraft.");
                    }
                    statusBar.setMessage(message);
                });
    }

    /** The usual minecraftWorlds folder for Bedrock on Windows, if present */
    private static File defaultWorldsFolder() {
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path users = Path.of(appData, "Minecraft Bedrock", "Users");
            if (Files.isDirectory(users)) {
                try (Stream<Path> list = Files.list(users)) {
                    for (Path user : list.toList()) {
                        Path worlds = user.resolve("games/com.mojang/minecraftWorlds");
                        if (!user.getFileName().toString().equals("Shared") && Files.isDirectory(worlds)) {
                            return worlds.toFile();
                        }
                    }
                } catch (IOException e) {
                    // Fall through to the other locations
                }
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path uwp = Path.of(localAppData, "Packages", "Microsoft.MinecraftUWP_8wekyb3d8bbwe",
                    "LocalState", "games", "com.mojang", "minecraftWorlds");
            if (Files.isDirectory(uwp)) {
                return uwp.toFile();
            }
        }
        return new File(System.getProperty("user.home"));
    }

    // ------------------------------------------------------------------
    // Browse and edit

    private void showRecord(WorldRecord record) {
        if (world == null) {
            return;
        }
        try {
            nbtView.showContent(world.load(record));
        } catch (IOException | LevelDbException | NbtFormatException e) {
            nbtView.clear("Could not read " + record.label() + ": " + e.getMessage());
        }
    }

    private void chunkSelected(ChunkPos pos) {
        nbtView.clear("Chunk " + pos.label() + " selected. Pick one of its records in the world tree.");
    }

    private void recordEdited(RecordContent edited) {
        world.recordEdited(edited);
        options.setSaveEnabled(true);
        updateTitle();
        statusBar.setMessage(world.getUnsavedChangeCount() + " unsaved change(s). Press Save to write them to the world.");
    }

    // ------------------------------------------------------------------
    // Save and backup

    private void save() {
        if (world == null || !world.hasUnsavedChanges()) {
            return;
        }
        if (world.isInUse()) {
            showWarning("World is in use", "Minecraft has this world open. Close the world in Minecraft, "
                    + "then press Save again. Your changes are still here.");
            return;
        }

        int count = world.getUnsavedChangeCount();
        runInBackground("Saving...", progress -> {
            world.save();
            return count;
        }, saved -> {
            options.setSaveEnabled(false);
            updateTitle();
            statusBar.setMessage("Saved " + saved + " change(s). Minecraft applies them the next time the world loads.");
        });
    }

    private void backup() {
        if (world == null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save a backup of the world");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Minecraft world (*.mcworld)", "*.mcworld"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"));
        chooser.setInitialFileName(safeFileName(world.getName()) + " backup " + stamp + ".mcworld");
        File parent = world.getFolder().getParent().toFile();
        if (parent.isDirectory()) {
            chooser.setInitialDirectory(parent);
        }

        File target = chooser.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        if (target.toPath().toAbsolutePath().startsWith(world.getFolder())) {
            showWarning("Choose another folder", "The backup cannot be saved inside the world folder itself.");
            return;
        }

        String note = world.hasUnsavedChanges() ? " (unsaved changes are not included)" : "";
        runInBackground("Backing up...", progress -> {
            world.backup(target.toPath());
            return target;
        }, file -> statusBar.setMessage("Backup saved to " + file + note));
    }

    private static String safeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "world" : cleaned;
    }

    // ------------------------------------------------------------------
    // Helpers

    private void updateTitle() {
        if (world == null) {
            stage.setTitle(APP_TITLE);
            return;
        }
        stage.setTitle((world.hasUnsavedChanges() ? "* " : "") + world.getName() + " - " + APP_TITLE);
    }

    /** True if there is nothing unsaved, or the user agrees to discard it */
    private boolean confirmDiscardChanges(String action) {
        if (world == null || !world.hasUnsavedChanges()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "You have " + world.getUnsavedChangeCount() + " unsaved change(s). Discard them before "
                        + action + "?", ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Unsaved changes");
        alert.initOwner(stage);
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void showWarning(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setHeaderText(header);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    /** Work that reports progress as (message, fraction) */
    @FunctionalInterface
    private interface Work<T> {
        T run(ProgressSink progress) throws Exception;
    }

    @FunctionalInterface
    private interface ProgressSink {
        void accept(String message, double fraction);
    }

    private <T> void runInBackground(String startMessage, Work<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.run((message, fraction) -> {
                    updateMessage(message);
                    updateProgress(fraction, 1);
                });
            }
        };

        content.setDisable(true);
        statusBar.bind(task, startMessage);

        task.setOnSucceeded(e -> {
            statusBar.unbind();
            content.setDisable(false);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            statusBar.unbind();
            content.setDisable(false);
            Throwable error = task.getException();
            statusBar.setMessage("Failed: " + error.getMessage());

            Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(error.getMessage()), ButtonType.OK);
            alert.setHeaderText(startMessage.replace("...", "") + " failed");
            alert.initOwner(stage);
            alert.showAndWait();
        });

        Thread thread = new Thread(task, "anvil-editor-worker");
        thread.setDaemon(true);
        thread.start();
    }
}
