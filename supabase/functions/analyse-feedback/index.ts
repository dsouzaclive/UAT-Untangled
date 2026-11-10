import { withSupabase } from "npm:@supabase/server@1";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const schema = {
  type: "object",
  properties: {
    classification: {
      type: "string",
      enum: [
        "functional_bug",
        "usability_issue",
        "performance_issue",
        "visual_issue",
        "improvement_idea",
      ],
    },
    summary: { type: "string" },
    suggested_severity: {
      type: "string",
      enum: ["low", "medium", "high", "critical"],
    },
    confidence: { type: "number" },
    reasoning: { type: "string" },
    clarification_question: { type: "string" },
    possible_duplicate: { type: "boolean" },
    duplicate_candidate_id: { type: "string" },
    duplicate_reason: { type: "string" },
    ticket_draft: {
      type: "object",
      properties: {
        title: { type: "string" },
        description: { type: "string" },
        steps_to_reproduce: {
          type: "array",
          items: { type: "string" },
        },
        expected_result: { type: "string" },
        actual_result: { type: "string" },
        severity: {
          type: "string",
          enum: ["low", "medium", "high", "critical"],
        },
        acceptance_criteria: {
          type: "array",
          items: { type: "string" },
        },
      },
      required: [
        "title",
        "description",
        "steps_to_reproduce",
        "expected_result",
        "actual_result",
        "severity",
        "acceptance_criteria",
      ],
    },
  },
  required: [
    "classification",
    "summary",
    "suggested_severity",
    "confidence",
    "reasoning",
    "clarification_question",
    "possible_duplicate",
    "duplicate_candidate_id",
    "duplicate_reason",
    "ticket_draft",
  ],
};

function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json",
    },
  });
}

function escapeControlCharacters(text: string): string {
  let result = "";
  let insideString = false;
  let escaped = false;

  for (const char of text) {
    if (insideString) {
      if (escaped) {
        result += char;
        escaped = false;
        continue;
      }

      if (char === "\\") {
        result += char;
        escaped = true;
        continue;
      }

      if (char === '"') {
        insideString = false;
        result += char;
        continue;
      }

      const code = char.charCodeAt(0);

      if (code < 32) {
        if (char === "\n") result += "\\n";
        else if (char === "\r") result += "\\r";
        else if (char === "\t") result += "\\t";
        else {
          result += `\\u${code.toString(16).padStart(4, "0")}`;
        }
      } else {
        result += char;
      }

      continue;
    }

    if (char === '"') {
      insideString = true;
    }

    result += char;
  }

  return result;
}

function extractOutput(interaction: any): string {
  if (typeof interaction.output_text === "string") {
    return interaction.output_text;
  }

  const steps = interaction.steps ?? [];

  for (const step of steps) {
    if (step.type === "model_output" && Array.isArray(step.content)) {
      const output = step.content
        .filter((item: any) => item.type === "text")
        .map((item: any) => item.text)
        .join("");

      if (output) return output;
    }
  }

  throw new Error("Gemini completed but returned no text output.");
}

function parseAnalysis(rawText: string) {
  const cleaned = rawText
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/i, "")
    .replace(/\s*```$/, "")
    .trim();

  const result = JSON.parse(
    escapeControlCharacters(cleaned),
  );

  const validClassifications = [
    "functional_bug",
    "usability_issue",
    "performance_issue",
    "visual_issue",
    "improvement_idea",
  ];

  const validSeverities = [
    "low",
    "medium",
    "high",
    "critical",
  ];

  if (!validClassifications.includes(result.classification)) {
    throw new Error("Gemini returned an invalid classification.");
  }

  if (!validSeverities.includes(result.suggested_severity)) {
    throw new Error("Gemini returned an invalid severity.");
  }

  if (
    typeof result.confidence !== "number" ||
    result.confidence < 0 ||
    result.confidence > 100
  ) {
    throw new Error("Gemini returned an invalid confidence score.");
  }

  if (
    !result.ticket_draft ||
    typeof result.ticket_draft.title !== "string" ||
    typeof result.ticket_draft.description !== "string" ||
    !Array.isArray(result.ticket_draft.steps_to_reproduce) ||
    typeof result.ticket_draft.expected_result !== "string" ||
    typeof result.ticket_draft.actual_result !== "string" ||
    !validSeverities.includes(result.ticket_draft.severity) ||
    !Array.isArray(result.ticket_draft.acceptance_criteria)
  ) {
    throw new Error("Gemini returned an invalid ticket draft.");
  }

  return result;
}

export default {
  fetch: withSupabase(
    { auth: ["publishable", "secret"] },
    async (req, _ctx) => {
      if (req.method === "OPTIONS") {
        return new Response("ok", {
          headers: corsHeaders,
        });
      }

      let activeJobId: string | null = null;
      let supabase: any = null;

      try {
        const supabaseUrl = Deno.env.get("SUPABASE_URL");

        const secretKeys = JSON.parse(
          Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}",
        );

        const secretKey = secretKeys.default;

        if (!supabaseUrl || !secretKey) {
          throw new Error("Supabase server configuration is missing.");
        }

        supabase = createClient(
          supabaseUrl,
          secretKey,
          {
            auth: {
              persistSession: false,
              autoRefreshToken: false,
            },
          },
        );

        const apiKey = Deno.env.get("GEMINI_API_KEY");

        if (!apiKey) {
          throw new Error("Gemini API key is missing.");
        }

        const body = await req.json();

        const action = body.action;
        const reportId = body.report_id;
        const interactionId = body.interaction_id;

        // ACTION 1: START ANALYSIS

        if (action === "start") {
          if (!reportId) {
            return jsonResponse({
              success: false,
              error: "report_id is required.",
            }, 400);
          }

          const { data: existingJob, error: jobLookupError } =
            await supabase
              .from("ai_analysis_jobs")
              .select("*")
              .eq("report_id", reportId)
              .maybeSingle();

          if (jobLookupError) throw jobLookupError;

          if (existingJob && existingJob.status !== "failed") {
              return jsonResponse({
                success: true,
                status: existingJob.status,
                interaction_id: existingJob.interaction_id,
                message: "An analysis job already exists for this report.",
              });
          }

          // Allow a failed job to be retried.
          if (existingJob) {
              activeJobId = existingJob.id;
          }

          const { data: report, error: reportError } =
            await supabase
              .from("feedback_reports")
              .select("*")
              .eq("id", reportId)
              .single();

          if (reportError || !report) {
            return jsonResponse({
              success: false,
              error: "Feedback report not found.",
            }, 404);
          }

          const { data: previousReports, error: previousError } =
            await supabase
              .from("feedback_reports")
              .select("id, title, description, feedback_type")
              .neq("id", reportId)
              .order("created_at", { ascending: false })
              .limit(10);

          if (previousError) throw previousError;

          const prompt = `
You are an assistant helping a human team review User Acceptance Testing (UAT) feedback.

Analyse the feedback below. Your job is to help the team understand and organise it, not make decisions for them.

CURRENT FEEDBACK:
Title: ${report.title ?? ""}
Description: ${report.description ?? ""}
Type: ${report.feedback_type ?? ""}
Expected result: ${report.expected_result ?? ""}
Actual result: ${report.actual_result ?? ""}
Reproduction steps: ${JSON.stringify(report.reproduction_steps ?? [])}
Environment: ${report.environment ?? ""}

PREVIOUS FEEDBACK REPORTS:
${JSON.stringify(previousReports ?? [])}

ANALYSIS INSTRUCTIONS:

1. Classify the feedback using one of the permitted classifications.
2. Summarise the issue clearly and briefly.
3. Suggest severity, but do not treat this as a final decision.
4. Give a confidence score from 0 to 100.
5. Explain the evidence behind your classification and severity.
6. If important information is missing, ask one polite clarification question. Otherwise use an empty string.
7. Identify a possible duplicate only when a previous report is genuinely similar.
8. If there is a possible duplicate, return its exact ID in duplicate_candidate_id.
9. Never invent report IDs. If no suitable duplicate exists, use an empty string.
10. Be cautious. Low evidence should result in lower confidence.
11. Return only valid JSON matching the requested schema.

TICKET DRAFT GENERATION:

Generate a practical, developer-ready ticket draft using the tester's submission and available evidence.

Requirements:

1. Write a concise, descriptive ticket title.
2. Explain the issue clearly, including its impact.
3. Provide reproduction steps based only on supplied information.
4. State the expected result.
5. State the actual result.
6. Recommend an appropriate severity.
7. Provide clear, testable acceptance criteria.

IMPORTANT:

- Do not invent missing technical details.
- Do not fabricate reproduction steps, logs, or environment information.
- If information is missing, explicitly say "Not provided".
- If no reproduction steps were supplied, return an empty steps_to_reproduce array.
- Keep the ticket factual and suitable for a development team.
- For improvement ideas, create an enhancement ticket rather than pretending there is a software defect.
- The ticket is a draft for human review, not an automatically approved development task.
- Do not automatically approve, reject, or create any development ticket.
`;

          const geminiResponse = await fetch(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                "x-goog-api-key": apiKey,
                "Api-Revision": "2026-05-20",
              },
              body: JSON.stringify({
                model: "gemini-3.8-flash",
                input: prompt,
                background: true,
                store: true,
                response_format: {
                  type: "text",
                  mime_type: "application/json",
                  schema,
                },
              }),
            },
          );

          const geminiData = await geminiResponse.json();

          if (!geminiResponse.ok) {
            console.error("Gemini start error:", geminiData);

            return jsonResponse({
              success: false,
              error:
                geminiData.error?.message ??
                "Gemini could not start the analysis.",
            }, 502);
          }

          if (!geminiData.id) {
            throw new Error("Gemini did not return an interaction ID.");
          }

          if (existingJob) {

            const { error: updateJobError } = await supabase
                .from("ai_analysis_jobs")
                .update({
                    interaction_id: geminiData.id,
                    status: "processing",
                    updated_at: new Date().toISOString(),
                })
                .eq("id", existingJob.id);

            if (updateJobError) throw updateJobError;

            activeJobId = existingJob.id;

        } else {

            const { data: insertedJob, error: insertJobError } =
                await supabase
                    .from("ai_analysis_jobs")
                    .insert({
                        report_id: reportId,
                        interaction_id: geminiData.id,
                        status: "processing",
                    })
                    .select("id")
                    .single();

            if (insertJobError) throw insertJobError;

            activeJobId = insertedJob.id;
        }

          return jsonResponse({
            success: true,
            status: "processing",
            interaction_id: geminiData.id,
            message: "AI analysis has started.",
          });
        }

        // ACTION 2: CHECK ANALYSIS

        if (action === "check") {
          if (!interactionId) {
            return jsonResponse({
              success: false,
              error: "interaction_id is required.",
            }, 400);
          }

          const { data: job, error: jobError } =
            await supabase
              .from("ai_analysis_jobs")
              .select("*")
              .eq("interaction_id", interactionId)
              .single();

          if (jobError || !job) {
            return jsonResponse({
              success: false,
              error: "Analysis job not found.",
            }, 404);
          }

          activeJobId = job.id;

          if (job.status === "completed") {
            const { data: savedAnalysis, error: savedError } =
              await supabase
                .from("ai_analyses")
                .select("*")
                .eq("report_id", job.report_id)
                .order("created_at", { ascending: false })
                .limit(1)
                .maybeSingle();

            if (savedError) throw savedError;

            return jsonResponse({
              success: true,
              status: "completed",
              analysis: savedAnalysis,
            });
          }

          if (job.status === "failed") {
            return jsonResponse({
              success: false,
              status: "failed",
              error: "This analysis job has already failed.",
            });
          }

          const checkResponse = await fetch(
            `https://generativelanguage.googleapis.com/v1beta/interactions/${encodeURIComponent(interactionId)}`,
            {
              method: "GET",
              headers: {
                "x-goog-api-key": apiKey,
                "Api-Revision": "2026-05-20",
              },
            },
          );

          const interaction = await checkResponse.json();

          if (!checkResponse.ok) {
            console.error("Gemini check error:", interaction);

            return jsonResponse({
              success: false,
              error:
                interaction.error?.message ??
                "Could not check Gemini analysis.",
            }, 502);
          }

          if (
            interaction.status === "in_progress" ||
            interaction.status === "requires_action"
          ) {
            return jsonResponse({
              success: true,
              status: "processing",
              message: "Gemini is still analysing the feedback.",
            });
          }

          if (interaction.status !== "completed") {
            const geminiErrors = interaction.errors ?? [];

            console.error(
              "Gemini interaction failure:",
              JSON.stringify({
                id: interaction.id,
                status: interaction.status,
                errors: geminiErrors,
                steps: interaction.steps,
              }, null, 2),
            );

            const failureMessage = geminiErrors.length > 0
              ? geminiErrors.map((e: any) => e.message).join("; ")
              : "Gemini failed without providing a diagnostic message.";

            const { error: updateError } = await supabase
              .from("ai_analysis_jobs")
              .update({
                status: "failed",
                updated_at: new Date().toISOString(),
              })
              .eq("id", job.id);

            if (updateError) throw updateError;

            return jsonResponse({
              success: false,
              status: "failed",
              error: failureMessage,
              details: {
                interaction_id: interaction.id,
                status: interaction.status,
                errors: geminiErrors,
              },
            });
          }

          const rawText = extractOutput(interaction);
          const analysis = parseAnalysis(rawText);

          let duplicateOf: string | null = null;

          if (
            analysis.possible_duplicate &&
            analysis.duplicate_candidate_id &&
            analysis.duplicate_candidate_id !== job.report_id
          ) {
            const { data: candidate, error: candidateError } =
              await supabase
                .from("feedback_reports")
                .select("id")
                .eq("id", analysis.duplicate_candidate_id)
                .maybeSingle();

            if (candidateError) throw candidateError;

            if (candidate) {
              duplicateOf = candidate.id;
            }
          }

          const { data: savedAnalysis, error: saveError } =
            await supabase
              .from("ai_analyses")
              .insert({
                report_id: job.report_id,
                classification: analysis.classification,
                summary: analysis.summary,
                suggested_severity: analysis.suggested_severity,
                confidence: analysis.confidence,
                reasoning: analysis.reasoning,
                clarification_question:
                  analysis.clarification_question,
                possible_duplicate: Boolean(duplicateOf),
                duplicate_of: duplicateOf,
                duplicate_reason: duplicateOf
                  ? analysis.duplicate_reason
                  : "",
                ticket_draft: analysis.ticket_draft,
              })
              .select()
              .single();

          if (saveError) throw saveError;

          const { error: updateJobError } = await supabase
            .from("ai_analysis_jobs")
            .update({
              status: "completed",
              updated_at: new Date().toISOString(),
            })
            .eq("id", job.id);

          if (updateJobError) throw updateJobError;

          activeJobId = null;

          return jsonResponse({
            success: true,
            status: "completed",
            analysis: savedAnalysis,
          });
        }

        return jsonResponse({
          success: false,
          error: "Invalid action. Use 'start' or 'check'.",
        }, 400);

      } catch (error) {
        console.error("Analysis function error:", error);

        const errorMessage = error instanceof Error
          ? error.message
          : "An unexpected error occurred.";

        // NEW: Mark the active job as failed if an unexpected
        // error occurs after its ID has been identified.

        if (supabase && activeJobId) {
          try {
            const { error: updateError } = await supabase
              .from("ai_analysis_jobs")
              .update({
                status: "failed",
                updated_at: new Date().toISOString(),
              })
              .eq("id", activeJobId);

            if (updateError) {
              console.error(
                "Could not mark analysis job as failed:",
                updateError,
              );
            }
          } catch (statusError) {
            console.error(
              "Failed to update analysis job status:",
              statusError,
            );
          }
        }

        return jsonResponse({
          success: false,
          status: activeJobId ? "failed" : "error",
          error: errorMessage,
        }, 500);
      }
    },
  ),
};
