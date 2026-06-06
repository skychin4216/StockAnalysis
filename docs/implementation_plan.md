# Implementation Plan and Changes (saved)

Date: 2026-06-07
Branch: dev

## 1) Summary of work completed (today)
- Added AI-focused docs and merged into architecture doc:
  - docs/AI_Dialogue_Stock_Analysis.md
  - docs/Quantitative_Simulated_Trading.md (merged AI dialogue)
  - docs/Codebase_Doc_Update_Notes.md
  - docs/backend_ai_handler.md (handler pseudocode)
  - docs/openapi-strategy-simulation.yaml (OpenAPI fragment)
  - docs/RemoteDataService_examples.md (Retrofit examples)
- Created skills/dialog_techniques/* (intent-recognition, dialog-techniques, copilot-guidelines, prompt-design)
- Integrated local AI handler + client and DTOs into app:
  - app/src/main/java/com/chin/stockanalysis/strategy/predict/AIAnalyzeHandler.kt
  - app/src/main/java/com/chin/stockanalysis/remote/RemoteAiClient.kt
  - app/src/main/java/com/chin/stockanalysis/remote/RetrofitFactory.kt
  - app/src/main/java/com/chin/stockanalysis/docs/RemoteDataService_examples.kt
- Moved project root Markdown files into docs/
- Committed and pushed changes to remote branch: dev

## 2) Files changed/added (full paths)
- docs/Quantitative_Simulated_Trading.md (updated, merged AI dialog)
- docs/AI_Dialogue_Stock_Analysis.md (new)
- docs/Codebase_Doc_Update_Notes.md (new)
- docs/backend_ai_handler.md (new)
- docs/openapi-strategy-simulation.yaml (new)
- docs/RemoteDataService_examples.md (new)
- docs/API_REFERENCE.md (updated)
- docs/implementation_plan.md (this file)
- skills/dialog_techniques/intent-recognition.md
- skills/dialog_techniques/dialog-techniques.md
- skills/dialog_techniques/copilot-guidelines.md
- skills/dialog_techniques/prompt-design.md

- app/src/main/java/com/chin/stockanalysis/strategy/predict/AIAnalyzeHandler.kt (new)
- app/src/main/java/com/chin/stockanalysis/remote/RemoteAiClient.kt (new)
- app/src/main/java/com/chin/stockanalysis/remote/RetrofitFactory.kt (new)
- app/src/main/java/com/chin/stockanalysis/docs/RemoteDataService_examples.kt (new)

## 3) Next steps (high priority, for tomorrow)
1. Fix KSP build failure seen during assembleDebug: run Gradle with --stacktrace, inspect KSP logs, and fix plugin or generated-code issues. (Owner: me)
2. Wire RemoteAiClient into UI Chat flow (ChatTabFragment):
   - Call analyzeStock(local mode) when user asks stock analysis
   - Render structured JSON result in StrategyResultDialogFragment table_view
   - Add "Add to simulation" button
3. Implement server endpoint /api/ai/analyze_stock using backend_ai_handler.md pseudocode (server team or create a lightweight service). Add OpenAPI route and unit tests.
4. Add JSON Schema validator for LLM output; implement fallback rule-based output when parsing fails.
5. Add tests: strategy contract tests & AI handler integration tests (mock provider).
6. Add rate-limiting, caching (30s intraday) and logging of prompt/exec_id; expose exec status endpoint.
7. Create PR(s) for code changes and request review; include run logs and example outputs.

## 4) Medium/Lower priority items
- Generate a Postman collection & Swagger UI from openapi YAML and publish to docs site.
- Create monitoring dashboard for AI calls (cost, latency, failure rate).
- Improve AI prompts (few-shot examples) and add CI check for JSON schema validity.

---

If this plan looks good, I'll start with step 1 (trace KSP build error) and then implement step 2 (UI wiring). Reply "start" to proceed or reply with modifications.
