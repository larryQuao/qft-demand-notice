package com.qft.demandnotice.ui;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Shows a rendered preview of the exact PDF that will be saved: the converted
 * Publisher template with the form values already stamped into it.
 *
 * <p>Rendering uses PDFBox ({@link PDFRenderer}) straight into a JavaFX
 * {@link ImageView}, so no browser or PDF viewer plugin is needed.</p>
 */
public class PdfPreviewController {

    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 4f;
    private static final float ZOOM_STEP = 1.25f;
    /** Raster resolution before zoom; 2.0 keeps text crisp on typical displays. */
    private static final float RENDER_DPI = 144f;

    @FXML private ScrollPane previewScrollPane;
    @FXML private StackPane pageContainer;
    @FXML private ImageView previewImageView;
    @FXML private Label pageLabel;
    @FXML private Label zoomLabel;
    @FXML private Label tokenStatusLabel;
    @FXML private Button fitButton;
    @FXML private Button saveButton;

    private byte[] pdfBytes;
    private PDFRenderer renderer;
    private PDDocument document;
    private int pageIndex;
    private float zoom = 1f;
    private float fitZoom = 1f;

    /** Invoked when the user accepts the preview; the host owns the Save flow. */
    private Consumer<PdfPreviewController> onSave;

    @FXML
    private void initialize() {
        previewImageView.setPreserveRatio(true);
        previewImageView.setSmooth(true);
        // Keep the page centred until it is wider/taller than the viewport.
        pageContainer.setAlignment(Pos.CENTER);
        previewScrollPane.viewportBoundsProperty().addListener(
                (obs, old, bounds) -> {
                    pageContainer.setMinWidth(bounds.getWidth());
                    pageContainer.setMinHeight(bounds.getHeight());
                    recomputeFitZoom(bounds.getWidth(), bounds.getHeight());
                });
        installGestureZoom();
    }

    /**
     * Gesture zoom for the page preview:
     * <ul>
     *   <li>{@code Ctrl} + mouse wheel zooms in/out, anchored at the cursor.
     *       This also covers two-finger pinch on Windows precision touchpads,
     *       which the OS reports as a ctrl + wheel scroll.</li>
     *   <li>Two-finger pinch on a touch screen arrives as a JavaFX
     *       {@link javafx.scene.input.ZoomEvent}, zooming per the gesture.</li>
     *   <li>Plain scrolling (wheel or two-finger) pans the page; the ScrollPane
     *       is also pannable, so dragging with the mouse moves the page too.</li>
     * </ul>
     */
    private void installGestureZoom() {
        previewScrollPane.addEventFilter(javafx.scene.input.ScrollEvent.ANY, event -> {
            if (!event.isControlDown()) {
                return; // plain scrolling pans the document as usual
            }
            double deltaY = event.getDeltaY();
            double deltaX = event.getDeltaX();
            if (deltaY != 0 || deltaX != 0) {
                double dominant = Math.abs(deltaY) >= Math.abs(deltaX) ? deltaY : deltaX;
                // One wheel notch is deltaY = 40; keep 1.25x per notch so the
                // wheel matches the zoom buttons.
                zoomAt(zoom * (float) Math.pow(ZOOM_STEP, dominant / 40.0),
                        event.getSceneX(), event.getSceneY());
            }
            event.consume();
        });
        previewScrollPane.addEventFilter(javafx.scene.input.ZoomEvent.ANY, event -> {
            zoomAt(zoom * (float) event.getZoomFactor(),
                    event.getSceneX(), event.getSceneY());
            event.consume();
        });
    }

    /**
     * Applies {@code newZoom} and, when the gesture comes from a pointing
     * device, keeps the point under the cursor/finger anchored: whatever part
     * of the page was at the cursor before the zoom stays there afterwards.
     */
    private void zoomAt(double newZoom, double sceneX, double sceneY) {
        if (previewImageView.getImage() == null) {
            setZoom((float) newZoom);
            return;
        }
        javafx.geometry.Bounds before = previewImageView.localToScene(
                previewImageView.getBoundsInLocal());
        javafx.geometry.Bounds viewport = previewScrollPane.localToScene(
                previewScrollPane.getViewportBounds());
        if (before.getWidth() <= 0 || before.getHeight() <= 0
                || viewport.getWidth() <= 0 || viewport.getHeight() <= 0) {
            setZoom((float) newZoom);
            return;
        }
        // Where on the page was the cursor, as a 0..1 fraction of the image?
        double fractionX = clamp01((sceneX - before.getMinX()) / before.getWidth());
        double fractionY = clamp01((sceneY - before.getMinY()) / before.getHeight());

        setZoom((float) newZoom);
        // Force the new fit width/height to be laid out before re-anchoring.
        previewScrollPane.applyCss();
        previewScrollPane.layout();

        javafx.geometry.Bounds after = previewImageView.localToScene(
                previewImageView.getBoundsInLocal());
        double rangeX = after.getWidth() - viewport.getWidth();
        double rangeY = after.getHeight() - viewport.getHeight();
        if (rangeX > 0) {
            // Shift the content so the fraction point sits under the cursor.
            previewScrollPane.setHvalue((fractionX * after.getWidth()
                    - (sceneX - after.getMinX())) / rangeX);
        }
        if (rangeY > 0) {
            previewScrollPane.setVvalue((fractionY * after.getHeight()
                    - (sceneY - after.getMinY())) / rangeY);
        }
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** Wires the callback the host uses to run the Save dialog. */
    public void setOnSave(Consumer<PdfPreviewController> onSave) {
        this.onSave = onSave;
    }

    /**
     * Loads {@code pdfBytes} for previewing.
     *
     * @param tokenStatus a human readable note about which placeholders were
     *                    found/replaced, shown above the page
     */
    public void showPdf(byte[] pdfBytes, String tokenStatus) throws IOException {
        closeDocument();
        this.pdfBytes = pdfBytes.clone();
        this.document = Loader.loadPDF(this.pdfBytes);
        this.renderer = new PDFRenderer(document);
        this.pageIndex = 0;
        tokenStatusLabel.setText(tokenStatus);
        renderPage();
        updateChrome();
    }

    /** The bytes currently being previewed (call after {@link #showPdf}). */
    public byte[] pdfBytes() {
        return pdfBytes == null ? null : pdfBytes.clone();
    }

    public void setSaveEnabled(boolean enabled) {
        saveButton.setDisable(!enabled);
    }

    // ------------------------------------------------------------------
    // Navigation / zoom
    // ------------------------------------------------------------------

    @FXML
    private void handlePrevPage() {
        if (pageIndex > 0) {
            pageIndex--;
            renderPage();
            updateChrome();
        }
    }

    @FXML
    private void handleNextPage() {
        if (document != null && pageIndex < document.getNumberOfPages() - 1) {
            pageIndex++;
            renderPage();
            updateChrome();
        }
    }

    @FXML
    private void handleZoomIn() {
        setZoom(zoom * ZOOM_STEP);
    }

    @FXML
    private void handleZoomOut() {
        setZoom(zoom / ZOOM_STEP);
    }

    @FXML
    private void handleFit() {
        setZoom(fitZoom);
    }

    @FXML
    private void handleSave() {
        if (onSave != null) {
            onSave.accept(this);
        }
    }

    private void setZoom(float newZoom) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        applyImageSize();
        updateChrome();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void renderPage() {
        if (renderer == null) {
            return;
        }
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI,
                    ImageType.RGB);
            previewImageView.setImage(toFxImage(image));
            applyImageSize();
        } catch (IOException e) {
            tokenStatusLabel.setText("The preview could not be rendered: " + e.getMessage());
        }
    }

    /**
     * Copies a rendered page into a JavaFX image without the javafx.swing
     * module (which is not on the classpath), by writing the AWT pixels
     * through {@link PixelWriter}.
     */
    private static WritableImage toFxImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        WritableImage fx = new WritableImage(width, height);
        PixelWriter writer = fx.getPixelWriter();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        writer.setPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return fx;
    }

    private void applyImageSize() {
        if (previewImageView.getImage() == null) {
            return;
        }
        previewImageView.setFitWidth(previewImageView.getImage().getWidth() * zoom);
        previewImageView.setFitHeight(previewImageView.getImage().getHeight() * zoom);
    }

    private void recomputeFitZoom(double viewportWidth, double viewportHeight) {
        if (previewImageView.getImage() == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        double imageWidth = previewImageView.getImage().getWidth();
        double imageHeight = previewImageView.getImage().getHeight();
        // 24px of breathing room so the page is not flush against the edges.
        fitZoom = (float) Math.max(MIN_ZOOM,
                Math.min((viewportWidth - 24) / imageWidth, (viewportHeight - 24) / imageHeight));
    }

    private void updateChrome() {
        if (document == null) {
            pageLabel.setText("");
        } else {
            pageLabel.setText("Page " + (pageIndex + 1) + " of " + document.getNumberOfPages());
        }
        zoomLabel.setText(Math.round(zoom * 100) + "%");
        fitButton.setDisable(zoom == fitZoom);
    }

    /** Releases the loaded PDF; call when the preview window closes. */
    public void closeDocument() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException ignored) {
                // closing is best-effort
            }
            document = null;
        }
        renderer = null;
    }
}
