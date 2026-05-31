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

## 0.1 Superpowers Workflow Alignment

For planning, documentation, execution, commits, worktrees, subagent usage, verification, review, and completion flow, follow the Codex `superpowers` plugin workflow when it is available, unless it conflicts with:

- System/developer runtime instructions
- This `AGENTS.md`
- Safety or permission requirements
- An explicit user instruction for the current task

The Codex `superpowers` workflow never takes precedence over this `AGENTS.md`. If `superpowers` is unavailable or cannot be used in the current environment, continue following the repository rules in this `AGENTS.md`.

When a relevant `superpowers` skill applies, use that skill's workflow. Approved specs and implementation plans should be executed continuously according to `superpowers:subagent-driven-development` or `superpowers:executing-plans`, not stopped after every individual task, unless a stop condition in this document applies. See §0.2 for the plan approval boundary.

All agent-authored documentation and review communication MUST be written in Korean. This includes specs, implementation plans, reports, PR titles/bodies, commit message summaries, code review comments, code review responses, Javadocs, code comments, DTO/enum/record field descriptions, and test display names such as `@DisplayName`.

Do NOT translate code identifiers, package/class/method names, protocol keywords, annotation names, validation message keys, metric names, log field keys, JSON field names, enum constant identifiers, file paths, shell commands, external API names, dependency/library names, raw error output, or quoted source text. Scope/domain names in commit messages should use lowercase English identifiers. This `AGENTS.md` document itself is exempt from full Korean translation.

Commit messages MUST use:

`Prefix(domain): Korean summary`

Allowed prefixes are `Feat`, `Fix`, `Docs`, `Refactor`, `Test`, and `Chore`. The first prefix letter MUST be uppercase. The domain MUST be a lowercase English domain identifier.

Example:

`Feat(traffic): 멱등키 검증 로직 구현`

---

## 0.2 Plan Approval Boundary

Writing, saving, or presenting a spec or implementation plan does NOT mean it is approved.

A spec or implementation plan is approved only when the user explicitly approves that specific plan or clearly asks the agent to proceed, implement, execute, or continue with that plan.

Before approval:

- The agent may ask questions, discuss alternatives, refine requirements, and write or update spec/plan documents.
- The agent MUST NOT modify production code.
- The agent MUST NOT start `superpowers:executing-plans`.
- The agent MUST NOT start `superpowers:subagent-driven-development`.
- The agent MUST NOT commit changes unless the user explicitly requested documentation-only commits or the commit is an allowed setup/spec/plan commit under §11.6.

After approval:

- The approved plan authorizes continuous execution of all listed tasks.
- The agent should not stop after every individual task.
- The agent must continue until all approved tasks are complete or a stop condition in this document is reached.

Examples that count as approval:

- "Approve this plan."
- "Proceed with this plan."
- "Implement this plan."
- "이 계획대로 진행하세요."
- "승인합니다."
- "구현하세요."

Examples that do NOT count as approval:

- "Show me the plan."
- "Review this approach."
- "Make the plan more detailed."
- "계획을 작성하세요."
- "검토해주세요."
- "어떤 방식이 좋은지 알려주세요."

---

## 1. Dependency Source of Truth

Before implementation-level answers, code generation, configuration, or exact API usage related to:

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

For high-level conceptual explanations that do not depend on project versions or exact APIs, do not read the dependency YAML by default. If examples become version-specific, first read `docs/context7-dependencies.yaml`.

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

For high-level conceptual explanations that do not depend on project versions or exact APIs, do not use Context7. If examples become version-specific, first read the dependency YAML.

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

## 9.1 Testability Must Not Distort Production Design

Testing convenience MUST NOT drive abnormal, artificial, or production-hostile module design.

- Do NOT add mutable fields, reflection hooks, test-only constructors, unnecessary setters, relaxed visibility, or extra indirection solely to make tests easier.
- Do NOT weaken encapsulation or dependency boundaries for tests.
- Do NOT introduce a new abstraction just because mocking the current design is inconvenient.
- Prefer testing through the same public constructor, bean wiring, and dependency contract used by production code.
- If deterministic tests require time, randomness, IDs, or external effects, model those as legitimate production dependencies only when they are real runtime concerns.
- If a test requires a structurally awkward change, stop and redesign the production code around the actual runtime responsibility, not around the test setup.
- During self-review, explicitly check whether any production code was shaped only for test convenience. If yes, simplify or redesign before reporting completion.

---

## 10. Code Comment Requirement

For code written by the AI agent:

- When creating a new public or package-visible class or method, write Javadocs that explain responsibility, role, and important contracts.
- When modifying an existing class or method, update Javadocs only if the change alters responsibility, behavior, contract, side effects, or important constraints.
- Do not add or update Javadocs or comments solely because a nearby class or method lacks documentation, unless the current task changes its behavior or contract.
- For method bodies, add concise step-by-step comments that describe the logic flow when the method contains meaningful branching, sequencing, transactions, external calls, or state transitions.
- When creating or modifying DTO, enum, and record types, explain the meaning of each field, enum constant, or record component with comments.
- All Javadocs, code comments, DTO/enum/record field descriptions, and test display names authored by the agent MUST be written in Korean.
- Prefer code that is simple enough to understand without excessive comments.
- Use comments kindly and clearly only when they help maintainers understand intent, branching reasons, important constraints, data meaning, or workflow.
- Do NOT add comments that merely restate obvious code.

---

## 11. Execution Policy

This section governs how non-trivial tasks are planned and executed with the `superpowers` workflow.

### 11.0 Think Before Coding

- State assumptions explicitly before implementation.
- If requirements are unclear, stop, name what is unclear, and ask the user.
- If multiple valid interpretations exist, present them instead of silently choosing one.
- If a simpler approach exists, mention it and prefer it unless the user approves a broader scope.
- Push back when the requested approach appears unnecessarily complex, risky, or misaligned with the stated goal.

### 11.1 Superpowers Plan-First Principle

- Non-trivial tasks MUST start with the relevant `superpowers` planning workflow.
- Use `superpowers:brainstorming` when requirements or design need refinement.
- Use `superpowers:writing-plans` when a spec or multi-step requirement exists.
- During the planning phase, do NOT implement production code.
- Plans MUST break work into small, verifiable tasks with concrete verification steps.
- Once the user explicitly approves the spec/plan or explicitly asks to execute that specific plan, the approved plan is authorization to execute all listed tasks continuously, subject to the stop conditions below. See §0.2 for approval examples and non-approval examples.

### 11.2 Continuous Execution Rule

- After an implementation plan is approved, choose the execution model according to the risk and scope rules in §11.7.
- Use `superpowers:subagent-driven-development` for high-risk or multi-area approved plans where isolated implementation and two-stage review materially reduce risk.
- Use `superpowers:executing-plans` for ordinary cohesive multi-file approved plans unless subagents are clearly beneficial.
- Do NOT stop after every individual task to ask whether to continue.
- Continue through the approved plan until all tasks are complete or a stop condition is reached.
- Run the verification and review steps required by the active `superpowers` workflow before moving between tasks or claiming completion.

### 11.3 Stop Conditions

Stop and ask the user before continuing when:

- Requirements are ambiguous enough that implementation would require guessing.
- The plan has a critical gap or conflicts with repository rules.
- The scope grows beyond the approved spec/plan.
- A blocker cannot be resolved with available context.
- Verification fails repeatedly.
- A dependency required for implementation is missing from `docs/context7-dependencies.yaml`.
- A destructive action is needed.
- A permission/escalation approval is required by the runtime environment.

### 11.4 Goal-Driven Execution

- Convert vague requests into verifiable goals before coding.
- For bug fixes, prefer a test or focused reproduction that fails before the fix and passes after it.
- For validation changes, include checks for invalid and boundary inputs when practical.
- For refactors, verify behavior before and after when the project provides suitable tests.
- Continue the implement/verify loop within the approved task or plan until the stated success criteria are met or a stop condition is surfaced.

### 11.5 Worktree Rule

- Before starting feature work or executing an implementation plan, follow `superpowers:using-git-worktrees`.
- Prefer platform-native worktree support when available.
- If a local worktree directory must be ignored for safe worktree usage, adding the necessary `.gitignore` entry is permitted.
- Such `.gitignore` changes should be committed as a setup commit using the repository commit message format.
- Do not start implementation on `main` or `master` without explicit user consent.

### 11.6 Commit Rule

- Setup commits for `.gitignore` changes required by `superpowers:using-git-worktrees` are allowed.
- Spec and plan document commits are allowed when created or updated through the approved `superpowers` documentation workflow.
- Codebase change commits require explicit user confirmation before committing.
- Do not commit source code, test code, configuration code, schema/migration files, scripts, or other runtime behavior changes merely because a `superpowers` skill or generated plan includes a commit step.
- If codebase changes are complete and verified but commit confirmation has not been granted, report that the codebase commit is pending user confirmation.

### 11.7 Subagent Rule

- Use `superpowers:subagent-driven-development` by default for approved plans that involve high-risk or multi-area changes, such as concurrency, data consistency, Redis Lua, Redis Streams, database schema, authentication, authorization/permission, billing/quota, transaction boundaries, migration, external integration, retry/idempotency, scheduler/time-based logic, batch processing, or broad refactoring.
- For ordinary cohesive multi-file changes, prefer `superpowers:executing-plans` unless tasks are independent enough to benefit from isolated implementation and two-stage review.
- For trivial single-file or documentation-only changes, do not use subagents.
- When subagents are used, every subagent prompt MUST include the requirement to follow this repository's `AGENTS.md`.
- When subagents are used, every subagent prompt MUST explicitly include the dependency/Context7 rules and the Korean documentation/review communication rule.
- When reviewer subagents are used, they MUST evaluate spec compliance before code quality.
- When reviewer-reported issues remain within the approved plan and do not trigger a stop condition, resolve them autonomously and re-run the required review/verification loop.
- When subagents are used, do not move to the next task while reviewer-reported spec compliance or important code quality issues remain unresolved. Ask the user only when a stop condition applies.

---

## 12. Self-Review Policy

After implementing an approved task or plan, the agent MUST NOT immediately report completion.
Instead, follow this sequence: **Implement → Verify → Self-Review → Fix → Report or Continue**.

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
- Only then report completion or continue to the next approved task according to the active `superpowers` workflow.

---

## 13. Reporting and Completion Format

All user-facing reports, review comments, review responses, PR text, and completion messages authored by the agent MUST be written in Korean.

Follow the active `superpowers` workflow for completion:

- Use `superpowers:verification-before-completion` before claiming work is complete, fixed, passing, or ready.
- Use `superpowers:finishing-a-development-branch` when implementation is complete and integration choices are needed.
- Present completion options in Korean while preserving the structure required by the `superpowers` skill.
- Include changed files, implemented behavior, verification results, self-review findings, simplicity/scope notes, and known risks when reporting completed work.
- Do not claim success without fresh verification evidence.

---

## 14. Change Scope Control

The purpose of task decomposition is to **control change scope** and maintain reviewability.

### 14.1 Scope Boundaries

- Do NOT modify code unrelated to the current approved task or plan.
- Refactoring is permitted ONLY within the current approved task or plan scope.
- Do NOT introduce new dependencies unless strictly necessary for the current approved task or plan.
- Do NOT add features, flexibility, configuration, or abstractions that were not requested.
- Do NOT add error handling for scenarios that cannot occur in the current design.
- Every changed line should trace directly to the user's request or to cleanup caused by that change.
- A `.gitignore` change required by `superpowers:using-git-worktrees` for local worktree safety is allowed as a setup change.

### 14.2 Reuse Over Duplication

- Prefer reusing existing project structures and patterns.
- Avoid duplicate implementations of logic that already exists.
- Match existing local style, even when another style would also be reasonable.
- If the implementation becomes much larger than the requirement warrants, simplify it before reporting.

### 14.3 Scope Escalation

- If the change scope starts growing beyond the approved task or plan boundary, **stop implementing**.
- Re-decompose the work into smaller tasks or a revised plan.
- Present the revised plan to the user before continuing.

### 14.4 Surgical Change Rules

- Do NOT improve adjacent code, comments, formatting, or naming unless it is required for the approved task or plan.
- Do NOT refactor unrelated code even if it looks imperfect.
- Remove imports, variables, functions, files, or comments only when they became unused because of the current change.
- If unrelated dead code or cleanup opportunities are noticed, mention them in the report instead of changing them.

### 14.5 Minimal Implementation Policy

All implementation MUST prefer the smallest change that fully satisfies the approved task or plan.

#### 14.5.1 Smallest Working Change Rule

- Implement only the minimum behavior required by the current approved task or plan.
- Do NOT generalize the solution for hypothetical future requirements.
- Do NOT introduce new abstractions, helper layers, interfaces, factories, strategies, configuration options, or extension points unless they are strictly required by the current approved task or plan.
- Do NOT make code "more flexible" unless flexibility is part of the explicit requirement.
- Prefer modifying existing code paths over creating parallel implementations.

#### 14.5.2 Complexity Justification Rule

Before adding any of the following, the agent MUST explicitly justify why a simpler solution is insufficient:

- New class
- New interface
- New configuration property
- New dependency
- New framework feature
- New utility/helper module
- Reflection, generics-heavy design, dynamic dispatch, or pattern-based abstraction
- Broad refactor touching files outside the approved task or plan scope

If the justification is weak or based on possible future needs, the change MUST NOT be made.

#### 14.5.3 Prefer Boring Code

- Prefer direct, readable, local code over clever or highly abstract code.
- Prefer explicit conditionals over premature polymorphism.
- Prefer existing project conventions over newly introduced patterns.
- Prefer a small duplication over a premature shared abstraction when reuse is uncertain.
- Prefer deleting unnecessary code over adding compatibility layers.

#### 14.5.4 Scope Growth Stop Rule

If the implementation starts requiring unrelated refactoring, new architecture, or broad changes, the agent MUST stop and report:

- Why the scope grew
- What simpler alternative exists
- Whether the task or plan should be split
- What user approval is needed before continuing

#### 14.5.5 Simplicity Self-Review Requirement

During self-review, the agent MUST answer:

- Can this be implemented with fewer files?
- Can this be implemented with fewer new concepts?
- Did I add any abstraction for a future case not required now?
- Did I modify unrelated code for cleanliness rather than necessity?
- Would a maintainer understand this change without reading extra documentation?

If the answer reveals unnecessary complexity, simplify before reporting completion.

---

## 15. Long-Running Task Management

For tasks that span multiple turns or sessions, use the `superpowers` documentation workflow to prevent context loss.

### 15.1 Superpowers Specs and Plans

- Write approved design specs to `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.
- Write implementation plans to `docs/superpowers/plans/YYYY-MM-DD-<feature-name>.md`.
- These spec and plan files are the source of truth for long-running `superpowers` work.
- New `superpowers`-managed work MUST NOT use `docs/plan/PLANS.md` unless the user explicitly asks for that legacy location.
- All spec and plan contents authored by the agent MUST be written in Korean.

### 15.2 Cross-Session Restoration

- Before continuing long-running work in a later session, read the relevant `docs/superpowers/specs/...` and `docs/superpowers/plans/...` files first.
- If session context and the saved spec/plan differ, treat the saved spec/plan as the persistent baseline and apply user-approved updates going forward.
- If an older task still uses `docs/plan/PLANS.md`, do not migrate it unless the user explicitly asks.

### 15.3 Update Discipline

- Keep spec and plan documents current when the user approves a change to scope, architecture, or task decomposition.
- Do not update planning documents for unrelated cleanup or speculative future work.
- Commit spec and plan document changes according to the repository commit message format when they are created or updated through the approved `superpowers` documentation workflow.
- Do not commit codebase task changes without explicit user confirmation.

---

## 16. Sequential Thinking Policy

Sequential Thinking MCP is a supplementary reasoning tool. It aids structured thinking but does NOT control execution.

### 16.1 Permitted Uses

- **Plan decomposition**: Breaking complex tasks into smaller tasks.
- **Self-review enhancement**: Structuring critical review of implemented code.
- **Decision analysis**: Evaluating trade-offs before proposing a plan.

### 16.2 Execution Boundary

- Sequential Thinking output is **never** authorization to execute all derived steps.
- Before a spec/plan is approved, Sequential Thinking output remains draft reasoning.
- After a user-approved spec/plan exists, execute according to the `superpowers` continuous execution workflow and the stop conditions in this document.
- Do not use Sequential Thinking output to bypass dependency/Context7 rules, safety requirements, verification, or scope control.

### 16.3 Role Definition

- Sequential Thinking is a **reasoning aid**, not an execution-control mechanism.
- It complements the Execution Policy (§11) and Self-Review Policy (§12) but does not override them.
- The stop conditions (§11.3) always take precedence over any Sequential Thinking output.

---

End of strict rules.
