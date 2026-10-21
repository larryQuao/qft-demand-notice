package com.qft.demandnotice.ui;

import com.qft.demandnotice.model.DemandNoticeData;
import com.qft.demandnotice.service.AmountFormatter;
import com.qft.demandnotice.service.DateFormatter;
import com.qft.demandnotice.service.PdfGenerator;
import com.qft.demandnotice.service.PubToPdfConverter;
import com.qft.demandnotice.service.TemplateStamper;
import com.qft.demandnotice.util.Constants;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Optional;

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
    @FXML private Button generateButton;
    @FXML private Button clearButton;
    @FXML private Button openPdfButton;
    @FXML private Label statusLabel;

    private static final int YEAR_MIN = 2020;
    private static final int YEAR_MAX = 2040;

    private Path lastGeneratedPath;
    private Path selectedTemplate;
    private File lastSaveDir;

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
        setStatus("Template selected. The four fields below will be stamped into it.", true);
    }

    @FXML
    private void handleClearTemplate() {
        selectedTemplate = null;
        templatePathField.clear();
        clearTemplateButton.setDisable(true);
        setStatus(null, false);
    }

    @FXML
    private void handleGenerate() {
        Optional<DemandNoticeData> data = collectData();
        if (data.isEmpty()) {
            return; // collectData already showed the validation error
        }

        Path out = askUserForSaveLocation(filenameFor(data.get()));
        if (out == null) {
            setStatus("Generation cancelled - no save location chosen.", false);
            return;
        }

        if (selectedTemplate != null) {
            generateFromTemplate(data.get(), out);
        } else {
            generateFromBuiltIn(data.get(), out);
        }
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

    /** Built-in layout (no Publisher file selected): generate directly with PDFBox. */
    private void generateFromBuiltIn(DemandNoticeData data, Path out) {
        try {
            PdfGenerator.generate(data, out);
            onGenerated(out, "PDF saved (built-in layout): " + out);
        } catch (IOException e) {
            showError("The PDF could not be written.\n\n" + e.getMessage());
        }
    }

    /**
     * Publisher template flow: convert the .pub to PDF with Microsoft Publisher,
     * then stamp the token placeholders with the form values.
     */
    private void generateFromTemplate(DemandNoticeData data, Path out) {
        setBusy(true, "Converting template with Microsoft Publisher, please wait...");

        Task<TemplateStamper.StampOutcome> task = new Task<>() {
            @Override
            protected TemplateStamper.StampOutcome call() throws Exception {
                Path converted = Files.createTempFile("qft_template_", ".pdf");
                try {
                    PubToPdfConverter.convert(selectedTemplate, converted);
                    return TemplateStamper.stamp(Files.readAllBytes(converted), data);
                } finally {
                    Files.deleteIfExists(converted);
                }
            }
        };

        task.setOnSucceeded(e -> {
            setBusy(false, null);
            try {
                Files.createDirectories(out.getParent());
                Files.write(out, task.getValue().pdfBytes());
            } catch (IOException ex) {
                showError("The PDF could not be written.\n\n" + ex.getMessage());
                return;
            }
            String tokenNote = task.getValue().replacedTokens().isEmpty()
                    ? "no {{...}} tokens found - converted as-is"
                    : "replaced " + String.join(", ", task.getValue().replacedTokens());
            onGenerated(out, "PDF saved (Publisher template, " + tokenNote + "): " + out);
        });

        task.setOnFailed(e -> {
            setBusy(false, null);
            showError("Template conversion failed.\n\n" + task.getException().getMessage());
        });

        new Thread(task, "pub-template-converter").start();
    }

    /** Shared success handling: remember the file, offer to open it. */
    private void onGenerated(Path out, String statusMessage) {
        lastGeneratedPath = out;
        openPdfButton.setDisable(false);
        setStatus(statusMessage, true);
        if (out.getParent() != null && Files.isDirectory(out.getParent())) {
            lastSaveDir = out.getParent().toFile();
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
