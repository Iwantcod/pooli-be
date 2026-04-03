# AI Agent Operating Rules (Strict Mode)

This repository enforces strict dependency + Context7 control.

You MUST follow these rules.

---

## 0. Rule Priority

If rules conflict, follow this priority order:

1. `AGENTS.md` in this repository
2. System/developer runtime instructions
3. General model defaults and assumptions

When conflict exists, do not guess. State which rule is applied.

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
- Do NOT invoke Context7 more than once per dependency per thread.

Exception:

- If the discussion transitions to a DIFFERENT dependency
  (for example, Spring Security -> Redis -> AWS SDK),
  a new Context7 call is allowed for that dependency.

Repeated calls for the SAME dependency in the same thread are prohibited.

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

- Write comments kindly and clearly so maintainers can understand intent and flow quickly.
- Prioritize comments for non-obvious logic, branching reasons, and important constraints.

---

End of strict rules.

---

# Additional Agent Requirement

The user requests the following requirement:

- For code written by the AI agent, write comments kindly and clearly so maintainers can understand intent and flow quickly.
