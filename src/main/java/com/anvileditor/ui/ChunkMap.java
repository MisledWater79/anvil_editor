package com.anvileditor.ui;

import com.anvileditor.world.ChunkPos;
import com.anvileditor.world.Dimension;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

/**
 * A top-down map of the chunks saved in one dimension.
 *
 * Each saved chunk is drawn as a square (x to the right, z downwards, as
 * in Minecraft's map). Drag to pan, scroll to zoom, click to select.
 */
public class ChunkMap extends Pane {

    private static final double MIN_CELL = 1;
    private static final double MAX_CELL = 48;

    private static final Color BACKGROUND = Color.web("#1f2a36");
    private static final Color CHUNK = Color.web("#5aa469");
    private static final Color GRID = Color.web("#00000055");
    private static final Color AXIS = Color.web("#ffffff66");
    private static final Color SELECTED = Color.web("#ff5555");

    private final Canvas canvas = new Canvas();

    private Dimension dimension = Dimension.OVERWORLD;
    private Set<Long> chunks = new HashSet<>();
    private Collection<ChunkPos> chunkList = Set.of();
    private ChunkPos selected;

    // View: the chunk coordinate at the centre of the canvas, and pixels per chunk
    private double centerX;
    private double centerZ;
    private double cellSize = 12;

    private double pressX;
    private double pressY;
    private double pressCenterX;
    private double pressCenterZ;
    private boolean dragged;

    private Consumer<ChunkPos> onChunkClicked = p -> { };
    private Consumer<ChunkPos> onHover = p -> { };

    public ChunkMap() {
        getStyleClass().add("chunk-map");
        getChildren().add(canvas);
        setMinSize(120, 120);
        setPrefSize(320, 230);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener(o -> redraw());
        canvas.heightProperty().addListener(o -> redraw());

        canvas.setOnMousePressed(e -> {
            pressX = e.getX();
            pressY = e.getY();
            pressCenterX = centerX;
            pressCenterZ = centerZ;
            dragged = false;
        });
        canvas.setOnMouseDragged(e -> {
            double dx = e.getX() - pressX;
            double dy = e.getY() - pressY;
            if (Math.abs(dx) + Math.abs(dy) > 3) {
                dragged = true;
            }
            centerX = pressCenterX - dx / cellSize;
            centerZ = pressCenterZ - dy / cellSize;
            redraw();
        });
        canvas.setOnMouseReleased(e -> {
            if (!dragged && e.getButton() == MouseButton.PRIMARY) {
                ChunkPos pos = chunkAt(e.getX(), e.getY());
                if (chunks.contains(pack(pos.x(), pos.z()))) {
                    onChunkClicked.accept(pos);
                }
            }
        });
        canvas.setOnMouseMoved(e -> {
            ChunkPos pos = chunkAt(e.getX(), e.getY());
            onHover.accept(chunks.contains(pack(pos.x(), pos.z())) ? pos : null);
        });
        canvas.setOnScroll(e -> {
            if (e.getDeltaY() == 0) {
                return;
            }
            // Zoom around the cursor so the chunk under it stays put
            double beforeX = toChunkX(e.getX());
            double beforeZ = toChunkZ(e.getY());
            double factor = e.getDeltaY() > 0 ? 1.25 : 0.8;
            cellSize = Math.max(MIN_CELL, Math.min(MAX_CELL, cellSize * factor));
            centerX += beforeX - toChunkX(e.getX());
            centerZ += beforeZ - toChunkZ(e.getY());
            redraw();
        });
    }

    public void setOnChunkClicked(Consumer<ChunkPos> handler) {
        onChunkClicked = handler == null ? p -> { } : handler;
    }

    /** Called with the saved chunk under the mouse, or null */
    public void setOnHover(Consumer<ChunkPos> handler) {
        onHover = handler == null ? p -> { } : handler;
    }

    public Dimension getDimension() {
        return dimension;
    }

    /** Shows a dimension's chunks and centres the view on them */
    public void setChunks(Dimension dimension, Collection<ChunkPos> positions) {
        this.dimension = dimension;
        this.chunkList = positions;
        this.chunks = new HashSet<>(positions.size() * 2);
        for (ChunkPos p : positions) {
            chunks.add(pack(p.x(), p.z()));
        }
        if (selected != null && selected.dimension() != dimension) {
            selected = null;
        }
        centerOnBulk();
        redraw();
    }

    /** Highlights a chunk and scrolls it into view if needed */
    public void setSelected(ChunkPos pos) {
        selected = pos;
        if (pos != null && pos.dimension() == dimension) {
            double halfW = canvas.getWidth() / cellSize / 2;
            double halfH = canvas.getHeight() / cellSize / 2;
            if (Math.abs(pos.x() + 0.5 - centerX) > halfW - 1 || Math.abs(pos.z() + 0.5 - centerZ) > halfH - 1) {
                centerX = pos.x() + 0.5;
                centerZ = pos.z() + 0.5;
            }
        }
        redraw();
    }

    /**
     * Centres on the median chunk, which stays on the main build area even
     * when a few far-away chunks exist (an outlying portal, a long trip...).
     */
    private void centerOnBulk() {
        if (chunkList.isEmpty()) {
            centerX = 0;
            centerZ = 0;
            return;
        }
        int[] xs = chunkList.stream().mapToInt(ChunkPos::x).sorted().toArray();
        int[] zs = chunkList.stream().mapToInt(ChunkPos::z).sorted().toArray();
        centerX = xs[xs.length / 2] + 0.5;
        centerZ = zs[zs.length / 2] + 0.5;
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        if (w <= 0 || h <= 0) {
            return;
        }

        int minX = (int) Math.floor(toChunkX(0));
        int maxX = (int) Math.floor(toChunkX(w));
        int minZ = (int) Math.floor(toChunkZ(0));
        int maxZ = (int) Math.floor(toChunkZ(h));
        long visibleCells = (long) (maxX - minX + 1) * (maxZ - minZ + 1);

        // Use whichever loop is smaller: visible cells or saved chunks
        g.setFill(CHUNK);
        double gap = cellSize >= 6 ? 1 : 0;
        if (visibleCells < chunks.size()) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (chunks.contains(pack(x, z))) {
                        g.fillRect(toScreenX(x), toScreenY(z), cellSize - gap, cellSize - gap);
                    }
                }
            }
        } else {
            for (ChunkPos p : chunkList) {
                if (p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ) {
                    g.fillRect(toScreenX(p.x()), toScreenY(p.z()), cellSize - gap, cellSize - gap);
                }
            }
        }

        // Grid lines when zoomed in far enough to be useful
        if (cellSize >= 10) {
            g.setStroke(GRID);
            g.setLineWidth(1);
            for (int x = minX; x <= maxX + 1; x++) {
                double sx = Math.floor(toScreenX(x)) + 0.5;
                g.strokeLine(sx, 0, sx, h);
            }
            for (int z = minZ; z <= maxZ + 1; z++) {
                double sy = Math.floor(toScreenY(z)) + 0.5;
                g.strokeLine(0, sy, w, sy);
            }
        }

        // World origin (chunk 0, 0)
        g.setStroke(AXIS);
        g.setLineWidth(1);
        double ox = Math.floor(toScreenX(0)) + 0.5;
        double oz = Math.floor(toScreenY(0)) + 0.5;
        g.strokeLine(ox, 0, ox, h);
        g.strokeLine(0, oz, w, oz);

        if (selected != null && selected.dimension() == dimension) {
            g.setStroke(SELECTED);
            g.setLineWidth(2);
            double size = Math.max(cellSize, 6);
            double offset = (size - cellSize) / 2;
            g.strokeRect(toScreenX(selected.x()) - offset, toScreenY(selected.z()) - offset, size, size);
        }

        if (chunks.isEmpty()) {
            g.setFill(Color.web("#ffffffaa"));
            g.fillText("No chunks saved in " + dimension.getDisplayName(), 10, 20);
        }
    }

    private ChunkPos chunkAt(double sx, double sy) {
        return new ChunkPos(dimension, (int) Math.floor(toChunkX(sx)), (int) Math.floor(toChunkZ(sy)));
    }

    private double toChunkX(double sx) {
        return centerX + (sx - canvas.getWidth() / 2) / cellSize;
    }

    private double toChunkZ(double sy) {
        return centerZ + (sy - canvas.getHeight() / 2) / cellSize;
    }

    private double toScreenX(double chunkX) {
        return (chunkX - centerX) * cellSize + canvas.getWidth() / 2;
    }

    private double toScreenY(double chunkZ) {
        return (chunkZ - centerZ) * cellSize + canvas.getHeight() / 2;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
