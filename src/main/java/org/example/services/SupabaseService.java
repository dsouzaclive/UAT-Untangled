package org.example.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.utils.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class SupabaseService {

    private final HttpClient client;
    private final ObjectMapper mapper;

    public SupabaseService() {

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        mapper = new ObjectMapper();
    }

    private HttpRequest.Builder requestBuilder(String endpoint) {

        return HttpRequest.newBuilder()
                .uri(URI.create(Config.SUPABASE_URL + endpoint))
                .header("apikey", Config.SUPABASE_KEY)
                .header("Authorization", "Bearer " + Config.SUPABASE_KEY)
                .header("Content-Type", "application/json");
    }

    private HttpResponse<String> send(HttpRequest request)
            throws Exception {

        return client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private void checkResponse(
            HttpResponse<String> response,
            String operation
    ) {

        if (response.statusCode() < 200 ||
                response.statusCode() >= 300) {

            throw new RuntimeException(
                    operation + " failed (" +
                            response.statusCode() + "): " +
                            response.body()
            );
        }
    }

    // Test Supabase connection.
    public String testConnection() throws Exception {

        HttpRequest request = requestBuilder(
                "/rest/v1/test_projects?select=id&limit=1"
        ).GET().build();

        HttpResponse<String> response = send(request);

        checkResponse(response, "Supabase connection");

        return "CONNECTED: Supabase database is working!";
    }

    // Submit feedback, including all tester information.
    public String submitFeedback(
            String testerName,
            String title,
            String description,
            String feedbackType,
            String reproductionSteps,
            String expectedResult,
            String actualResult,
            String environment
    ) throws Exception {

        ObjectNode json = mapper.createObjectNode();

        json.put("tester_name", testerName);
        json.put("title", title);
        json.put("description", description);
        json.put("feedback_type", feedbackType);

        // Save reproduction steps as a JSON array.
        json.set(
                "reproduction_steps",
                linesToArray(reproductionSteps)
        );

        // Save expected and actual results.
        json.put(
                "expected_result",
                expectedResult == null ? "" : expectedResult.trim()
        );

        json.put(
                "actual_result",
                actualResult == null ? "" : actualResult.trim()
        );

        // Save testing environment.
        json.put(
                "environment",
                environment == null ? "" : environment.trim()
        );

        HttpRequest request = requestBuilder(
                "/rest/v1/feedback_reports"
        )
                .header("Prefer", "return=representation")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(json)
                ))
                .build();

        HttpResponse<String> response = send(request);

        checkResponse(response, "Feedback submission");

        return extractValue(response.body(), "id");
    }

    // Start Gemini analysis.
    public String startAiAnalysis(String reportId) throws Exception {

        ObjectNode json = mapper.createObjectNode();

        json.put("action", "start");
        json.put("report_id", reportId);

        HttpRequest request = requestBuilder(
                "/functions/v1/analyse-feedback"
        )
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(json)
                ))
                .build();

        HttpResponse<String> response = send(request);

        checkResponse(response, "Starting AI analysis");

        return extractValue(response.body(), "interaction_id");
    }

    // Check Gemini analysis progress.
    public String checkAiAnalysis(String interactionId)
            throws Exception {

        ObjectNode json = mapper.createObjectNode();

        json.put("action", "check");
        json.put("interaction_id", interactionId);

        HttpRequest request = requestBuilder(
                "/functions/v1/analyse-feedback"
        )
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(json)
                ))
                .build();

        HttpResponse<String> response = send(request);

        checkResponse(response, "AI status check");

        return response.body();
    }

    // Retrieve pending feedback and attach its AI analysis.
    public ArrayNode getManagerQueue() throws Exception {

        HttpRequest reportsRequest = requestBuilder(
                "/rest/v1/feedback_reports" +
                        "?select=*" +
                        "&status=in.(new,needs_info)" +
                        "&order=created_at.desc"
        ).GET().build();

        HttpResponse<String> reportsResponse =
                send(reportsRequest);

        checkResponse(reportsResponse, "Retrieving feedback");

        HttpRequest analysesRequest = requestBuilder(
                "/rest/v1/ai_analyses?select=*&order=created_at.desc"
        ).GET().build();

        HttpResponse<String> analysesResponse =
                send(analysesRequest);

        checkResponse(analysesResponse, "Retrieving AI analyses");

        ArrayNode reports = (ArrayNode)
                mapper.readTree(reportsResponse.body());

        ArrayNode analyses = (ArrayNode)
                mapper.readTree(analysesResponse.body());

        // Match each report with its latest AI analysis.
        Map<String, JsonNode> analysisMap = new HashMap<>();

        for (JsonNode analysis : analyses) {

            String reportId = analysis.path("report_id").asText();

            if (!reportId.isEmpty()) {
                analysisMap.putIfAbsent(reportId, analysis);
            }
        }

        ArrayNode managerQueue = mapper.createArrayNode();

        for (JsonNode report : reports) {

            ObjectNode managerReport = report.deepCopy();

            String reportId = report.path("id").asText();

            JsonNode analysis = analysisMap.get(reportId);

            if (analysis != null) {
                managerReport.set("ai_analysis", analysis);
            } else {
                managerReport.putNull("ai_analysis");
            }

            managerQueue.add(managerReport);
        }

        return managerQueue;
    }

    // Save the manager's decision and update the feedback status.
    public void recordDecision(
            String reportId,
            String managerName,
            String decision,
            String finalSeverity,
            String overrideReason,
            String managerNotes
    ) throws Exception {

        String newStatus;

        switch (decision) {
            case "approve" -> newStatus = "approved";
            case "reject" -> newStatus = "rejected";
            case "mark_duplicate" -> newStatus = "duplicate";
            case "request_info" -> newStatus = "needs_info";

            default -> throw new IllegalArgumentException(
                    "Invalid manager decision: " + decision
            );
        }

        ObjectNode json = mapper.createObjectNode();

        json.put("report_id", reportId);

        json.put(
                "manager_name",
                managerName == null || managerName.isBlank()
                        ? "Manager"
                        : managerName
        );

        json.put("decision", decision);

        if (finalSeverity != null && !finalSeverity.isBlank()) {
            json.put("final_severity", finalSeverity);
        }

        if (overrideReason != null) {
            json.put("override_reason", overrideReason);
        }

        if (managerNotes != null) {
            json.put("manager_notes", managerNotes);
        }

        // First, save the human review decision.
        HttpRequest decisionRequest = requestBuilder(
                "/rest/v1/review_decisions"
        )
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(json)
                ))
                .build();

        HttpResponse<String> decisionResponse =
                send(decisionRequest);

        checkResponse(
                decisionResponse,
                "Saving manager decision"
        );

        // Then update the feedback report's status.
        ObjectNode statusJson = mapper.createObjectNode();
        statusJson.put("status", newStatus);

        HttpRequest updateRequest = requestBuilder(
                "/rest/v1/feedback_reports?id=eq." + reportId
        )
                .method(
                        "PATCH",
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(statusJson)
                        )
                )
                .build();

        HttpResponse<String> updateResponse =
                send(updateRequest);

        checkResponse(
                updateResponse,
                "Updating feedback status"
        );
    }

    // Save the manager-edited ticket draft to the existing AI analysis.
    public void saveTicketDraft(
            String reportId,
            String title,
            String description,
            String stepsToReproduce,
            String expectedResult,
            String actualResult,
            String severity,
            String acceptanceCriteria
    ) throws Exception {

        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("No report selected.");
        }

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "Ticket title cannot be empty."
            );
        }

        ObjectNode ticketDraft = mapper.createObjectNode();

        ticketDraft.put("title", title.trim());

        ticketDraft.put(
                "description",
                description == null ? "" : description.trim()
        );

        ticketDraft.set(
                "steps_to_reproduce",
                linesToArray(stepsToReproduce)
        );

        ticketDraft.put(
                "expected_result",
                expectedResult == null ? "" : expectedResult.trim()
        );

        ticketDraft.put(
                "actual_result",
                actualResult == null ? "" : actualResult.trim()
        );

        ticketDraft.put(
                "severity",
                severity == null ? "medium" : severity.toLowerCase().trim()
        );

        ticketDraft.set(
                "acceptance_criteria",
                linesToArray(acceptanceCriteria)
        );

        ObjectNode updateJson = mapper.createObjectNode();
        updateJson.set("ticket_draft", ticketDraft);

        HttpRequest request = requestBuilder(
                "/rest/v1/ai_analyses?report_id=eq." + reportId
        )
                .header("Prefer", "return=representation")
                .method(
                        "PATCH",
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(updateJson)
                        )
                )
                .build();

        HttpResponse<String> response = send(request);

        checkResponse(response, "Saving ticket draft");

        JsonNode updatedRows = mapper.readTree(response.body());

        if (!updatedRows.isArray() || updatedRows.isEmpty()) {

            throw new RuntimeException(
                    "The ticket draft was not saved. " +
                            "No matching AI analysis was updated."
            );
        }
    }

    public void saveManagerTicketDraft(
            String reportId,
            String title,
            String description,
            String steps,
            String expected,
            String actual,
            String severity,
            String criteria
    ) throws Exception {

        ObjectNode draft = mapper.createObjectNode();

        draft.put("title", title);
        draft.put("description", description);
        draft.set("steps_to_reproduce", linesToArray(steps));
        draft.put("expected_result", expected);
        draft.put("actual_result", actual);
        draft.put("severity", severity);
        draft.set("acceptance_criteria", linesToArray(criteria));

        ObjectNode payload = mapper.createObjectNode();
        payload.set("manager_ticket_draft", draft);

        HttpRequest request = requestBuilder(
                "/rest/v1/feedback_reports?id=eq." + reportId
        )
                .header("Prefer", "return=minimal")
                .method(
                        "PATCH",
                        HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(payload)
                        )
                )
                .build();

        HttpResponse<String> response = send(request);

        checkResponse(response, "Saving manager ticket draft");
    }

    // Convert multiline text into a JSON array.
    private ArrayNode linesToArray(String text) {

        ArrayNode array = mapper.createArrayNode();

        if (text == null || text.isBlank()) {
            return array;
        }

        for (String line : text.split("\\R")) {

            String cleanedLine = line.trim();

            if (!cleanedLine.isEmpty()) {
                array.add(cleanedLine);
            }
        }

        return array;
    }

    // Extract a value from a JSON response.
    private String extractValue(String json, String key)
            throws Exception {

        JsonNode root = mapper.readTree(json);

        if (root.isArray() && !root.isEmpty()) {
            root = root.get(0);
        }

        JsonNode value = root.get(key);

        if (value == null || value.isNull()) {

            throw new RuntimeException(
                    "Could not find '" + key +
                            "' in response: " + json
            );
        }

        return value.asText();
    }

    // Extract analysis status.
    public String extractStatus(String json) throws Exception {
        return extractValue(json, "status");
    }
}
