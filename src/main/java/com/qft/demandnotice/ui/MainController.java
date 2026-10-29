package com.qft.demandnotice.ui;

import com.qft.demandnotice.model.DemandNoticeData;
import com.qft.demandnotice.service.AmountFormatter;
import com.qft.demandnotice.service.DateFormatter;
import com.qft.demandnotice.service.PdfGenerator;
import com.qft.demandnotice.service.TemplateStamper;
import com.qft.demandnotice.util.Constants;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Controller wiring the demand notice form to the PDF generator. */
public class MainController {

    @FXML private ComboBox<Integer> letterDayCombo;
    @FXML private ComboBox<String> letterMonthCombo;
    @FXML private Spinner<Integer> letterYearSpinner;
    @FXML private ComboBox<String> contribMonthCombo;
    @FXML private Spinner<Integer> contribYearSpinner;
    @FXML private TextField amountField;
    @FXML private Label amountPreviewLabel;
    @FXML private ComboBox<Integer> deadlineDayCombo;
    @FXML private ComboBox<String> deadlineMonthCombo;
    @FXML private Spinner<Integer> deadlineYearSpinner;
    @FXML private TextField templatePathField;
    @FXML private Button browseTemplateButton;
    @FXML private Button clearTemplateButton;
    @FXML private Button previewButton;
    @FXML private Button generateButton;
    @FXML private Button clearButton;
    @FXML private Button openPdfButton;
    @FXML private Label statusLabel;

    private static final int YEAR_MIN = 2020;
    private static final int YEAR_MAX = 2040;

    /** The one size the preview window always opens at (never user-resized leftovers). */
    private static final double PREVIEW_WIDTH = 760;
    private static final double PREVIEW_HEIGHT = 900;

    private Path lastGeneratedPath;
    private Path selectedTemplate;
    private File lastSaveDir;

    /**
     * Runs the (slow) Publisher conversions one at a time. A template picked
     * in the file chooser starts converting immediately, so the result is
     * usually ready long before the user clicks "Preview PDF".
     */
    private final java.util.concurrent.ExecutorService templatePreparer =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread worker = new Thread(r, "pub-template-preparer");
                worker.setDaemon(true);
                return worker;
            });
    /** Conversion in flight (or done) for {@link #selectedTemplate}. */
    private java.util.concurrent.CompletableFuture<TemplateStamper.PreparedTemplate>
            preparation;

    /**
     * The currently converted template. Converting with Microsoft Publisher
     * takes 15-20 s, so the result is cached and only re-stamped (fast) when
     * the preview is refreshed. Invalidated whenever the template changes.
     */
    private TemplateStamper.PreparedTemplate preparedTemplate;
    /** Which .pub {@link #preparedTemplate} was converted from, to detect edits. */
    private Path preparedFrom;
    private FileTime preparedFromStamp;
    /** The stage of the (reused) preview window, if it has been opened. */
    private Stage previewStage;
    private PdfPreviewController previewController;

    @FXML
    private void initialize() {
        int currentYear = LocalDate.now().getYear();

        for (ComboBox<Integer> dayCombo : new ComboBox[]{letterDayCombo, deadlineDayCombo}) {
            for (int d = 1; d <= 31; d++) {
                dayCombo.getItems().add(d);
            }
        }
        for (ComboBox<String> monthCombo : new ComboBox[]{letterMonthCombo, deadlineMonthCombo,
                contribMonthCombo}) {
            for (Month m : Month.values()) {
                monthCombo.getItems().add(DateFormatter.monthName(m));
            }
        }
        for (Spinner<Integer> yearSpinner : new Spinner[]{letterYearSpinner, contribYearSpinner,
                deadlineYearSpinner}) {
            yearSpinner.setValueFactory(
                    new SpinnerValueFactory.IntegerSpinnerValueFactory(YEAR_MIN, YEAR_MAX,
                            currentYear));
            yearSpinner.setEditable(false);
        }

        // Sensible defaults: letter date = today, contribution period = last month,
        // deadline = 14 days from today.
        LocalDate today = LocalDate.now();
        selectDate(letterDayCombo, letterMonthCombo, letterYearSpinner, today);
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        contribMonthCombo.getSelectionModel().select(lastMonth.getMonth().ordinal());
        contribYearSpinner.getValueFactory().setValue(lastMonth.getYear());
        selectDate(deadlineDayCombo, deadlineMonthCombo, deadlineYearSpinner, today.plusDays(14));

        amountField.textProperty().addListener((obs, oldText, newText) ->
                updateAmountPreview(newText));

        setStatus(null, false);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    @FXML
    private void handleBrowseTemplate() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a Microsoft Publisher template");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Microsoft Publisher", "*.pub"));
        File initialDir = Paths.get(System.getProperty("user.home"), "Documents").toFile();
        if (initialDir.isDirectory()) {
            chooser.setInitialDirectory(initialDir);
        }
        File chosen = chooser.showOpenDialog(statusLabel.getScene().getWindow());
        if (chosen == null) {
            return;
        }
        selectedTemplate = chosen.toPath();
        templatePathField.setText(chosen.getAbsolutePath());
        clearTemplateButton.setDisable(false);
        invalidatePreparedTemplate();
        startTemplatePreparation();
        setStatus("Template selected - converting in the background..."
                + " You can fill in the form meanwhile.", true);
    }

    @FXML
    private void handleClearTemplate() {
        selectedTemplate = null;
        templatePathField.clear();
        clearTemplateButton.setDisable(true);
        invalidatePreparedTemplate();
        setStatus(null, false);
    }

    /** Drops the cached conversion; the next preview/save re-converts the .pub. */
    private void invalidatePreparedTemplate() {
        preparedTemplate = null;
        preparedFrom = null;
        preparedFromStamp = null;
        // The running conversion is left alone on purpose: its result lands in
        // the disk cache, so switching back to that .pub later is instant.
        preparation = null;
    }

    /**
     * Starts converting the currently selected .pub in the background (one
     * conversion at a time). When it finishes, the result is cached in memory
     * so the first preview does not have to wait for Microsoft Publisher.
     */
    private void startTemplatePreparation() {
        final Path template = selectedTemplate;
        if (template == null) {
            return;
        }
        CompletableFuture<TemplateStamper.PreparedTemplate> thisRun =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return TemplateStamper.prepare(template);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, templatePreparer);
        preparation = thisRun;
        thisRun.whenComplete((prepared, error) -> Platform.runLater(() -> {
            if (preparation != thisRun) {
                return; // superseded by a different template
            }
            if (error != null) {
                setStatus("Template conversion failed: " + rootMessage(error), false);
                return;
            }
            try {
                preparedTemplate = prepared;
                preparedFrom = template;
                preparedFromStamp = stampOf(template);
            } catch (IOException e) {
                return;
            }
            setStatus("Template is ready for preview.", true);
        }));
    }

    /** The tail of an error chain, for status messages. */
    private static String rootMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getCause() == null) {
                return cause.getMessage() == null ? cause.toString() : cause.getMessage();
            }
        }
        return error.toString();
    }

    /**
     * Previews the notice: converts the template if needed, stamps the current
     * form values, and shows the result without saving anything. Saving happens
     * from the preview window, so what the user sees is what is written.
     */
    @FXML
    private void handlePreview() {
        Optional<DemandNoticeData> data = collectData();
        if (data.isEmpty()) {
            return; // collectData already showed the validation error
        }
        if (selectedTemplate == null) {
            // No .pub: the built-in layout is generated in memory and previewed.
            generateFromBuiltIn(data.get(), null);
            return;
        }
        prepareAndPreview(data.get());
    }

    /**
     * Stamps the prepared template and previews it. The conversion (when still
     * needed) runs in the shared preparer queue; stamping runs in this task so
     * the UI thread only renders the finished bytes.
     */
    private void prepareAndPreview(DemandNoticeData data) {
        if (preparation == null || preparation.isCompletedExceptionally()) {
            // First preview after launch (or the previous attempt failed, e.g.
            // Publisher was busy): start a fresh conversion.
            startTemplatePreparation();
        }
        final CompletableFuture<TemplateStamper.PreparedTemplate> scheduled = preparation;
        boolean converting = !scheduled.isDone();
        setBusy(true, converting
                ? "Converting the template with Microsoft Publisher, please wait..."
                : "Updating the preview...");
        if (previewController != null && previewStage != null && previewStage.isShowing()) {
            previewController.setSaveEnabled(false);
        }

        final Path from = selectedTemplate;
        Task<PreviewPayload> task = new Task<>() {
            @Override
            protected PreviewPayload call() throws Exception {
                TemplateStamper.PreparedTemplate prepared = scheduled.get();
                TemplateStamper.StampOutcome outcome = prepared.stamp(data);
                return new PreviewPayload(prepared, outcome, from, stampOf(from));
            }
        };

        task.setOnSucceeded(e -> {
            setBusy(false, null);
            PreviewPayload payload = task.getValue();
            preparedTemplate = payload.prepared;
            preparedFrom = payload.from;
            preparedFromStamp = payload.stamp;
            showPreview(payload.outcome.pdfBytes(),
                    buildTokenNote(payload.prepared, payload.outcome),
                    previewStatus(payload.outcome), !payload.outcome.replacedTokens().isEmpty());
        });

        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable error = task.getException();
            String detail = error instanceof java.util.concurrent.CompletionException
                    && error.getCause() != null
                    ? rootMessage(error.getCause()) : rootMessage(error);
            showError("Template conversion failed.\n\n" + detail);
        });

        new Thread(task, "pub-preview-preparer").start();
    }

    private record PreviewPayload(TemplateStamper.PreparedTemplate prepared,
                                  TemplateStamper.StampOutcome outcome,
                                  Path from, FileTime stamp) {
    }

    /** True when the cached conversion matches the selected .pub on disk. */
    private boolean isPreparedTemplateCurrent() {
        if (preparedTemplate == null || selectedTemplate == null
                || !selectedTemplate.equals(preparedFrom)) {
            return false;
        }
        try {
            return preparedFromStamp != null
                    && preparedFromStamp.equals(stampOf(selectedTemplate));
        } catch (IOException e) {
            return false;
        }
    }

    private static FileTime stampOf(Path file) throws IOException {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Opens (or refreshes) the preview window with the finished PDF bytes.
     *
     * @param tokenNote   explanation of which placeholders were filled in
     * @param statusNote  one-line status for the main window
     * @param saveEnabled whether the values reached the PDF (saving allowed)
     */
    private void showPreview(byte[] pdfBytes, String tokenNote, String statusNote,
                             boolean saveEnabled) {
        try {
            if (previewStage == null) {
                FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                        MainController.class.getResource("PdfPreviewView.fxml")));
                Parent root = loader.load();
                previewController = loader.getController();
                previewController.setOnSave(controller -> saveFromPreview(controller.pdfBytes()));

                previewStage = new Stage();
                previewStage.setTitle("Preview - QFT Demand Notice");
                Window owner = statusLabel.getScene() == null
                        ? null : statusLabel.getScene().getWindow();
                if (owner != null) {
                    previewStage.initOwner(owner);
                }
                previewStage.initModality(Modality.NONE);
                Scene scene = new Scene(root, PREVIEW_WIDTH, PREVIEW_HEIGHT);
                scene.getStylesheets().add(
                        Objects.requireNonNull(getClass().getResource("/styles.css"))
                                .toExternalForm());
                previewStage.setScene(scene);
                previewStage.setMinWidth(600);
                previewStage.setMinHeight(560);
                previewStage.setOnHidden(e -> {
                    if (previewController != null) {
                        previewController.closeDocument();
                    }
                });
            }

            previewController.showPdf(pdfBytes, tokenNote);
            // Saving only makes sense when the values actually reached the PDF.
            previewController.setSaveEnabled(saveEnabled);
            openAtFixedSize(previewStage);
            previewStage.show();
            previewStage.toFront();
            setStatus(statusNote, saveEnabled);
        } catch (IOException e) {
            showError("The preview could not be shown.\n\n" + e.getMessage());
        }
    }

    /**
     * Every open puts the preview at the same place and size: a fixed size
     * (clamped to the desktop work area so it always fits) centered on the
     * screen, instead of remembering the user's last resize/drag.
     */
    private static void openAtFixedSize(Stage previewStage) {
        Rectangle2D work = Screen.getPrimary().getVisualBounds();
        double width = Math.max(previewStage.getMinWidth(),
                Math.min(PREVIEW_WIDTH, work.getWidth() - 40));
        double height = Math.max(previewStage.getMinHeight(),
                Math.min(PREVIEW_HEIGHT, work.getHeight() - 40));
        previewStage.setX(work.getMinX() + (work.getWidth() - width) / 2);
        previewStage.setY(work.getMinY() + (work.getHeight() - height) / 2);
        previewStage.setWidth(width);
        previewStage.setHeight(height);
    }

    /** One-line note about which placeholders were filled in. */
    /**
     * Explains to the user how the values reached the PDF: which placeholders
     * were filled in, whether those were auto-inserted into a copy of the
     * .pub, and which fields need manual attention.
     */
    private String buildTokenNote(TemplateStamper.PreparedTemplate prepared,
                                  TemplateStamper.StampOutcome outcome) {
        if (prepared == null) {
            // Built-in layout: no template involved.
            return "Built-in layout - no template placeholders involved; every value "
                    + "below comes straight from the form.";
        }
        if (outcome.replacedTokens().isEmpty()) {
            String note = "No {{...}} placeholders are in this PDF, so the form values are "
                    + "NOT filled in - it was converted exactly as the .pub is.";
            if (prepared.wasAutoTokenized() && !prepared.notRecognized().isEmpty()) {
                note += "\nThe auto-filler searched for a date/amount pattern but could not "
                        + "identify: " + String.join(", ", prepared.notRecognized())
                        + "\nOpen the .pub in Publisher and type "
                        + "{{LETTER_DATE}}, {{PERIOD}}, {{AMOUNT}}, {{DEADLINE}} where those "
                        + "values should appear, then preview again.";
            } else {
                note += "\nAdd {{LETTER_DATE}}, {{PERIOD}}, {{AMOUNT}} and {{DEADLINE}} to the "
                        + ".pub to have them filled in.";
            }
            return note;
        }
        String note = "Values filled in: " + String.join(", ", outcome.replacedTokens());
        if (prepared.wasAutoTokenized() && !prepared.autoInserted().isEmpty()) {
            note += "\nThose placeholders were auto-inserted into a temporary COPY of the .pub "
                    + "(your original file was not modified). Exchange: "
                    + String.join(", ", prepared.autoInserted());
        }
        if (!outcome.unreplacedTokens().isEmpty()) {
            note += "\nNot found in the template: "
                    + String.join(", ", outcome.unreplacedTokens());
        }
        return note;
    }

    /**
     * One-line status for the main window. When a template yields fewer
     * replacements than placeholders, the missing fields are named right
     * here - the most likely cause is a .pub letter without the
     * {@code {{PERIOD}}} / {@code {{DEADLINE}}} text.
     */
    private String previewStatus(TemplateStamper.StampOutcome outcome) {
        if (outcome.replacedTokens().isEmpty()) {
            return "Preview ready, but the template has no {{...}} placeholders - nothing "
                    + "was filled in. Saving is disabled.";
        }
        String status = "Preview ready - " + outcome.replacedTokens().size()
                + " of " + TemplateStamper.TOKENS.size() + " fields filled in";
        if (!outcome.unreplacedTokens().isEmpty()) {
            status += "\nNOT found in this template: "
                    + String.join(", ", outcome.unreplacedTokens())
                    + " - the .pub is missing this text, so these values are not filled in";
        }
        return status + ". Check the values, then click Save PDF.";
    }

    /** Writes the previewed bytes to a user-chosen file (called by the preview). */
    private void saveFromPreview(byte[] pdfBytes) {
        Path out = askUserForSaveLocation(filenameForPreview());
        if (out == null) {
            setStatus("Save cancelled - the preview is unchanged.", false);
            return;
        }
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, pdfBytes);
        } catch (IOException e) {
            showError("The PDF could not be written.\n\n" + e.getMessage());
            return;
        }
        onGenerated(out, "PDF saved: " + out, false);
    }

    /** Suggested file name from the form as it currently stands. */
    private String filenameForPreview() {
        return collectData().map(this::filenameFor)
                .orElse("QFT Demand Notice.pdf");
    }

    @FXML
    private void handleGenerate() {
        Optional<DemandNoticeData> data = collectData();
        if (data.isEmpty()) {
            return; // collectData already showed the validation error
        }
        if (selectedTemplate == null) {
            Path out = askUserForSaveLocation(filenameFor(data.get()));
            if (out == null) {
                setStatus("Generation cancelled - no save location chosen.", false);
                return;
            }
            generateFromBuiltIn(data.get(), out);
            return;
        }
        // Template flow: always preview first; the Save dialog opens from there.
        prepareAndPreview(data.get());
    }

    /**
     * Shows a "Save Demand Notice As" dialog pre-filled with the suggested
     * filename (default: Documents\QFT Demand Notices). The user can rename
     * the file and choose any folder; the chosen folder is remembered for the
     * next generation. Returns null if the user cancels.
     */
    private Path askUserForSaveLocation(String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Demand Notice As");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
        chooser.setInitialFileName(suggestedName);

        // Start in the folder used last time, otherwise the default output folder
        // (or Documents if the default folder does not exist yet).
        File startDir = lastSaveDir;
        if (startDir == null || !startDir.isDirectory()) {
            startDir = Constants.defaultOutputDir().toFile();
        }
        if (!startDir.isDirectory()) {
            startDir = Paths.get(System.getProperty("user.home"), "Documents").toFile();
        }
        if (startDir.isDirectory()) {
            chooser.setInitialDirectory(startDir);
        }

        File target = chooser.showSaveDialog(statusLabel.getScene().getWindow());
        if (target == null) {
            return null;
        }
        Path out = target.toPath();
        if (!out.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            out = out.resolveSibling(out.getFileName().toString() + ".pdf");
        }
        return out;
    }

    /**
     * Built-in layout (no Publisher file selected). With {@code out} null the
     * PDF is generated in memory and shown in the preview window instead of
     * being written; the preview's Save button then writes it.
     */
    private void generateFromBuiltIn(DemandNoticeData data, Path out) {
        try {
            if (out == null) {
                // Generate once, preview it, and let the preview's Save button
                // write exactly the bytes that were shown.
                Path temp = Files.createTempFile("qft_builtin_preview_", ".pdf");
                byte[] bytes;
                try {
                    PdfGenerator.generate(data, temp);
                    bytes = Files.readAllBytes(temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
                showPreview(bytes,
                        "Built-in layout - no template placeholders involved; every value "
                                + "below comes straight from the form.",
                        "Preview ready (built-in layout). Check the values, then click "
                                + "Save PDF.", true);
                return;
            }
            PdfGenerator.generate(data, out);
            onGenerated(out, "PDF saved (built-in layout): " + out, true);
        } catch (IOException e) {
            showError("The PDF could not be written.\n\n" + e.getMessage());
        }
    }

    /**
     * Shared success handling: remember the file, and optionally offer to open it.
     *
     * @param promptToOpen false when the user just reviewed the PDF in the
     *                     preview window, in which case no extra prompt is needed
     */
    private void onGenerated(Path out, String statusMessage, boolean promptToOpen) {
        lastGeneratedPath = out;
        openPdfButton.setDisable(false);
        setStatus(statusMessage, true);
        if (out.getParent() != null && Files.isDirectory(out.getParent())) {
            lastSaveDir = out.getParent().toFile();
        }
        if (!promptToOpen) {
            return;
        }

        Optional<ButtonType> choice = new Alert(Alert.AlertType.INFORMATION,
                "Demand notice generated successfully.\n\n" + out + "\n\nOpen it now?",
                ButtonType.YES, ButtonType.NO)
                .showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.YES) {
            openFile(out);
        }
    }

    private void setBusy(boolean busy, String message) {
        generateButton.setDisable(busy);
        clearButton.setDisable(busy);
        browseTemplateButton.setDisable(busy);
        if (message != null) {
            setStatus(message, true);
        }
    }

    @FXML
    private void handleClear() {
        LocalDate today = LocalDate.now();
        selectDate(letterDayCombo, letterMonthCombo, letterYearSpinner, today);
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        contribMonthCombo.getSelectionModel().select(lastMonth.getMonth().ordinal());
        contribYearSpinner.getValueFactory().setValue(lastMonth.getYear());
        amountField.clear();
        updateAmountPreview("");
        selectDate(deadlineDayCombo, deadlineMonthCombo, deadlineYearSpinner, today.plusDays(14));
        setStatus(null, false);
    }

    @FXML
    private void handleOpenPdf() {
        if (lastGeneratedPath == null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select a demand notice PDF");
            chooser.setInitialDirectory(Constants.defaultOutputDir().toFile());
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
            File chosen = chooser.showOpenDialog(statusLabel.getScene().getWindow());
            if (chosen == null) {
                return;
            }
            lastGeneratedPath = chosen.toPath();
        }
        openFile(lastGeneratedPath);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Optional<DemandNoticeData> collectData() {
        LocalDate letterDate = readDate(letterDayCombo, letterMonthCombo, letterYearSpinner,
                "letter date");
        if (letterDate == null) {
            return Optional.empty();
        }
        LocalDate deadline = readDate(deadlineDayCombo, deadlineMonthCombo, deadlineYearSpinner,
                "payment deadline");
        if (deadline == null) {
            return Optional.empty();
        }

        String monthName = contribMonthCombo.getValue();
        if (monthName == null) {
            showError("Please select the contribution month.");
            return Optional.empty();
        }
        Month month = Month.valueOf(monthName.toUpperCase(java.util.Locale.ENGLISH));

        Optional<BigDecimal> amount = AmountFormatter.parse(amountField.getText());
        if (amount.isEmpty()) {
            showError("Please enter a valid amount.\n\nUse numbers only, greater than zero, "
                    + "with at most 2 decimal places (e.g. 1389.00).");
            return Optional.empty();
        }

        try {
            return Optional.of(new DemandNoticeData(
                    letterDate, YearMonth.of(contribYearSpinner.getValue(), month),
                    amount.get(), deadline));
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return Optional.empty();
        }
    }

    private LocalDate readDate(ComboBox<Integer> dayCombo, ComboBox<String> monthCombo,
                               Spinner<Integer> yearSpinner, String fieldLabel) {
        Integer day = dayCombo.getValue();
        String monthName = monthCombo.getValue();
        if (day == null || monthName == null || yearSpinner.getValue() == null) {
            showError("Please complete the " + fieldLabel + " (day, month and year).");
            return null;
        }
        Month month = Month.valueOf(monthName.toUpperCase(java.util.Locale.ENGLISH));
        try {
            return LocalDate.of(yearSpinner.getValue(), month, day);
        } catch (java.time.DateTimeException e) {
            showError("The " + fieldLabel + " is not a valid calendar date ("
                    + day + " " + monthName + " " + yearSpinner.getValue() + ").");
            return null;
        }
    }

    private void updateAmountPreview(String text) {
        AmountFormatter.parse(text)
                .ifPresentOrElse(
                        value -> amountPreviewLabel.setText(AmountFormatter.format(value)),
                        () -> amountPreviewLabel.setText(
                                text == null || text.isBlank() ? "" : "Invalid amount"));
    }

    private String filenameFor(DemandNoticeData data) {
        return Constants.FILENAME_TEMPLATE.formatted(
                DateFormatter.monthName(data.contributionMonth().getMonth()),
                data.contributionMonth().getYear());
    }

    private void selectDate(ComboBox<Integer> dayCombo, ComboBox<String> monthCombo,
                            Spinner<Integer> yearSpinner, LocalDate date) {
        dayCombo.getSelectionModel().select(date.getDayOfMonth() - 1);
        monthCombo.getSelectionModel().select(date.getMonth().ordinal());
        yearSpinner.getValueFactory().setValue(date.getYear());
    }

    private void setStatus(String message, boolean success) {
        statusLabel.setText(message == null ? "" : message);
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        if (message != null) {
            statusLabel.getStyleClass().add(success ? "status-success" : "status-error");
        }
    }

    private void showError(String message) {
        new Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait();
        setStatus(message, false);
    }

    private void openFile(Path path) {
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            } else {
                showError("Opening files is not supported on this system.");
            }
        } catch (IOException e) {
            showError("Could not open the file.\n\n" + e.getMessage());
        }
    }
}
