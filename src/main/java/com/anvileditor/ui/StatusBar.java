package com.anvileditor.ui;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * One-line status area at the bottom of the window, with a progress bar
 * that appears while background work is running.
 */
public class StatusBar extends HBox {

    private final Label message = new Label();
    private final ProgressBar progress = new ProgressBar();

    public StatusBar() {
        super(10);
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 10, 4, 10));

        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        progress.setPrefWidth(160);
        progress.setVisible(false);

        getChildren().addAll(message, progress);
    }

    public void setMessage(String text) {
        message.setText(text);
    }

    /** Follows a task's message and progress until unbind() is called */
    public void bind(Task<?> task, String initialMessage) {
        message.textProperty().unbind();
        message.setText(initialMessage);
        message.textProperty().bind(javafx.beans.binding.Bindings.when(task.messageProperty().isEmpty())
                .then(initialMessage).otherwise(task.messageProperty()));
        progress.progressProperty().bind(task.progressProperty());
        progress.setVisible(true);
    }

    public void unbind() {
        message.textProperty().unbind();
        progress.progressProperty().unbind();
        progress.setVisible(false);
    }
}
