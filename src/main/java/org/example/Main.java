package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.Parent;
import javafx.stage.Stage;
import org.example.services.SupabaseService;

public class Main extends Application {

    public static final double WINDOW_WIDTH = 1200;
    public static final double WINDOW_HEIGHT = 800;

    private final SupabaseService service = new SupabaseService();

    @Override
    public void start(Stage stage) {
        showRoleSelection(stage);
    }

    public void showHome(Stage stage) {
        showRoleSelection(stage);
    }

    // =========================================================
    // REUSABLE SCENE
    // =========================================================

    public static void displayScreen(Stage stage, Parent root, String title) {
        if (stage.getScene() == null) {
            Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
            scene.getStylesheets().add(Main.class.getResource("/css/style.css").toExternalForm());
            stage.setMinWidth(650);
            stage.setMinHeight(550);
            stage.setScene(scene);
        } else {
            stage.getScene().setRoot(root);
        }

        stage.setTitle(title);

        if (!stage.isShowing()) {
            stage.show();
        }
    }

    private void showRoleSelection(Stage stage) {
        Label heading = new Label("UAT, Untangled");
        heading.getStyleClass().add("page-title");

        Label subtitle = new Label("AI-assisted User Acceptance Testing");
        subtitle.getStyleClass().add("page-subtitle");

        Label instruction = new Label("Choose how you want to access the application.");
        instruction.getStyleClass().add("muted-text");

        Button testerButton = new Button("Tester Portal");
        Button managerButton = new Button("Manager Dashboard");

        testerButton.getStyleClass().add("primary-button");
        managerButton.getStyleClass().add("primary-button");

        testerButton.setMaxWidth(Double.MAX_VALUE);
        managerButton.setMaxWidth(Double.MAX_VALUE);

        testerButton.setOnAction(e -> showTester(stage));
        managerButton.setOnAction(e -> new ManagerDashboard().show(stage));

        VBox root = new VBox(20, heading, subtitle, instruction, testerButton, managerButton);

        root.getStyleClass().add("page-card");
        root.setPadding(new Insets(40));
        root.setMaxWidth(480);

        StackPane screen = new StackPane(root);
        screen.getStyleClass().add("app-root");

        displayScreen(stage, screen, "UAT, Untangled");
    }

    private void showTester(Stage stage) {
        Label heading = new Label("UAT, Untangled");
        heading.getStyleClass().add("page-title");

        Label subtitle = new Label("Submit UAT Feedback");
        subtitle.getStyleClass().add("page-subtitle");

        Label introduction = new Label("Report a bug or share an idea. Include as much useful detail as possible to help the review team.");
        introduction.getStyleClass().add("muted-text");
        introduction.setWrapText(true);

        Button backButton = new Button("Back to Home");
        backButton.getStyleClass().add("secondary-button");
        backButton.setOnAction(e -> showRoleSelection(stage));

        TextField testerName = new TextField();
        testerName.setPromptText("Your name");

        TextField issueTitle = new TextField();
        issueTitle.setPromptText("Issue title");

        TextArea description = new TextArea();
        description.setPromptText("Describe what happened...");
        description.setPrefRowCount(5);
        description.setWrapText(true);

        TextArea reproductionSteps = new TextArea();
        reproductionSteps.setPromptText("Enter each step on a separate line.\n\n"
                                            + "Example:\n"
                                                + "1. Open the application.\n"
                                                + "2. Navigate to the login screen.\n"
                                                + "3. Enter valid credentials.\n"
                                                + "4. Click Sign In.");
        reproductionSteps.setPrefRowCount(5);
        reproductionSteps.setWrapText(true);

        TextArea expectedResult = new TextArea();
        expectedResult.setPromptText("What should have happened?");
        expectedResult.setPrefRowCount(3);
        expectedResult.setWrapText(true);

        TextArea actualResult = new TextArea();
        actualResult.setPromptText("What happened instead?");
        actualResult.setPrefRowCount(3);
        actualResult.setWrapText(true);

        TextArea environment = new TextArea();
        environment.setPromptText("Optional: Browser, operating system, device, application version, etc.");
        environment.setPrefRowCount(2);
        environment.setWrapText(true);

        ComboBox<String> feedbackType = new ComboBox<>();
        feedbackType.getItems().addAll("bug", "idea");
        feedbackType.setValue("bug");
        feedbackType.setMaxWidth(Double.MAX_VALUE);

        Button submitButton = new Button("Submit Feedback");
        submitButton.getStyleClass().add("primary-button");
        submitButton.setId("submitButton");
        submitButton.setMaxWidth(Double.MAX_VALUE);

        Label status = new Label("Ready to submit.");
        status.getStyleClass().add("status-text");
        status.setWrapText(true);
        status.setMaxWidth(700);

        TextArea aiResults = new TextArea();
        aiResults.setEditable(false);
        aiResults.setWrapText(true);
        aiResults.setPromptText("AI analysis results will appear here...");
        aiResults.setPrefRowCount(10);

        submitButton.setOnAction(event -> {
            String name = testerName.getText().trim();
            String title = issueTitle.getText().trim();
            String details = description.getText().trim();
            String steps = reproductionSteps.getText().trim();
            String expected = expectedResult.getText().trim();
            String actual = actualResult.getText().trim();
            String testEnvironment = environment.getText().trim();
            String type = feedbackType.getValue();

            if (name.isEmpty() || title.isEmpty() || details.isEmpty()) {
                status.setText("Please complete your name, issue title and description.");
                return;
            }

            submitButton.setDisable(true);
            aiResults.clear();
            status.setText("Submitting feedback...");

            Thread thread = new Thread(() -> {
                try {
                    String reportId = service.submitFeedback(name, title, details, type, steps, expected, actual, testEnvironment);

                    Platform.runLater(() -> {
                        status.setText("Feedback submitted!\n" + "Report ID: " + reportId + "\nStarting AI analysis...");

                        testerName.clear();
                        issueTitle.clear();
                        description.clear();
                        reproductionSteps.clear();
                        expectedResult.clear();
                        actualResult.clear();
                        environment.clear();
                        feedbackType.setValue("bug");
                    });

                    String interactionId = service.startAiAnalysis(reportId);

                    Platform.runLater(() -> status.setText("Feedback submitted successfully!\n" + "AI analysis started.\n" + "Waiting for Gemini..."));

                    boolean completed = false;

                    for (int attempt = 1; attempt <= 40; attempt++) {
                        Thread.sleep(3000);

                        String result = service.checkAiAnalysis(interactionId);
                        String aiStatus = service.extractStatus(result);

                        if ("completed".equals(aiStatus)) {
                            completed = true;

                            Platform.runLater(() -> {
                                status.setText("AI analysis completed successfully!\n" + "Report ID: " + reportId);
                                aiResults.setText(result);
                                submitButton.setDisable(false);
                            });

                            break;
                        }

                        if ("failed".equals(aiStatus)) {
                            throw new RuntimeException("Gemini analysis failed: " + result);
                        }

                        final int progress = attempt;

                        Platform.runLater(() -> status.setText("Feedback submitted successfully!\n" + "Gemini is analysing your report...\n" + "Checking progress: " + progress + "/40"));
                    }

                    if (!completed) {
                        Platform.runLater(() -> {
                            status.setText("AI analysis is still processing.\n" + "Report ID: " + reportId + "\nYou can check again later.");
                            submitButton.setDisable(false);
                        });
                    }

                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        status.setText("Error: " + ex.getMessage());
                        submitButton.setDisable(false);
                    });
                }
            }, "feedback-submission");

            thread.setDaemon(true);
            thread.start();
        });

        Label formHeading = new Label("Feedback details");
        formHeading.getStyleClass().add("section-title");

        Label resultsHeading = new Label("AI Analysis Results");
        resultsHeading.getStyleClass().add("section-title");

        VBox root = new VBox(13, backButton, heading, subtitle, introduction, formHeading, new Label("Tester Name"), testerName, new Label("Issue Title"), issueTitle, new Label("Description"), description, new Label("Steps to Reproduce (Optional)"), reproductionSteps, new Label("Expected Result (Optional)"), expectedResult, new Label("Actual Result (Optional)"), actualResult, new Label("Testing Environment (Optional)"), environment, new Label("Feedback Type"), feedbackType, submitButton, status, new Separator(), resultsHeading, aiResults);

        root.getStyleClass().add("page-card");
        root.setPadding(new Insets(30));
        root.setMaxWidth(820);
        root.setPrefWidth(820);

        StackPane content = new StackPane(root);
        content.getStyleClass().add("app-root");
        content.setPadding(new Insets(24));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("app-scroll");

        displayScreen(stage, scrollPane, "UAT, Untangled - Tester");
    }

    public static void main(String[] args) {
        launch(args);
    }
}