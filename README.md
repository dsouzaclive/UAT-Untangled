# UAT, Untangled

### AI-Assisted User Acceptance Testing & Feedback Management

UAT, Untangled is a JavaFX desktop application designed to streamline User Acceptance Testing (UAT) feedback collection, analysis and management.

The application combines AI-assisted feedback analysis with a human-controlled review workflow, helping teams turn raw testing feedback into structured, actionable developer tickets.

**AI assists the decision-making process; the final decisions remain with the human reviewer.**

## Key Features

### Tester Portal
- Structured feedback submission.
- Guided reporting with reproduction steps.
- Expected and actual result collection.
- Environment information capture.
- AI-powered feedback analysis.

### AI-Powered Analysis
- Feedback classification.
- Issue summarisation.
- Suggested severity and confidence scoring.
- Possible duplicate detection.
- Clarification question generation.
- Structured developer ticket generation.

### Manager Dashboard
- Centralised feedback review queue.
- AI-generated ticket editing.
- Manager-controlled ticket saving.
- Approve, reject and mark duplicate actions.
- Request additional information.
- Duplicate report inspection.
- Retry AI analysis.

### Human Judgement Workflow
AI recommendations are advisory rather than automatic decisions. Managers retain control over report classification, ticket editing and final review decisions.

## Technology Stack

| Component | Technology |
|---|---|
| Programming language | Java |
| Desktop interface | JavaFX |
| Build management | Maven |
| Database | Supabase PostgreSQL |
| Backend integration | Supabase REST API |
| AI integration | Google Gemini |
| Server-side processing | Supabase Edge Functions |
| JSON processing | Jackson |

## Application Architecture

The application follows a structured client-server approach:

1. The JavaFX application provides the tester and manager interfaces.
2. Supabase stores feedback reports, AI analyses and review decisions.
3. A Supabase Edge Function communicates with Gemini to analyse submitted feedback.
4. AI results are returned to the application and presented to the manager.
5. Managers review the recommendations and make the final decisions.

## Getting Started

### Prerequisites

- Java JDK 21 or compatible version.
- IntelliJ IDEA or another Java IDE.
- Maven.
- A Supabase project.
- A Google Gemini API key.

### Configuration

The application uses environment variables for its Supabase configuration.

Configure the following variables before running the application:

```text
SUPABASE_URL=your_supabase_project_url
SUPABASE_KEY=your_supabase_publishable_key
```

NOTE: Do not hardcode actual credentials or API keys to the repository.

### Supabase Setup

To run the complete application, configure your own Supabase project with the required database tables, columns, relationships and Row Level Security policies.

Deploy the `analyse-feedback` Edge Function and configure the Gemini API key as a server-side Supabase secret.

The database setup instructions and Edge Function deployment instructions should be provided alongside the project.

### Running the Application

1. Clone the repository.
2. Open the project in IntelliJ IDEA.
3. Configure the required environment variables.
4. Ensure the Supabase backend is configured.
5. Run the Maven goal:

```bash
mvn javafx:run
```

Alternatively, run the `javafx:run` Maven goal through IntelliJ's Maven panel.

## Project Structure

```text
UAT_Untangled/
├── src/
│   └── main/
│       ├── java/
│       │   └── org/example/
│       │       ├── services/
│       │       │   └── SupabaseService.java
│       │       ├── utils/
│       │       │   └── Config.java
│       │       ├── Main.java
│       │       └── ManagerDashboard.java
│       └── resources/
│           └── css/
│               └── style.css
│── supabase/
│   └── functions/
|       └── analyse-feedback/
│           ├── index.ts
├── pom.xml
├── .gitignore
└── README.md
```

## Security

- Supabase publishable credentials are supplied through environment variables.
- Gemini API credentials are stored server-side in Supabase Edge Function secrets.
- Supabase Row Level Security policies should be configured appropriately.
- Never publish secret keys, service-role keys or private credentials.

## Project Objective

UAT, Untangled aims to make UAT feedback management more structured, efficient and accessible through AI-assisted analysis while preserving human oversight and accountability.

---

**UAT, Untangled**  
AI-assisted testing. Human-led decisions.
