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
