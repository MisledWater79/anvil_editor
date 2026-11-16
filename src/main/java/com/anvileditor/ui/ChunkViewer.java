package com.anvileditor.ui;

import com.anvileditor.world.BedrockWorld;
import com.anvileditor.world.ChunkPos;
import com.anvileditor.world.Dimension;
import com.anvileditor.world.WorldRecord;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

/**
 * Displays chunk data in the Anvil Editor interface
 * (which is the upper half): a tree of the world's dimensions,
 * chunks and records on the left, and a chunk map on the right.
 */
public class ChunkViewer extends BorderPane {

    /** A non-selectable grouping node in the world tree */
    private record Folder(String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    // Displays the currently selected chunk's coordinates
    private final Label selectedChunkLabel = new Label("No world open");

    private final TreeView<Object> worldTree = new TreeView<>();
    private final ChunkMap chunkMap = new ChunkMap();
    private final ComboBox<Dimension> dimensionBox = new ComboBox<>();
    private final Label hoverLabel = new Label(" ");

    private BedrockWorld world;
    private final Map<Dimension, TreeItem<Object>> dimensionItems = new EnumMap<>(Dimension.class);
    private final Map<ChunkPos, TreeItem<Object>> chunkItems = new HashMap<>();

    private Consumer<WorldRecord> onRecordSelected = r -> { };
    private Consumer<ChunkPos> onChunkSelected = c -> { };

    /**
     * Upper "half" of the editor interface
     */
    public ChunkViewer() {
        // Apply the styling to the chunk viewer section
        getStyleClass().add("chunk-viewer");

        // Grid to arrange the world tree and chunk map side by side
        GridPane chunkViewer = new GridPane();
        chunkViewer.setHgap(10);
        chunkViewer.setVgap(10);
        chunkViewer.setPadding(new Insets(10));

        // World tree on the left side, chunk map on the right side
        chunkViewer.add(createWorldPanel(), 0, 0);
        chunkViewer.add(createChunkMapPanel(), 1, 0);

        // Give the map column the remaining available horizontal space
        ColumnConstraints worldColumn = new ColumnConstraints(200, 260, 420);
        worldColumn.setHgrow(Priority.SOMETIMES);
        ColumnConstraints mapColumn = new ColumnConstraints();
        mapColumn.setHgrow(Priority.ALWAYS);
        chunkViewer.getColumnConstraints().addAll(worldColumn, mapColumn);

        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        chunkViewer.getRowConstraints().add(row);

        // Use the grid as the main content of this component
        setCenter(chunkViewer);

        // Display the selected chunk information underneath the grid
        selectedChunkLabel.setPadding(new Insets(4, 10, 16, 10));
        setBottom(selectedChunkLabel);
    }

    /** Called when a record (or level.dat) is selected in the tree */
    public void setOnRecordSelected(Consumer<WorldRecord> handler) {
        onRecordSelected = handler == null ? r -> { } : handler;
    }

    /** Called when a chunk itself is selected (in the tree or on the map) */
    public void setOnChunkSelected(Consumer<ChunkPos> handler) {
        onChunkSelected = handler == null ? c -> { } : handler;
    }

    /**
     * Dimension and chunk tree on the left side of the window
     */
    private VBox createWorldPanel() {
        Label worldTitle = new Label("World");
        worldTitle.setStyle("-fx-font-size: 17px; -fx-underline: true;");

        worldTree.setShowRoot(false);
        worldTree.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(Object value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                } else if (value instanceof ChunkPos pos) {
                    setText(pos.label());
                } else {
                    setText(value.toString());
                }
            }
        });

        worldTree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item == null) {
                return;
            }
            Object value = item.getValue();
            if (value instanceof WorldRecord record) {
                if (record.chunk() != null) {
                    showSelectedChunk(record.chunk());
                }
                onRecordSelected.accept(record);
            } else if (value instanceof ChunkPos pos) {
                showSelectedChunk(pos);
                onChunkSelected.accept(pos);
            }
        });

        VBox panel = new VBox(8, worldTitle, worldTree);
        panel.setPadding(new Insets(5));
        panel.setPrefWidth(260);

        VBox.setVgrow(worldTree, Priority.ALWAYS);
        worldTree.setMaxHeight(Double.MAX_VALUE);
        worldTree.setPrefHeight(230);

        return panel;
    }

    /**
     * Map of the saved chunks in the chosen dimension
     */
    private VBox createChunkMapPanel() {
        Label mapTitle = new Label("Chunk Map");
        mapTitle.setStyle("-fx-font-size: 17px; -fx-underline: true;");

        dimensionBox.getItems().addAll(Dimension.values());
        dimensionBox.setValue(Dimension.OVERWORLD);
        dimensionBox.setDisable(true);
        dimensionBox.valueProperty().addListener((obs, old, dim) -> {
            if (world != null && dim != null && dim != chunkMap.getDimension()) {
                chunkMap.setChunks(dim, world.getChunks(dim));
            }
        });

        chunkMap.setOnChunkClicked(this::selectChunk);
        chunkMap.setOnHover(pos -> hoverLabel.setText(pos == null ? " " : "Chunk " + pos.label()
                + "  blocks " + (pos.x() * 16) + ", " + (pos.z() * 16)));

        HBox header = new HBox(10, mapTitle, dimensionBox, hoverLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        hoverLabel.setStyle("-fx-font-size: 12px;");

        Label help = new Label("Drag to move, scroll to zoom, click a chunk to open it");
        help.setStyle("-fx-font-size: 11px;");

        VBox panel = new VBox(6, header, chunkMap, help);
        panel.setPadding(new Insets(5));
        VBox.setVgrow(chunkMap, Priority.ALWAYS);

        return panel;
    }

    /** Fills the tree and map with an opened world */
    public void setWorld(BedrockWorld world) {
        this.world = world;
        dimensionItems.clear();
        chunkItems.clear();

        TreeItem<Object> root = new TreeItem<>(new Folder(world.getName()));

        if (world.hasLevelDat()) {
            root.getChildren().add(new TreeItem<>(WorldRecord.LEVEL_DAT));
        }

        Dimension firstWithChunks = null;
        for (Dimension dimension : Dimension.values()) {
            var positions = world.getChunks(dimension);
            if (positions.isEmpty()) {
                continue;
            }
            if (firstWithChunks == null) {
                firstWithChunks = dimension;
            }

            TreeItem<Object> dimItem = new TreeItem<>(new Folder(dimension.getDisplayName()
                    + " (" + positions.size() + " chunks)"));
            for (ChunkPos pos : positions) {
                TreeItem<Object> chunkItem = new ChunkTreeItem(pos);
                chunkItems.put(pos, chunkItem);
                dimItem.getChildren().add(chunkItem);
            }
            dimensionItems.put(dimension, dimItem);
            root.getChildren().add(dimItem);
        }

        List<WorldRecord> others = world.getOtherRecords();
        if (!others.isEmpty()) {
            TreeItem<Object> otherItem = new TreeItem<>(new Folder("Other Records (" + others.size() + ")"));
            for (WorldRecord record : others) {
                otherItem.getChildren().add(new TreeItem<>(record));
            }
            root.getChildren().add(otherItem);
        }

        root.setExpanded(true);
        worldTree.setRoot(root);

        Dimension shown = firstWithChunks == null ? Dimension.OVERWORLD : firstWithChunks;
        dimensionBox.setDisable(false);
        dimensionBox.setValue(shown);
        chunkMap.setSelected(null);
        chunkMap.setChunks(shown, world.getChunks(shown));

        selectedChunkLabel.setText(world.getChunkCount() + " chunks. Select a chunk on the map or in the tree.");
    }

    /** Selects a chunk in the tree (expanding it to show its records) and on the map */
    public void selectChunk(ChunkPos pos) {
        TreeItem<Object> item = chunkItems.get(pos);
        if (item == null) {
            return;
        }
        dimensionItems.get(pos.dimension()).setExpanded(true);
        item.setExpanded(true);
        worldTree.getSelectionModel().select(item);
        int row = worldTree.getRow(item);
        if (row >= 0) {
            worldTree.scrollTo(Math.max(0, row - 2));
        }
    }

    private void showSelectedChunk(ChunkPos pos) {
        selectedChunkLabel.setText("Selected Chunk: " + pos.dimension().getDisplayName() + " " + pos.label()
                + "  (blocks " + (pos.x() * 16) + ", " + (pos.z() * 16) + " to "
                + (pos.x() * 16 + 15) + ", " + (pos.z() * 16 + 15) + ")");
        if (pos.dimension() != chunkMap.getDimension()) {
            dimensionBox.setValue(pos.dimension());
        }
        chunkMap.setSelected(pos);
    }

    /** A chunk node whose records are read only when it is first expanded */
    private class ChunkTreeItem extends TreeItem<Object> {
        private boolean loaded;

        ChunkTreeItem(ChunkPos pos) {
            super(pos);
            expandedProperty().addListener((obs, was, now) -> {
                if (now) {
                    load();
                }
            });
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        private void load() {
            if (loaded || world == null) {
                return;
            }
            loaded = true;
            try {
                for (WorldRecord record : world.getChunkRecords((ChunkPos) getValue())) {
                    getChildren().add(new TreeItem<>(record));
                }
            } catch (IOException e) {
                getChildren().add(new TreeItem<>(new Folder("Could not read chunk: " + e.getMessage())));
            }
        }
    }
}
