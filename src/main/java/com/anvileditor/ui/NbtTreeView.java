package com.anvileditor.ui;

import com.anvileditor.nbt.NbtDocument;
import com.anvileditor.nbt.NbtEditor;
import com.anvileditor.nbt.NbtFormatException;
import com.anvileditor.nbt.NbtPath;
import com.anvileditor.nbt.NbtValues;
import com.anvileditor.nbt.Tag;
import com.anvileditor.nbt.TagType;
import com.anvileditor.world.RecordContent;

import java.util.Optional;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Displays hierarchical NBT data in the Anvil Editor interface.
 *
 * TreeItem manages the hierarchy, while NbtTreeNode stores
 * the information displayed for each tag.
 *
 * Editable records can be changed through the right-click menu (or by
 * double-clicking / pressing Enter on a value, and Delete to remove a tag).
 * Every change goes through NbtEditor, so invalid values are rejected
 * before anything is modified.
 */
public class NbtTreeView extends BorderPane {

    private final TreeView<NbtTreeNode> treeView;
    private final Label title = new Label("NBT Data");
    private final Label note = new Label();

    // What is currently shown, and where edits are reported
    private RecordContent content;
    private Consumer<RecordContent> onEdited = c -> { };

    /**
     * Lower "half" of the editor interface
     */
    public NbtTreeView() {
        // Apply the styling to the NBT tree view section
        getStyleClass().add("nbt-tree-view");

        // Set up the JavaFX tree, empty until a record is selected
        treeView = new TreeView<>(emptyRoot());
        treeView.setShowRoot(false);
        treeView.setCellFactory(tree -> new NbtCell());
        treeView.getStyleClass().add("nbt-tree");

        treeView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                editSelectedValue();
            }
        });
        treeView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                editSelectedValue();
            } else if (event.getCode() == KeyCode.DELETE) {
                deleteSelected();
            }
        });

        // Add the panel title and tree to the layout
        title.setStyle("-fx-font-family: \"Minecraft Default\"; -fx-font-size: 18px; -fx-font-weight: bold; -fx-underline: true;");
        note.setWrapText(true);
        note.setManaged(false);
        note.setVisible(false);

        VBox header = new VBox(2, title, note);
        header.setPadding(new Insets(8));

        setTop(header);
        setCenter(treeView);
        setTitle("NBT Data", "Open a world, then select a record to see its NBT data.");
    }

    public TreeView<NbtTreeNode> getTreeView() {
        return treeView;
    }

    /** Called with the record's content after every successful edit */
    public void setOnEdited(Consumer<RecordContent> onEdited) {
        this.onEdited = onEdited == null ? c -> { } : onEdited;
    }

    /**
     * Replaces the displayed tree with the contents of an NBT document
     * (for example one read from a LevelDB record or level.dat)
     */
    public void showDocument(NbtDocument document) {
        content = null;
        treeView.setRoot(NbtTreeBuilder.build(document));
        setTitle("NBT Data", null);
    }

    /** Shows a world record; edits are allowed if the content is editable */
    public void showContent(RecordContent content) {
        this.content = content;
        treeView.setRoot(NbtTreeBuilder.build(content.documents()));

        String where = content.record().chunk() == null ? "" : " (" + content.record().chunk() + ")";
        String hint = content.editable()
                ? "Right-click a tag to edit, add or delete. Changes are kept until you press Save."
                : null;
        String text = content.note() != null ? content.note() : hint;
        setTitle("NBT Data: " + content.record().label() + where, text);
    }

    /** Clears the view, optionally showing a message in place of the tree */
    public void clear(String message) {
        content = null;
        treeView.setRoot(emptyRoot());
        setTitle("NBT Data", message);
    }

    private void setTitle(String text, String noteText) {
        title.setText(text);
        boolean hasNote = noteText != null && !noteText.isEmpty();
        note.setText(hasNote ? noteText : "");
        note.setManaged(hasNote);
        note.setVisible(hasNote);
    }

    private static TreeItem<NbtTreeNode> emptyRoot() {
        return new TreeItem<>(new NbtTreeNode("NBT Data", "ROOT", null));
    }

    // ------------------------------------------------------------------
    // Editing

    private boolean isEditable() {
        return content != null && content.editable();
    }

    /** The document and path a tree item refers to, or null for the hidden root */
    private record Target(NbtDocument document, NbtPath path, Tag tag) {
    }

    private Target targetOf(TreeItem<NbtTreeNode> item) {
        if (content == null || item == null) {
            return null;
        }
        int index = NbtTreeBuilder.documentIndexOf(item);
        if (index < 0) {
            return null;
        }
        NbtDocument document = content.documents().get(index);
        NbtPath path = NbtTreeBuilder.pathOf(item);
        return new Target(document, path, new NbtEditor(document).get(path));
    }

    private void editSelectedValue() {
        TreeItem<NbtTreeNode> item = treeView.getSelectionModel().getSelectedItem();
        if (!isEditable() || item == null) {
            return;
        }
        Target target = targetOf(item);
        if (target == null || target.tag() instanceof Tag.CompoundTag || target.tag() instanceof Tag.ListTag) {
            return;
        }

        String current = NbtValues.toText(target.tag());
        TextInputDialog dialog = new TextInputDialog(current);
        dialog.setTitle("Edit Value");
        dialog.setHeaderText("Edit " + item.getValue().getName() + " (" + target.tag().getType() + ")");
        dialog.setContentText("Value:");
        if (target.tag().getType() == TagType.BYTE_ARRAY || target.tag().getType() == TagType.INT_ARRAY) {
            dialog.setContentText("Values (comma separated):");
        }
        dialog.getEditor().setPrefColumnCount(40);

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().equals(current)) {
            return;
        }

        applyEdit(() -> new NbtEditor(target.document()).setValueFromText(target.path(), result.get()));
        refreshItem(item);
    }

    private void addToSelected() {
        TreeItem<NbtTreeNode> item = treeView.getSelectionModel().getSelectedItem();
        Target target = targetOf(item);
        if (!isEditable() || target == null) {
            return;
        }

        if (target.tag() instanceof Tag.CompoundTag) {
            Optional<NewTag> newTag = askForNewTag(true, null);
            newTag.ifPresent(t -> {
                if (applyEdit(() -> new NbtEditor(target.document()).add(target.path(), t.name(), t.tag()))) {
                    refreshItem(item);
                    item.setExpanded(true);
                }
            });
        } else if (target.tag() instanceof Tag.ListTag list) {
            TagType fixedType = list.getElements().isEmpty() && list.getElementType() == TagType.END
                    ? null : list.getElementType();
            Optional<NewTag> newTag = askForNewTag(false, fixedType);
            newTag.ifPresent(t -> {
                if (applyEdit(() -> new NbtEditor(target.document()).addToList(target.path(), t.tag()))) {
                    refreshItem(item);
                    item.setExpanded(true);
                }
            });
        }
    }

    private void deleteSelected() {
        TreeItem<NbtTreeNode> item = treeView.getSelectionModel().getSelectedItem();
        Target target = targetOf(item);
        if (!isEditable() || target == null || target.path().isRoot()) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + item.getValue().getName() + " (" + target.tag().getType() + ")?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        TreeItem<NbtTreeNode> parent = item.getParent();
        if (applyEdit(() -> new NbtEditor(target.document()).remove(target.path()))) {
            refreshItem(parent);
        }
    }

    /** Runs an edit, reporting validation errors instead of applying them */
    private boolean applyEdit(Runnable edit) {
        try {
            edit.run();
        } catch (NbtFormatException e) {
            Alert error = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
            error.setHeaderText("That change is not valid");
            error.showAndWait();
            return false;
        }
        onEdited.accept(content);
        return true;
    }

    /** Re-reads an item (and its children) from the model after an edit */
    private void refreshItem(TreeItem<NbtTreeNode> item) {
        Target target = targetOf(item);
        if (target != null) {
            boolean expanded = item.isExpanded();
            NbtTreeBuilder.refresh(item, target.tag());
            // List element labels are their indexes, which shift after a removal
            item.setExpanded(expanded);
        }
    }

    private record NewTag(String name, Tag tag) {
    }

    /**
     * Asks for a new tag. For compounds a name and type are chosen; for
     * lists the type is fixed by the list unless it is still empty.
     */
    private Optional<NewTag> askForNewTag(boolean needsName, TagType fixedType) {
        Dialog<NewTag> dialog = new Dialog<>();
        dialog.setTitle("Add Tag");
        dialog.setHeaderText(needsName ? "Add a tag to this compound" : "Add an element to this list");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField name = new TextField();
        ComboBox<TagType> type = new ComboBox<>();
        for (TagType t : TagType.values()) {
            if (t != TagType.END) {
                type.getItems().add(t);
            }
        }
        type.setValue(fixedType != null ? fixedType : TagType.INT);
        type.setDisable(fixedType != null);
        TextField value = new TextField();
        value.setPromptText("Value (arrays: comma separated)");

        type.valueProperty().addListener((obs, old, t) ->
                value.setDisable(t == TagType.COMPOUND || t == TagType.LIST));
        value.setDisable(type.getValue() == TagType.COMPOUND || type.getValue() == TagType.LIST);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int row = 0;
        if (needsName) {
            grid.addRow(row++, new Label("Name:"), name);
        }
        grid.addRow(row++, new Label("Type:"), type);
        grid.addRow(row, new Label("Value:"), value);
        GridPane.setHgrow(value, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);

        // Validate before closing so a typo does not lose the dialog
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                if (needsName && name.getText().isEmpty()) {
                    throw new NbtFormatException("Enter a name for the new tag");
                }
                createTag(type.getValue(), value.getText());
            } catch (NbtFormatException e) {
                Alert error = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
                error.setHeaderText("That tag is not valid");
                error.showAndWait();
                event.consume();
            }
        });

        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new NewTag(name.getText(), createTag(type.getValue(), value.getText()))
                : null);
        return dialog.showAndWait();
    }

    private static Tag createTag(TagType type, String text) {
        return switch (type) {
            case COMPOUND -> new Tag.CompoundTag();
            case LIST -> new Tag.ListTag(TagType.END);
            default -> NbtValues.parse(type, text);
        };
    }

    // ------------------------------------------------------------------

    /** One row: name, type and value columns, plus the edit menu */
    private class NbtCell extends TreeCell<NbtTreeNode> {

        @Override
        protected void updateItem(NbtTreeNode node, boolean empty) {
            super.updateItem(node, empty);

            if (empty || node == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                return;
            }

            Label name = new Label(node.getName());
            Label type = new Label(node.getType());
            Label value = new Label(
                    node.getValue() == null
                            ? ""
                            : node.getValue().toString());

            name.setPrefWidth(180);
            type.setPrefWidth(100);
            value.setMaxWidth(Double.MAX_VALUE);

            // Create a compact row for each NBT tag
            HBox row = new HBox(8, name, type, value);
            HBox.setHgrow(value, Priority.ALWAYS);
            row.setPadding(new Insets(0, 4, 0, 4));

            setText(null);
            setGraphic(row);
            setContextMenu(isEditable() ? menuFor(node) : null);
        }

        private ContextMenu menuFor(NbtTreeNode node) {
            boolean container = "COMPOUND".equals(node.getType()) || "LIST".equals(node.getType());
            boolean isDocumentRoot = getTreeItem() != null && getTreeItem().getParent() != null
                    && getTreeItem().getParent().getParent() == null;

            MenuItem edit = new MenuItem("Edit value...");
            edit.setDisable(container);
            edit.setOnAction(e -> {
                getTreeView().getSelectionModel().select(getTreeItem());
                editSelectedValue();
            });

            MenuItem add = new MenuItem("COMPOUND".equals(node.getType()) ? "Add tag..." : "Add element...");
            add.setDisable(!container);
            add.setOnAction(e -> {
                getTreeView().getSelectionModel().select(getTreeItem());
                addToSelected();
            });

            MenuItem delete = new MenuItem("Delete");
            delete.setDisable(isDocumentRoot);
            delete.setOnAction(e -> {
                getTreeView().getSelectionModel().select(getTreeItem());
                deleteSelected();
            });

            return new ContextMenu(edit, add, new SeparatorMenuItem(), delete);
        }
    }
}
