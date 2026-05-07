# AI Agent Operating Rules (Strict Mode)

This repository enforces strict dependency + Context7 control.

You MUST follow these rules.

---

## 0. Rule Priority

If rules conflict, follow this priority order:

1. System/developer runtime instructions
2. `AGENTS.md` in this repository
3. General model defaults and assumptions

When conflict exists:
- Follow the higher-priority rule.
- Explicitly state which rule was applied.
- Do not guess.
- Treat conflicting lower-priority rules as non-applicable for that turn.

---

## 1. Dependency Source of Truth

Before answering any question related to:

- Spring Boot
- Spring Security
- MyBatis
- Redis
- JDBC
- JUnit
- AWS SDK
- Any external library behavior

You MUST read:

`docs/context7-dependencies.yaml`

This file is the authoritative source for:

- Project dependency versions
- Context7 primary library IDs
- Context7 fallback library IDs
- Artifact mappings

Never guess versions.  
Never assume "latest".  
Always use the version defined in the YAML file.

If the dependency is NOT listed:

- Ask the user for confirmation.
- Do NOT proceed with assumptions.
- Suspend detailed implementation/code generation until confirmed.

---

## 2. Context7 Usage Policy

Context7 MUST be used ONLY when:

- Exact API signatures are required
- Version-accurate configuration options are needed
- Deprecated/removed API must be verified
- Security configuration depends on version
- Official example code is necessary

Context7 MUST NOT be used for:

- General Java concepts
- High-level architecture
- Algorithm explanations
- Non-library-specific discussions

---

## 3. How Context7 Must Be Invoked

When using Context7:

1. Read `docs/context7-dependencies.yaml`
2. Retrieve the correct `primary_library_id`
3. Invoke using:

   `use library <primary_library_id>`

4. Always include version explicitly:

   `"<Library Name> <version> 기준으로 설명해주세요."`

---

## 4. Fallback Library Usage Rule (Clarified)

Fallback library IDs (for example, website/reference slugs)  
MUST be used ONLY when the question specifically requires:

- Configuration reference tables
- DSL guide explanations
- Property reference documentation
- Official reference chapter-level semantics
- Structured documentation sections

Fallback MUST NOT be used for:

- Class-level API usage
- Implementation examples
- Code snippet generation
- Internal class behavior explanation

Default to repository slug unless explicitly documentation-oriented.

---

## 5. Call Optimization Rule (Free Tier Protection)

To preserve monthly Context7 limits:

- Clarify scope BEFORE invoking Context7.
- Batch related API requests into ONE call.
- Default to ONE Context7 call per dependency per thread.
- Additional calls for the SAME dependency are allowed only when:
  - User requirements changed after the initial call
  - Exact signatures or version-specific options must be re-verified
  - Error investigation requires additional official references
- For each additional call, briefly state the reason in one sentence.

---

## 6. Version Enforcement Rule

When generating code:

- Explicitly state the dependency version.
- Never mix major versions.
- Respect Spring Boot managed dependency alignment.
- Do not silently upgrade versions.

---

## 7. Missing Dependency Handling (Strict Mode)

If a required dependency is missing from `docs/context7-dependencies.yaml`:

1. Inform the user.
2. Ask for confirmation.
3. Pause implementation-level response.
4. Do NOT call Context7.
5. Resume only after confirmation.

---

## 8. Required Output Format (Library-Specific Answers)

For any dependency/library-specific answer, always include:

- `version`: The version used from `docs/context7-dependencies.yaml`
- `source`: `docs/context7-dependencies.yaml`
- `context7_library_id`: The library ID actually used (or `not_used` with reason)

If Context7 was intentionally not used, state the reason in one sentence.

---

## 9. Unit Test Writing Reference Rule

When writing unit tests, you MUST reference:

`docs/junit-unit-test-guide.md`

Use this document as the default style and quality guide for JUnit test structure, annotations, assertions, and Mockito usage.

---

## 10. Code Comment Requirement

For code written by the AI agent:

- Prefer code that is simple enough to understand without comments.
- Use comments kindly and clearly only when they help maintainers understand non-obvious intent, branching reasons, important constraints, or workflow.
- Do NOT add comments that merely restate obvious code.

---

## 11. Execution Policy

This section governs how non-trivial tasks are planned and executed.

### 11.0 Think Before Coding

- State assumptions explicitly before implementation.
- If requirements are unclear, stop, name what is unclear, and ask the user.
- If multiple valid interpretations exist, present them instead of silently choosing one.
- If a simpler approach exists, mention it and prefer it unless the user approves a broader scope.
- Push back when the requested approach appears unnecessarily complex, risky, or misaligned with the stated goal.

### 11.1 Plan-First Principle

- Non-trivial tasks MUST start in **plan-only mode**.
- During the planning phase, do NOT implement any code.
- Break work into the **smallest meaningful milestones**.
- A plan is a **candidate task list**, not permission to execute all listed steps.
- For multi-step work, include concrete success criteria for each step, such as `Step -> verify: check`.

### 11.2 One-Milestone-Per-Turn Rule

- Never implement more than **one milestone per turn**.
- After completing one milestone, **stop and wait** for explicit user approval.
- Do NOT automatically continue to the next milestone.
- Even if the plan lists sequential steps, only proceed with the step the user has approved.

### 11.3 Approval Gate

- User approval is required before each milestone execution begins.
- Unapproved milestones MUST NOT be executed under any circumstance.
- If a milestone's scope grows during implementation, pause and re-decompose before continuing.

### 11.4 Goal-Driven Execution

- Convert vague requests into verifiable goals before coding.
- For bug fixes, prefer a test or focused reproduction that fails before the fix and passes after it.
- For validation changes, include checks for invalid and boundary inputs when practical.
- For refactors, verify behavior before and after when the project provides suitable tests.
- Continue the implement/verify loop within the approved milestone until the stated success criteria are met or a blocker is surfaced.

---

## 12. Self-Review Policy

After implementing a milestone, the agent MUST NOT immediately report completion.
Instead, follow this sequence: **Implement → Verify → Self-Review → Fix → Report → Wait**.

### 12.1 Verification Step

- Run available tests, lint checks, and type checks.
- Verify that the build (if applicable) succeeds.
- Confirm no regressions in directly related functionality.

### 12.2 Critical Self-Review

Perform a brief but honest review for:

- **Duplication**: Is the new code duplicating existing patterns?
- **Regression**: Could this change break existing behavior?
- **Edge cases**: Are important boundary conditions handled?
- **Over-engineering**: Is the solution unnecessarily complex for the requirement?
- **Simplicity**: Could the solution be materially smaller or clearer without losing required behavior?

### 12.3 Fix-Before-Report

- If obvious issues are found during self-review, fix them **before** reporting to the user.
- After fixing, re-run minimum verification to confirm the fix.
- Only then report the milestone as complete.

---

## 13. Milestone Report Format

After each milestone completion (post self-review), report using this standard format:

```
### Milestone Report

**Changed Files**
- List of files created, modified, or deleted

**What Was Implemented**
- Brief description of the work done in this milestone

**Validations Performed**
- Tests run, lint results, build status, manual checks

**Self-Review Findings**
- Issues found and fixed during self-review
- Any concerns noted but deferred

**Known Risks / Open Issues**
- Remaining risks, limitations, or unresolved items

**Next Milestone Candidate**
- Suggested next step (awaiting user approval)
```

This format ensures the user can quickly assess what to review and whether to approve the next step.

---

## 14. Change Scope Control

The purpose of milestone decomposition is to **control change scope** and maintain reviewability.

### 14.1 Scope Boundaries

- Do NOT modify code unrelated to the current milestone.
- Refactoring is permitted ONLY within the current milestone's scope.
- Do NOT introduce new dependencies unless strictly necessary for the milestone.
- Do NOT add features, flexibility, configuration, or abstractions that were not requested.
- Do NOT add error handling for scenarios that cannot occur in the current design.
- Every changed line should trace directly to the user's request or to cleanup caused by that change.

### 14.2 Reuse Over Duplication

- Prefer reusing existing project structures and patterns.
- Avoid duplicate implementations of logic that already exists.
- Match existing local style, even when another style would also be reasonable.
- If the implementation becomes much larger than the requirement warrants, simplify it before reporting.

### 14.3 Scope Escalation

- If the change scope starts growing beyond the original milestone boundary, **stop implementing**.
- Re-decompose the milestone into smaller sub-milestones.
- Present the revised plan to the user before continuing.

### 14.4 Surgical Change Rules

- Do NOT improve adjacent code, comments, formatting, or naming unless it is required for the approved milestone.
- Do NOT refactor unrelated code even if it looks imperfect.
- Remove imports, variables, functions, files, or comments only when they became unused because of the current change.
- If unrelated dead code or cleanup opportunities are noticed, mention them in the report instead of changing them.

---

## 15. Long-Running Task Management

For tasks that span multiple turns or sessions, maintain an external planning document to prevent context loss.

### 15.1 PLANS.md as Cross-Session State

- For long-running or multi-step work, create and maintain a `PLANS.md` file under `docs/plan/`.
- Example path: `docs/plan/PLANS.md` (create the directory if it does not exist).
- `PLANS.md` is the **source of truth** for the current execution plan and milestone status.
- Before continuing work in a later session, **read `docs/plan/PLANS.md` first** to restore context.

### 15.2 Relationship with Thread Session Context

- `PLANS.md` is the only persistent planning document across sessions.
- Within a single thread, use the current session context as the per-conversation planning source.
- When the thread/session context is unavailable or a new session starts, restore context from `docs/plan/PLANS.md` first.
- If session context and `PLANS.md` differ, treat `PLANS.md` as the persistent baseline and apply user-approved updates going forward.

### 15.3 Update Discipline

- After each completed milestone, update `PLANS.md` with:
  - Milestone status (planned / in-progress / completed)
  - Change log entry for what was done
  - Any deviations from the original plan
- `AGENTS.md` = behavioral rules (static). `PLANS.md` = task-specific planning state (dynamic).

### 15.4 Lifecycle Rule

- `PLANS.md` is considered **complete** when all milestones reach `completed` status.
- The agent MUST NOT automatically delete `PLANS.md` upon completion.
- Instead, add a `[COMPLETED]` marker at the top of the document and notify the user.
- After marking completion, move the file to `docs/plan/completed/[한글로 작성된 작업 간단요약].md` to preserve history.
- The archived file in `docs/plan/completed/` MUST NOT be deleted unless the user explicitly instructs deletion.
- This preserves the decision-making history for retrospective review.

---

## 16. Sequential Thinking Policy

Sequential Thinking MCP is a supplementary reasoning tool. It aids structured thinking but does NOT control execution.

### 16.1 Permitted Uses

- **Plan decomposition**: Breaking complex tasks into milestones.
- **Self-review enhancement**: Structuring critical review of implemented code.
- **Decision analysis**: Evaluating trade-offs before proposing a plan.

### 16.2 Execution Boundary

- Sequential Thinking output is **never** authorization to execute all derived steps.
- Even well-structured plans produced by Sequential Thinking require **explicit user approval** per milestone.
- When Sequential Thinking output is recorded in `PLANS.md`, it remains in **draft status** until the user approves each milestone.

### 16.3 Role Definition

- Sequential Thinking is a **reasoning aid**, not an execution-control mechanism.
- It complements the Execution Policy (§11) and Self-Review Policy (§12) but does not override them.
- The approval gate (§11.3) always takes precedence over any Sequential Thinking output.

---

End of strict rules.
