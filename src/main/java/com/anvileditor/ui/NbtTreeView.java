package com.anvileditor.ui;

import com.anvileditor.nbt.NbtDocument;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Displays hierarchical NBT data in the Anvil Editor interface.
 *
 * TreeItem manages the hierarchy, while NbtTreeNode stores
 * the information displayed for each tag.
 */
public class NbtTreeView extends BorderPane {

    private final TreeView<NbtTreeNode> treeView;

    /**
     * Lower "half" of the editor interface
     */
    public NbtTreeView() {
        // Apply the styling to the NBT tree view section
        getStyleClass().add("nbt-tree-view");

        // Create the root tag for the tree
        TreeItem<NbtTreeNode> root =
                new TreeItem<>(new NbtTreeNode("NBT Data", "ROOT", null));

        // Example Level compound
        TreeItem<NbtTreeNode> level =
                new TreeItem<>(new NbtTreeNode("Level", "COMPOUND", null));

        level.getChildren().add(new TreeItem<>(new NbtTreeNode("xPos", "INT", 1)));
        level.getChildren().add(new TreeItem<>(new NbtTreeNode("zPos", "INT", 0)));
        level.getChildren().add(new TreeItem<>(new NbtTreeNode("Status", "STRING", "full")));

        // Example Entities list containing one entity
        TreeItem<NbtTreeNode> entities =
                new TreeItem<>(new NbtTreeNode("Entities", "LIST", null));

        TreeItem<NbtTreeNode> entity =
                new TreeItem<>(new NbtTreeNode("Entity 0", "COMPOUND", null));

        entity.getChildren().add(
            new TreeItem<>(new NbtTreeNode("id", "STRING", "minecraft:cow")));
        entity.getChildren().add(
            new TreeItem<>(new NbtTreeNode("Health", "FLOAT", 20.0f)));

        entities.getChildren().add(entity);
        level.getChildren().add(entities);
        root.getChildren().add(level);

        // Set up the JavaFX tree
        treeView = new TreeView<>(root);
        treeView.setShowRoot(false);
        treeView.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(NbtTreeNode node, boolean empty) {
                super.updateItem(node, empty);

                if (empty || node == null) {
                    setGraphic(null);
                    setText(null);
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
            }
        });

        // Expand the main containers to show the sample data
        root.setExpanded(true);
        level.setExpanded(true);
        entities.setExpanded(true);
        entity.setExpanded(true);

        // Add the panel title and tree to the layout
        Label title = new Label("NBT Data");
        title.setStyle("-fx-font-family: \"Minecraft Default\"; -fx-font-size: 18px; -fx-font-weight: bold; -fx-underline: true;");
        title.setPadding(new Insets(8));

        setTop(title);
        setCenter(treeView);

        treeView.getStyleClass().add("nbt-tree");
    }

    public TreeView<NbtTreeNode> getTreeView() {
        return treeView;
    }

    /**
     * Replaces the displayed tree with the contents of an NBT document
     * (for example one read from a LevelDB record or level.dat)
     */
    public void showDocument(NbtDocument document) {
        treeView.setRoot(NbtTreeBuilder.build(document));
    }
}
