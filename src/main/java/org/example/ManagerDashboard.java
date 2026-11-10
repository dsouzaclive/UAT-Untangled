package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Stage;
import org.example.services.SupabaseService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ManagerDashboard {

    private final SupabaseService supabase = new SupabaseService();

    private final ListView<JsonNode> reportList = new ListView<>();

    private final VBox detailsContent = new VBox(14);

    private final TextArea notesArea = new TextArea();
    private final TextArea overrideArea = new TextArea();

    private final TextField ticketTitleField = new TextField();
    private final TextArea ticketDescriptionArea = new TextArea();
    private final TextArea ticketStepsArea = new TextArea();
    private final TextArea ticketExpectedArea = new TextArea();
    private final TextArea ticketActualArea = new TextArea();
    private final TextArea ticketCriteriaArea = new TextArea();

    private final ComboBox<String> ticketSeverityBox = new ComboBox<>(FXCollections.observableArrayList("low", "medium", "high", "critical"));

    private final Button copyTicketButton = new Button("Copy Ticket");
    private final Button saveTicketButton = new Button("Save Ticket Draft");
    private final Button retryAiButton = new Button("Retry AI Analysis");

    private final Button viewMatchingReportButton = new Button("View Matching Report");

    private final Label statusLabel = new Label("Loading feedback...");
    private final Label selectedLabel = new Label("Select a report");

    private final Label duplicateBanner = new Label("POSSIBLE DUPLICATE — verify the matching report before deciding.");

    private final ComboBox<String> severityBox = new ComboBox<>(FXCollections.observableArrayList("low", "medium", "high", "critical"));

    private final Button approveButton = new Button("Approve");
    private final Button rejectButton = new Button("Reject");
    private final Button duplicateButton = new Button("Mark Duplicate");

    private final Button requestInfoButton = new Button("Request More Information");

    private JsonNode selectedReport;

    private ScrollPane reviewScroll;

    // =========================================================
    // COLLAPSIBLE SECTION HELPER
    // =========================================================

    private VBox createCollapsibleSection(String title, Node content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("section-title");

        Label arrow = new Label("⌄");
        arrow.getStyleClass().add("collapse-arrow");

        HBox headerContent = new HBox(10, titleLabel, new Region(), arrow);

        headerContent.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerContent.getChildren().get(1), Priority.ALWAYS);

        Button headerButton = new Button();
        headerButton.setGraphic(headerContent);
        headerButton.setMaxWidth(Double.MAX_VALUE);
        headerButton.setAlignment(Pos.CENTER_LEFT);
        headerButton.getStyleClass().add("collapse-header");

        VBox section = new VBox(12, headerButton, content);

        section.getStyleClass().add("ticket-panel");
        section.setFillWidth(true);

        headerButton.setOnAction(e -> {
            boolean currentlyExpanded = content.isVisible();
            boolean expand = !currentlyExpanded;

            content.setVisible(expand);
            content.setManaged(expand);

            if (expand) {
                arrow.getStyleClass().remove("collapsed");
            } else {
                if (!arrow.getStyleClass().contains("collapsed")) {
                    arrow.getStyleClass().add("collapsed");
                }
            }
        });

        return section;
    }

    // =========================================================
    // MAIN DASHBOARD
    // =========================================================

    public void show(Stage stage) {
        Label heading = new Label("UAT, Untangled");
        heading.getStyleClass().add("page-title");

        Label subtitle = new Label("Manager Dashboard | AI-assisted feedback review");
        subtitle.getStyleClass().add("page-subtitle");

        Button backButton = new Button("Back to Home");
        backButton.getStyleClass().add("secondary-button");
        backButton.setOnAction(e -> new Main().showHome(stage));

        Button refreshButton = new Button("Refresh Queue");
        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(e -> loadQueue());

        retryAiButton.getStyleClass().add("primary-button");
        retryAiButton.setId("retryAiButton");
        retryAiButton.setDisable(true);
        retryAiButton.setOnAction(e -> retryAiAnalysis());

        HBox headerButtons = new HBox(10, backButton, refreshButton, retryAiButton);

        headerButtons.getStyleClass().add("toolbar");

        VBox header = new VBox(10, heading, subtitle, headerButtons);

        header.getStyleClass().add("dashboard-header");

        // =====================================================
        // REPORT QUEUE
        // =====================================================

        Label queueHeading = new Label("Feedback Queue");
        queueHeading.getStyleClass().add("section-title");

        Label queueDescription = new Label("Select a report to inspect its details and AI analysis.");

        queueDescription.getStyleClass().add("muted-text");
        queueDescription.setWrapText(true);

        reportList.getStyleClass().add("report-list");

        reportList.setPlaceholder(new Label("No feedback reports available."));

        reportList.setCellFactory(list -> new ListCell<>() {

            @Override
            protected void updateItem(JsonNode report, boolean empty) {
                super.updateItem(report, empty);

                if (empty || report == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                JsonNode managerDraft = report.path("manager_ticket_draft");

                String title = managerDraft.path("title").asText(report.path("title").asText("Untitled report"));

                String type = report.path("feedback_type").asText("unknown");

                boolean possibleDuplicate = report.path("ai_analysis").path("possible_duplicate").asBoolean(false);

                VBox content = new VBox(5);

                Label titleLabel = new Label(title);
                titleLabel.getStyleClass().add("report-list-title");
                titleLabel.setWrapText(true);

                Label typeLabel = new Label("Type: " + type);
                typeLabel.getStyleClass().add("muted-text");

                if (possibleDuplicate) {
                    Label duplicateLabel = new Label("POSSIBLE DUPLICATE");

                    duplicateLabel.getStyleClass().add("duplicate-warning");

                    content.getChildren().add(duplicateLabel);
                }

                content.getChildren().addAll(titleLabel, typeLabel);

                setText(null);
                setGraphic(content);
            }
        });

        reportList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            selectedReport = newValue;
            displayReport(newValue);
        });

        VBox queuePanel = new VBox(12, queueHeading, queueDescription, reportList);

        queuePanel.getStyleClass().add("queue-panel");
        VBox.setVgrow(reportList, Priority.ALWAYS);

        // =====================================================
        // REPORT DETAILS CONTENT
        // =====================================================

        detailsContent.setFillWidth(true);

        viewMatchingReportButton.getStyleClass().add("secondary-button");

        viewMatchingReportButton.setOnAction(e -> viewMatchingReport());

        VBox reportDetailsContent = new VBox(14, detailsContent, viewMatchingReportButton);

        VBox reportDetailsSection = createCollapsibleSection("REPORT DETAILS & AI ANALYSIS", reportDetailsContent);

        // =====================================================
        // TICKET EDITOR
        // =====================================================

        ticketTitleField.setPromptText("Ticket title");

        ticketDescriptionArea.setPromptText("Ticket description");
        ticketDescriptionArea.setWrapText(true);
        ticketDescriptionArea.setPrefRowCount(4);

        ticketStepsArea.setPromptText("Enter each reproduction step on a separate line");
        ticketStepsArea.setWrapText(true);
        ticketStepsArea.setPrefRowCount(4);

        ticketExpectedArea.setPromptText("Expected result");
        ticketExpectedArea.setWrapText(true);
        ticketExpectedArea.setPrefRowCount(3);

        ticketActualArea.setPromptText("Actual result");
        ticketActualArea.setWrapText(true);
        ticketActualArea.setPrefRowCount(3);

        ticketCriteriaArea.setPromptText("Enter each acceptance criterion on a separate line");
        ticketCriteriaArea.setWrapText(true);
        ticketCriteriaArea.setPrefRowCount(4);

        ticketSeverityBox.setPromptText("Ticket severity");
        ticketSeverityBox.setMaxWidth(Double.MAX_VALUE);

        for (Control control : List.of(ticketTitleField, ticketDescriptionArea, ticketStepsArea, ticketExpectedArea, ticketActualArea, ticketCriteriaArea)) {
            control.getStyleClass().add("form-control");
        }

        Label ticketExplanation = new Label("Review and edit this draft before sharing it with developers.");

        ticketExplanation.getStyleClass().add("muted-text");
        ticketExplanation.setWrapText(true);

        saveTicketButton.getStyleClass().add("primary-button");
        saveTicketButton.setOnAction(e -> saveTicketDraft());

        copyTicketButton.getStyleClass().add("secondary-button");
        copyTicketButton.setOnAction(e -> copyTicketToClipboard());

        HBox ticketButtons = new HBox(10, saveTicketButton, copyTicketButton);

        VBox ticketContent = new VBox(12, ticketExplanation, new Label("Ticket title"), ticketTitleField, new Label("Description"), ticketDescriptionArea, new Label("Steps to reproduce"), ticketStepsArea, new Label("Expected result"), ticketExpectedArea, new Label("Actual result"), ticketActualArea, new Label("Severity"), ticketSeverityBox, new Label("Acceptance criteria"), ticketCriteriaArea, ticketButtons);

        VBox ticketSection = createCollapsibleSection("AI-GENERATED DEVELOPER TICKET", ticketContent);

        // =====================================================
        // MANAGER DECISION BUTTONS
        // =====================================================

        approveButton.getStyleClass().add("approve-button");
        rejectButton.getStyleClass().add("danger-button");

        duplicateButton.getStyleClass().add("secondary-button");
        requestInfoButton.getStyleClass().add("secondary-button");

        for (Button button : List.of(approveButton, rejectButton, duplicateButton, requestInfoButton)) {
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinHeight(44);
            button.setPrefHeight(44);
            button.setMaxHeight(44);
        }

        approveButton.setOnAction(e -> makeDecision("approve"));
        rejectButton.setOnAction(e -> makeDecision("reject"));
        duplicateButton.setOnAction(e -> makeDecision("mark_duplicate"));
        requestInfoButton.setOnAction(e -> makeDecision("request_info"));

        severityBox.setPromptText("Final severity");
        severityBox.setMaxWidth(Double.MAX_VALUE);

        GridPane actions = new GridPane();
        actions.setHgap(12);
        actions.setVgap(12);

        actions.add(new Label("Final severity:"), 0, 0);
        actions.add(severityBox, 1, 0);

        actions.add(approveButton, 0, 1);
        actions.add(rejectButton, 1, 1);

        actions.add(duplicateButton, 0, 2);
        actions.add(requestInfoButton, 1, 2);

        ColumnConstraints firstColumn = new ColumnConstraints();
        ColumnConstraints secondColumn = new ColumnConstraints();

        firstColumn.setPercentWidth(50);
        secondColumn.setPercentWidth(50);

        firstColumn.setHgrow(Priority.ALWAYS);
        secondColumn.setHgrow(Priority.ALWAYS);
        firstColumn.setFillWidth(true);
        secondColumn.setFillWidth(true);

        actions.getColumnConstraints().addAll(firstColumn, secondColumn);

        GridPane.setHgrow(severityBox, Priority.ALWAYS);
        GridPane.setHgrow(approveButton, Priority.ALWAYS);
        GridPane.setHgrow(rejectButton, Priority.ALWAYS);
        GridPane.setHgrow(duplicateButton, Priority.ALWAYS);
        GridPane.setHgrow(requestInfoButton, Priority.ALWAYS);

        VBox decisionContent = new VBox(12, new Label("Final severity:"), severityBox, actions);

        VBox decisionSection = createCollapsibleSection("MANAGER DECISIONS", decisionContent);

        // =====================================================
        // MANAGER NOTES
        // =====================================================

        notesArea.setPromptText("Enter manager notes...");
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        notesArea.getStyleClass().add("form-control");

        VBox notesContent = new VBox(10, notesArea);

        VBox notesSection = createCollapsibleSection("MANAGER NOTES", notesContent);

        // =====================================================
        // SEVERITY OVERRIDE
        // =====================================================

        overrideArea.setPromptText("Explain why you are overriding the AI recommendation...");
        overrideArea.setPrefRowCount(3);
        overrideArea.setWrapText(true);
        overrideArea.getStyleClass().add("form-control");

        VBox overrideContent = new VBox(10, overrideArea);

        VBox overrideSection = createCollapsibleSection("SEVERITY OVERRIDE REASON", overrideContent);

        // =====================================================
        // WARNINGS AND STATUS
        // =====================================================

        duplicateBanner.getStyleClass().add("duplicate-banner");
        duplicateBanner.setWrapText(true);
        duplicateBanner.setVisible(false);
        duplicateBanner.setManaged(false);

        selectedLabel.getStyleClass().add("section-title");

        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(55);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        statusLabel.setStyle("-fx-background-color: #30343A;" + "-fx-border-color: #4A515A;" + "-fx-border-radius: 8;" + "-fx-background-radius: 8;" + "-fx-padding: 12;");

        // =====================================================
        // REVIEW PANEL
        // =====================================================

        VBox reviewPanel = new VBox(16, selectedLabel, statusLabel, duplicateBanner, reportDetailsSection, ticketSection, notesSection, overrideSection, decisionSection);

        reviewPanel.getStyleClass().add("review-panel");
        reviewPanel.setPadding(new Insets(20));

        // =====================================================
        // SCROLLABLE REVIEW AREA
        // =====================================================

        reviewScroll = new ScrollPane(reviewPanel);
        reviewScroll.setFitToWidth(true);

        reviewScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        reviewScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        reviewScroll.getStyleClass().add("app-scroll");

        // =====================================================
        // DASHBOARD LAYOUT
        // =====================================================

        SplitPane splitPane = new SplitPane(queuePanel, reviewScroll);

        splitPane.getStyleClass().add("dashboard-split");
        splitPane.setDividerPositions(0.30);

        VBox.setVgrow(splitPane, Priority.ALWAYS);

        VBox root = new VBox(16, header, splitPane);

        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(20));

        Main.displayScreen(stage, root, "UAT, Untangled - Manager Dashboard");

        loadQueue();
    }

    // =========================================================
    // REPORT DETAIL UI HELPERS
    // =========================================================

    private VBox createDetailSection(String heading, String value) {
        Label headingLabel = new Label(heading.toUpperCase());
        headingLabel.setStyle("-fx-text-fill: #D8CBB5;" + "-fx-font-size: 13px;" + "-fx-font-weight: bold;");

        Label valueLabel = new Label(value == null || value.isBlank() ? "Not provided" : value);

        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);

        valueLabel.setStyle("-fx-text-fill: #E8DFD0;" + "-fx-font-size: 14px;" + "-fx-line-spacing: 3px;");

        VBox section = new VBox(8, headingLabel, valueLabel);

        section.setPadding(new Insets(14));

        section.setStyle("-fx-background-color: #202327;" + "-fx-border-color: #454B53;" + "-fx-border-radius: 9;" + "-fx-background-radius: 9;");

        return section;
    }

    private VBox createMetadataCard(String heading, String value) {
        Label headingLabel = new Label(heading.toUpperCase());

        headingLabel.setStyle("-fx-text-fill: #AFA18C;" + "-fx-font-size: 11px;" + "-fx-font-weight: bold;");

        Label valueLabel = new Label(value == null || value.isBlank() ? "Not provided" : value);

        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);

        valueLabel.setStyle("-fx-text-fill: #E8DFD0;" + "-fx-font-size: 13px;" + "-fx-font-weight: bold;");

        VBox card = new VBox(7, headingLabel, valueLabel);

        card.setPadding(new Insets(12));

        card.setStyle("-fx-background-color: #30343A;" + "-fx-border-color: #4A515A;" + "-fx-border-radius: 8;" + "-fx-background-radius: 8;");

        return card;
    }

    // =========================================================
    // NEW: AI SUMMARY PANEL
    // =========================================================

    private HBox createAiSummaryPanel(JsonNode ai) {
        // Classification
        VBox classificationBox = createSummaryMetric("AI CLASSIFICATION", ai.path("classification").asText("Not available"));

        // Suggested severity
        VBox severitySummaryBox = createSummaryMetric("SUGGESTED SEVERITY", ai.path("suggested_severity").asText("Not available"));

        // Confidence value
        double confidence = getConfidencePercentage(ai.path("confidence"));

        // Circular confidence indicator
        StackPane confidenceRing = createConfidenceRing(confidence);

        Label confidenceHeading = new Label("AI CONFIDENCE");

        confidenceHeading.setStyle("-fx-text-fill: #D8CBB5;" + "-fx-font-size: 13px;" + "-fx-font-weight: bold;");

        VBox confidenceTextBox = new VBox(8, confidenceHeading);

        confidenceTextBox.setAlignment(Pos.CENTER_LEFT);

        HBox confidenceBox = new HBox(16, confidenceRing, confidenceTextBox);

        confidenceBox.setAlignment(Pos.CENTER_LEFT);
        confidenceBox.setPadding(new Insets(12));

        // Vertical separators
        Separator separator1 = new Separator();
        separator1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator1.setStyle("-fx-background-color: #454B53;");
        separator1.setPrefHeight(85);

        Separator separator2 = new Separator();
        separator2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator2.setStyle("-fx-background-color: #454B53;");
        separator2.setPrefHeight(85);

        // Main horizontal panel
        HBox summaryPanel = new HBox(12, classificationBox, separator1, severitySummaryBox, separator2, confidenceBox);

        summaryPanel.setAlignment(Pos.CENTER_LEFT);
        summaryPanel.setPadding(new Insets(12));

        summaryPanel.setMinHeight(125);
        summaryPanel.setMaxWidth(Double.MAX_VALUE);

        HBox.setHgrow(classificationBox, Priority.ALWAYS);
        HBox.setHgrow(severitySummaryBox, Priority.ALWAYS);
        HBox.setHgrow(confidenceBox, Priority.ALWAYS);

        classificationBox.setMaxWidth(Double.MAX_VALUE);
        severitySummaryBox.setMaxWidth(Double.MAX_VALUE);
        confidenceBox.setMaxWidth(Double.MAX_VALUE);

        summaryPanel.setStyle("-fx-background-color: #202327;" + "-fx-border-color: #454B53;" + "-fx-border-radius: 10;" + "-fx-background-radius: 10;");

        return summaryPanel;
    }

    // =========================================================
    // NEW: SUMMARY METRIC
    // =========================================================

    private VBox createSummaryMetric(String heading, String value) {
        Label headingLabel = new Label(heading);

        headingLabel.setStyle("-fx-text-fill: #D8CBB5;" + "-fx-font-size: 13px;" + "-fx-font-weight: bold;");

        Label valueLabel = new Label(value == null || value.isBlank() ? "Not available" : value);

        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);

        valueLabel.setStyle("-fx-text-fill: #F0E8DC;" + "-fx-font-size: 17px;");

        VBox box = new VBox(12, headingLabel, valueLabel);

        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(12));

        return box;
    }

    // =========================================================
    // NEW: CONFIDENCE PERCENTAGE CONVERSION
    // =========================================================

    private double getConfidencePercentage(JsonNode confidenceNode) {
        if (confidenceNode == null || confidenceNode.isMissingNode() || confidenceNode.isNull()) {
            return 0;
        }

        try {
            String value = confidenceNode.asText().replace("%", "").trim();

            double percentage = Double.parseDouble(value);

            // Supports both 0.15 and 15.0 formats.
            if (percentage >= 0 && percentage <= 1) {
                percentage *= 100;
            }

            return Math.max(0, Math.min(100, percentage));

        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // =========================================================
    // NEW: CIRCULAR CONFIDENCE RING
    // =========================================================

    private StackPane createConfidenceRing(double confidence) {
        final double size = 84;
        final double centre = size / 2;
        final double radius = 33;

        // Background ring
        Circle backgroundRing = new Circle(centre, centre, radius);

        backgroundRing.setFill(Color.TRANSPARENT);
        backgroundRing.setStroke(Color.web("#454B53"));
        backgroundRing.setStrokeWidth(8);

        // Electric-blue progress arc
        Arc progressArc = new Arc(centre, centre, radius, radius, 90, -(confidence * 3.6));

        progressArc.setType(ArcType.OPEN);
        progressArc.setFill(Color.TRANSPARENT);
        progressArc.setStroke(Color.web("#287BFF"));
        progressArc.setStrokeWidth(8);
        progressArc.setStrokeLineCap(StrokeLineCap.ROUND);

        Pane ringDrawing = new Pane(backgroundRing, progressArc);

        ringDrawing.setPrefSize(size, size);
        ringDrawing.setMinSize(size, size);
        ringDrawing.setMaxSize(size, size);

        // Percentage displayed in the centre
        Label percentageLabel = new Label(String.format(Locale.US, "%.1f%%", confidence));

        percentageLabel.setStyle("-fx-text-fill: #F0E8DC;" + "-fx-font-size: 17px;" + "-fx-font-weight: bold;");

        StackPane ring = new StackPane(ringDrawing, percentageLabel);

        ring.setAlignment(Pos.CENTER);
        ring.setMinSize(size, size);
        ring.setPrefSize(size, size);
        ring.setMaxSize(size, size);

        return ring;
    }

    // =========================================================
    // DISPLAY STRUCTURED REPORT
    // =========================================================

    private void displayStructuredReport(JsonNode report, JsonNode ai) {
        detailsContent.getChildren().clear();

        GridPane metadataGrid = new GridPane();

        metadataGrid.setHgap(10);
        metadataGrid.setVgap(10);

        ColumnConstraints metadataColumn1 = new ColumnConstraints();
        ColumnConstraints metadataColumn2 = new ColumnConstraints();

        metadataColumn1.setPercentWidth(50);
        metadataColumn2.setPercentWidth(50);

        metadataColumn1.setHgrow(Priority.ALWAYS);
        metadataColumn2.setHgrow(Priority.ALWAYS);

        metadataGrid.getColumnConstraints().addAll(metadataColumn1, metadataColumn2);

        metadataGrid.add(createMetadataCard("Report ID", report.path("id").asText("Not provided")), 0, 0);

        metadataGrid.add(createMetadataCard("Tester", report.path("tester_name").asText("Not provided")), 1, 0);

        metadataGrid.add(createMetadataCard("Type", report.path("feedback_type").asText("Not provided")), 0, 1);

        metadataGrid.add(createMetadataCard("Title", report.path("title").asText("Not provided")), 1, 1);

        detailsContent.getChildren().add(metadataGrid);

        detailsContent.getChildren().add(createDetailSection("Description", report.path("description").asText("Not provided")));

        JsonNode steps = report.path("reproduction_steps");

        StringBuilder stepsText = new StringBuilder();

        if (steps.isArray() && !steps.isEmpty()) {
            int number = 1;

            for (JsonNode step : steps) {
                stepsText.append(number++).append(". ").append(step.asText()).append("\n");
            }

        } else {
            stepsText.append("Not provided");
        }

        detailsContent.getChildren().add(createDetailSection("Steps to Reproduce", stepsText.toString().trim()));

        detailsContent.getChildren().add(createDetailSection("Expected Result", report.path("expected_result").asText("Not provided")));

        detailsContent.getChildren().add(createDetailSection("Actual Result", report.path("actual_result").asText("Not provided")));

        detailsContent.getChildren().add(createDetailSection("Testing Environment", report.path("environment").asText("Not provided")));

        if (!ai.isObject()) {
            detailsContent.getChildren().add(createDetailSection("AI Analysis", "AI analysis is currently unavailable.\n\n" + "This report is still available for manual review. " + "No AI classification or severity recommendation " + "is currently available."));

            return;
        }

        // NEW HORIZONTAL AI SUMMARY PANEL
        detailsContent.getChildren().add(createAiSummaryPanel(ai));

        // Remaining AI details stay underneath
        detailsContent.getChildren().add(createDetailSection("AI Summary", ai.path("summary").asText("Not available")));

        detailsContent.getChildren().add(createDetailSection("AI Reasoning", ai.path("reasoning").asText("Not available")));

        detailsContent.getChildren().add(createDetailSection("Clarification Question", ai.path("clarification_question").asText("None suggested")));

        boolean possibleDuplicate = ai.path("possible_duplicate").asBoolean(false);

        detailsContent.getChildren().add(createDetailSection("Possible Duplicate", possibleDuplicate ? "Yes" : "No"));

        if (possibleDuplicate) {
            String duplicateId = ai.path("duplicate_candidate_id").asText();

            if (duplicateId.isBlank()) {
                duplicateId = ai.path("duplicate_of").asText();
            }

            detailsContent.getChildren().add(createDetailSection("Matching Report ID", duplicateId.isBlank() ? "Not provided" : duplicateId));

            detailsContent.getChildren().add(createDetailSection("Duplicate Reason", ai.path("duplicate_reason").asText("Not provided")));
        }
    }

    // =========================================================
    // LOAD QUEUE
    // =========================================================

    private void loadQueue() {
        statusLabel.setText("Loading feedback queue...");

        new Thread(() -> {
            try {
                ArrayNode reports = supabase.getManagerQueue();

                Platform.runLater(() -> {
                    selectedReport = null;
                    retryAiButton.setDisable(true);

                    selectedLabel.setText("Select a report");

                    detailsContent.getChildren().clear();

                    duplicateBanner.setVisible(false);
                    duplicateBanner.setManaged(false);

                    reportList.getItems().clear();

                    for (JsonNode report : reports) {
                        reportList.getItems().add(report);
                    }

                    statusLabel.setText(reportList.getItems().size() + " report(s) ready for review.");
                });

            } catch (Exception ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load queue: " + ex.getMessage()));
            }
        }, "manager-queue-loader").start();
    }

    // =========================================================
    // DISPLAY REPORT
    // =========================================================

    private void displayReport(JsonNode report) {
        if (report == null) {
            return;
        }

        JsonNode ai = report.path("ai_analysis");

        boolean hasAi = ai.isObject();

        boolean possibleDuplicate = hasAi && ai.path("possible_duplicate").asBoolean(false);

        duplicateBanner.setVisible(possibleDuplicate);
        duplicateBanner.setManaged(possibleDuplicate);

        retryAiButton.setDisable(hasAi);

        String duplicateCandidateId = ai.path("duplicate_candidate_id").asText();

        if (duplicateCandidateId.isBlank()) {
            duplicateCandidateId = ai.path("duplicate_of").asText();
        }

        boolean matchingReportAvailable = false;

        for (JsonNode candidate : reportList.getItems()) {
            if (!duplicateCandidateId.isBlank() && candidate.path("id").asText().equals(duplicateCandidateId)) {
                matchingReportAvailable = true;
                break;
            }
        }

        viewMatchingReportButton.setDisable(!matchingReportAvailable);

        selectedLabel.setText("Reviewing: " + report.path("title").asText("Untitled report"));

        displayStructuredReport(report, ai);

        severityBox.setValue(hasAi ? ai.path("suggested_severity").asText(null) : null);

        JsonNode savedDraft = report.path("manager_ticket_draft");

        if (savedDraft.isObject()) {
            populateTicketDraft(savedDraft);
        } else if (hasAi) {
            populateTicketDraft(ai.path("ticket_draft"));
        } else {
            clearTicketDraft();
        }

        statusLabel.setText(hasAi ? "Report loaded. Review the AI recommendation." : "AI analysis unavailable. Manual review is available.");

        notesArea.clear();
        overrideArea.clear();
    }

    // =========================================================
    // VIEW MATCHING REPORT
    // =========================================================

    private void viewMatchingReport() {
        if (selectedReport == null) {
            statusLabel.setText("Please select a report first.");
            return;
        }

        JsonNode ai = selectedReport.path("ai_analysis");

        String duplicateId = ai.path("duplicate_candidate_id").asText();

        if (duplicateId.isBlank()) {
            duplicateId = ai.path("duplicate_of").asText();
        }

        if (duplicateId.isBlank()) {
            statusLabel.setText("No matching report ID is available.");
            return;
        }

        for (int i = 0; i < reportList.getItems().size(); i++) {
            JsonNode candidate = reportList.getItems().get(i);

            if (candidate.path("id").asText().equals(duplicateId)) {
                reportList.getSelectionModel().select(i);
                reportList.scrollTo(i);

                statusLabel.setText("Opened the matching report.");
                return;
            }
        }

        statusLabel.setText("The matching report is not currently in the review queue.");
    }

    // =========================================================
    // POPULATE TICKET DRAFT
    // =========================================================

    private void populateTicketDraft(JsonNode draft) {
        if (draft == null || !draft.isObject()) {
            clearTicketDraft();
            return;
        }

        ticketTitleField.setText(draft.path("title").asText(""));

        ticketDescriptionArea.setText(draft.path("description").asText(""));

        ticketStepsArea.setText(arrayToLines(draft.path("steps_to_reproduce")));

        ticketExpectedArea.setText(draft.path("expected_result").asText(""));

        ticketActualArea.setText(draft.path("actual_result").asText(""));

        ticketCriteriaArea.setText(arrayToLines(draft.path("acceptance_criteria")));

        ticketSeverityBox.setValue(draft.path("severity").asText("medium"));
    }

    private String arrayToLines(JsonNode array) {
        if (!array.isArray()) {
            return "";
        }

        List<String> values = new ArrayList<>();

        for (JsonNode item : array) {
            values.add(item.asText());
        }

        return String.join("\n", values);
    }

    private void clearTicketDraft() {
        ticketTitleField.clear();
        ticketDescriptionArea.clear();
        ticketStepsArea.clear();
        ticketExpectedArea.clear();
        ticketActualArea.clear();
        ticketCriteriaArea.clear();
        ticketSeverityBox.setValue(null);
    }

    private String numberedLines(String text) {
        StringBuilder result = new StringBuilder();

        int number = 1;

        for (String line : text.split("\\R")) {
            String value = line.trim();

            if (!value.isEmpty()) {
                result.append(number++).append(". ").append(value).append("\n");
            }
        }

        return result.toString();
    }

    // =========================================================
    // SAVE TICKET
    // =========================================================

    private void saveTicketDraft() {
        if (selectedReport == null) {
            statusLabel.setText("Please select a report first.");
            return;
        }

        String reportId = selectedReport.path("id").asText();

        String title = ticketTitleField.getText().trim();
        String description = ticketDescriptionArea.getText();
        String steps = ticketStepsArea.getText();
        String expected = ticketExpectedArea.getText();
        String actual = ticketActualArea.getText();
        String criteria = ticketCriteriaArea.getText();
        String severity = ticketSeverityBox.getValue();

        if (title.isBlank()) {
            statusLabel.setText("Please enter a ticket title.");
            return;
        }

        if (severity == null || severity.isBlank()) {
            statusLabel.setText("Please select ticket severity.");
            return;
        }

        saveTicketButton.setDisable(true);
        copyTicketButton.setDisable(true);

        statusLabel.setText("Saving ticket draft to Supabase...");

        new Thread(() -> {
            try {
                supabase.saveManagerTicketDraft(reportId, title, description, steps, expected, actual, severity, criteria);

                Platform.runLater(() -> {
                    statusLabel.setText("Ticket draft saved successfully to Supabase.");

                    saveTicketButton.setDisable(false);
                    copyTicketButton.setDisable(false);
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Could not save ticket draft: " + ex.getMessage());

                    saveTicketButton.setDisable(false);
                    copyTicketButton.setDisable(false);
                });
            }
        }, "ticket-draft-saver").start();
    }

    // =========================================================
    // COPY TICKET
    // =========================================================

    private void copyTicketToClipboard() {
        if (selectedReport == null) {
            statusLabel.setText("Please select a report first.");
            return;
        }

        String title = ticketTitleField.getText().trim();
        String severity = ticketSeverityBox.getValue();

        if (title.isBlank()) {
            statusLabel.setText("Please enter a ticket title.");
            return;
        }

        if (severity == null || severity.isBlank()) {
            statusLabel.setText("Please select ticket severity.");
            return;
        }

        StringBuilder ticket = new StringBuilder();

        ticket.append("DEVELOPER TICKET\n");
        ticket.append("==============================\n\n");

        ticket.append("Report ID: ").append(selectedReport.path("id").asText()).append("\n\n");

        ticket.append("TITLE\n").append(title).append("\n\n");

        ticket.append("DESCRIPTION\n").append(ticketDescriptionArea.getText().trim()).append("\n\n");

        ticket.append("STEPS TO REPRODUCE\n");

        String steps = numberedLines(ticketStepsArea.getText());

        ticket.append(steps.isBlank() ? "Not provided\n" : steps);

        ticket.append("\nEXPECTED RESULT\n").append(ticketExpectedArea.getText().trim()).append("\n\n");

        ticket.append("ACTUAL RESULT\n").append(ticketActualArea.getText().trim()).append("\n\n");

        ticket.append("SEVERITY\n").append(severity).append("\n\n");

        ticket.append("ACCEPTANCE CRITERIA\n");

        String criteria = numberedLines(ticketCriteriaArea.getText());

        ticket.append(criteria.isBlank() ? "Not provided\n" : criteria);

        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();

        content.putString(ticket.toString());

        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);

        statusLabel.setText("Ticket copied to clipboard. Review it before sharing.");
    }

    // =========================================================
    // MANAGER DECISIONS
    // =========================================================

    private void makeDecision(String decision) {
        if (selectedReport == null) {
            statusLabel.setText("Please select a report first.");
            return;
        }

        String severity = severityBox.getValue();

        if (decision.equals("approve") && (severity == null || severity.isBlank())) {
            statusLabel.setText("Please select a final severity.");
            return;
        }

        if (decision.equals("mark_duplicate") && !confirmAction("Mark this report as a duplicate?")) {
            return;
        }

        if (decision.equals("reject") && !confirmAction("Reject this feedback report?")) {
            return;
        }

        String reportId = selectedReport.path("id").asText();

        JsonNode ai = selectedReport.path("ai_analysis");

        String suggestedSeverity = ai.path("suggested_severity").asText("");

        String overrideReason = overrideArea.getText().trim();

        if (!suggestedSeverity.isBlank() && severity != null && !severity.equalsIgnoreCase(suggestedSeverity) && overrideReason.isBlank()) {
            statusLabel.setText("Please explain the severity override.");
            return;
        }

        String managerNotes = notesArea.getText();

        approveButton.setDisable(true);
        rejectButton.setDisable(true);
        duplicateButton.setDisable(true);
        requestInfoButton.setDisable(true);

        statusLabel.setText("Saving manager decision...");

        new Thread(() -> {
            try {
                supabase.recordDecision(reportId, "Manager", decision, severity, overrideReason, managerNotes);

                Platform.runLater(() -> {
                    statusLabel.setText("Decision saved successfully.");

                    approveButton.setDisable(false);
                    rejectButton.setDisable(false);
                    duplicateButton.setDisable(false);
                    requestInfoButton.setDisable(false);

                    selectedReport = null;

                    detailsContent.getChildren().clear();
                    selectedLabel.setText("Select a report");

                    clearTicketDraft();

                    loadQueue();
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Could not save decision: " + ex.getMessage());

                    approveButton.setDisable(false);
                    rejectButton.setDisable(false);
                    duplicateButton.setDisable(false);
                    requestInfoButton.setDisable(false);
                });
            }
        }, "manager-decision-saver").start();
    }

    // =========================================================
    // RETRY AI ANALYSIS
    // =========================================================

    private void retryAiAnalysis() {
        if (selectedReport == null) {
            statusLabel.setText("Please select a report first.");
            return;
        }

        String reportId = selectedReport.path("id").asText();

        retryAiButton.setDisable(true);

        statusLabel.setText("AI ANALYSIS IN PROGRESS — Gemini is analysing this report.");

        if (reviewScroll != null) {
            reviewScroll.setVvalue(0);
        }

        new Thread(() -> {
            try {
                String interactionId = supabase.startAiAnalysis(reportId);

                boolean completed = false;

                for (int attempt = 0; attempt < 60; attempt++) {
                    String response = supabase.checkAiAnalysis(interactionId);

                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode result = mapper.readTree(response);

                    String analysisStatus = result.path("status").asText("");

                    if ("completed".equalsIgnoreCase(analysisStatus)) {
                        completed = true;
                        break;
                    }

                    if ("failed".equalsIgnoreCase(analysisStatus)) {
                        throw new Exception(result.path("error").asText("AI analysis failed."));
                    }

                    final int progress = attempt + 1;

                    Platform.runLater(() -> {
                        statusLabel.setText("AI ANALYSIS IN PROGRESS — " + "Checking progress: " + progress + "/60");

                        if (reviewScroll != null) {
                            reviewScroll.setVvalue(0);
                        }
                    });

                    Thread.sleep(3000);
                }

                if (!completed) {
                    throw new Exception("Analysis is taking longer than expected. " + "Refresh the queue later.");
                }

                Platform.runLater(() -> {
                    statusLabel.setText("AI ANALYSIS COMPLETED SUCCESSFULLY.");

                    loadQueue();
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("AI ANALYSIS FAILED: " + ex.getMessage());

                    retryAiButton.setDisable(false);

                    if (reviewScroll != null) {
                        reviewScroll.setVvalue(0);
                    }
                });
            }
        }, "ai-analysis-retry").start();
    }

    // =========================================================
    // CONFIRMATION
    // =========================================================

    private boolean confirmAction(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);

        alert.setHeaderText("Confirm manager decision");

        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }
}