# Codex setup and model review

Reviewed 2026-09-07 using the OpenAI Docs skill.

## Project configuration

`.codex/config.toml` selects `gpt-6-astra` with `medium` reasoning, per the
owner's explicit repository preference. Existing task selections and explicit CLI overrides can take
precedence. Project configuration applies in trusted repositories; start a new
task or restart Codex to load the new documentation server.

The project registers the public `openaiDeveloperDocs` MCP endpoint. For OpenAI
questions, search and fetch the relevant official documentation with its tools
when available; fall back to official OpenAI web sources when unavailable.
It requires no repository API key. Global authentication and permission settings
are not part of this project configuration.

Sources: [configuration precedence](https://developers.openai.com/codex/config-basic),
[configuration fields](https://developers.openai.com/codex/config-reference), and
[OpenAI Docs skill](https://github.com/openai/skills/blob/main/skills/.curated/openai-docs/SKILL.md).

## Required model routing

Use `gpt-6-astra` with `medium` reasoning for planning and review. Always
assign implementation to subagents using `gpt-5.6-luna` with `xhigh` reasoning.
Pass model and reasoning effort explicitly when spawning; use a fresh or bounded
context when the collaboration tool requires it for model overrides. Astra owns
task decomposition and review of the resulting implementation and validation.
This owner-directed routing takes precedence over older skill model suggestions
for those roles. Preserve mandatory independent reviews and phase barriers.
If the required model cannot be selected, report the limitation rather than
silently choosing a different model.

## Prompt changes

`AGENTS.md` now specifies completion, handling of real blockers, callable tool
adaptation, evidence boundaries, concise responses, and proportionate checks.
Its skill links resolve to `.agents/skills/`, the local Codex skill directory.
Trading thresholds, position rules, output contracts, and mandatory independent
reviews remain the authority for their respective workflows.

Sources: [GPT-6 Astra migration and prompting](https://developers.openai.com/api/docs/guides/latest-model/gpt-6-astra#prompting-best-practices)
and [skill discovery](https://developers.openai.com/codex/skills).

## Calibration in Codex

The Java calibration driver generates task files; it does not call an LLM API.
Its `haiku`/`sonnet`/`opus` defaults support the Claude workflow. They are not
OpenAI model IDs. Preserve those defaults and historical run manifests.

For a **new Codex calibration**, pass `--skill-dir .agents/skills` to
`./bin/analytics calib-run init` along with the inputs required by the calibration
skill. This overrides the driver's `.claude/skills` default and targets the skills
Codex actually loads.

Before the first `plan`, set the new run's `run.json` `models` object to:

```json
{
  "extract": "gpt-5.6-luna",
  "grade": "gpt-6-astra",
  "diagnose": "gpt-6-astra",
  "verify": "gpt-6-astra",
  "synthesize": "gpt-6-astra"
}
```

These are Codex model IDs advertised by the current session at review time.
This is a workload-based starting map, not evidence of equivalent forecast
quality. Use Luna/xhigh for extraction work and Astra/medium for grading,
diagnosis, verification, and synthesis. Any implementation of adopted changes
is delegated to Luna/xhigh and reviewed by Astra/medium. Check the current session's supported
models before dispatch; honor an explicit user model choice and record deviations
in the run and calibration ledger. Do not silently substitute an unavailable model.

The CLI currently exposes no `--models` flag, although the engine persists and
reads `run.json.models`. Change only that object in a newly initialized run before
planning; do not rewrite already planned tasks or completed runs. The planner
copies the selected model into each task manifest. Spawn agents with that model
and the corresponding prompt file using the session's supported tool arguments.
Pass the reasoning effort explicitly at dispatch: the manifest's `models` object
contains model IDs only and does not configure reasoning effort.
Keep the file-based JSON deliverable and one-line status reply contract; validate
each phase with `collect` before advancing.

## Review scope and validation

No direct OpenAI SDK imports, API endpoints, GPT model defaults, or `codex exec`
calls were found in the tracked application/tooling sources reviewed. There is
therefore no application API migration or sampling-parameter removal to perform.
The existing Claude model defaults, historical reports, and oracle fixtures stay
intact. No market analysis or calibration was run as part of this setup change.

Validate the TOML, skill paths, and `codex mcp get openaiDeveloperDocs` from the
repository. A successful configuration read verifies registration, not an
end-to-end model call or forecast-quality improvement. Validate the new model map
on the next authorized calibration using the existing schema collectors and
independent audits before attributing any quality improvement to the migration.
