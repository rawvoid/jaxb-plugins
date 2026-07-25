# Agent Conventions

Instructions for AI assistants working in this repository.

## 1. Workflow & Planning

- **Plan First (non-trivial)**: For non-trivial work (behavior changes, new modules or public APIs, unclear design), research thoroughly and produce a short implementation plan with atomic subtasks before modifying code.
- **Approval Gate (non-trivial)**: Do **NOT** start implementing non-trivial work until the user explicitly approves the plan.
- **Trivial / directed work**: For clearly scoped fixes, follow-ups on an already approved plan, or tasks the user has specified precisely, implement directly after stating the intended scope. Do not invent a heavyweight plan.
- **Smallest change**: Prefer the smallest change that satisfies the request; avoid unrelated refactors and scope creep.

## 2. Code & Research

- **Simplicity & Elegance**: Avoid over-engineering, unnecessary code, and speculative abstractions. Keep architecture flat and control flow obvious. Write concise, readable, and elegant code with clear structure. Drop unused branches and defensive code for impossible states.
- **Modern Java**: Target **Java 21+**. Use current Java features (`var` for obvious types, records, pattern matching, sequenced collections, text blocks) when they improve clarity.
- **Dependencies**: Do NOT introduce new external libraries or frameworks without explicit user approval.
- **Class Imports**: In Java sources and Javadoc, prefer explicit `import` statements over fully qualified names. Use FQCN only on name collision or in non-source contexts (SPI files, diagnostic strings, config that requires a type name).
- **Local Source Inspection**: Prioritize reading local Maven repository (`~/.m2/repository`) source JARs over web searches when investigating third-party APIs. If local source JARs are missing, run `mvn dependency:sources` to download them before attempting online search.
- **Code Hygiene**: No dead code, unused parameters, swallowed exceptions, or redundant comments. Code comments and Javadoc MUST be in **English only**.

## 3. Testing & Verification

- **Execution Requirement**: When code or tests change, run and verify the smallest relevant Maven test suite before declaring work done (e.g., `mvn test` or `mvn test -Dtest=...`). Docs-only or other non-code changes do not require a test run unless they affect the build or CI.
- **Test Integrity**: **NEVER** delete failing tests or comment out broken assertions to pass build checks.

## 4. Git & Remote Actions

- **Conventional Commits**: Git commit messages and PR titles MUST adhere to the Conventional Commits specification.
- **Atomic Local Commits**: Commit locally as coherent, verified units of work (tests green when code changed). Avoid combining unrelated changes; do not force a commit after every exploratory substep.
- **Commit when done**: After a coherent unit of work is complete and verified (relevant tests green when code changed), create a local commit using Conventional Commits. Do **not** wait for the user to ask. Applies to any meaningful change set, including docs-only. Skip auto-commit if the user asked not to commit, if verification failed, or if there are no meaningful changes.
- **Remote Operations**: **NEVER** `git push`, force-push, amend published history, or open/update PRs unless explicitly instructed by the user.
- **Secrets**: **NEVER** commit secrets, tokens, or credentials.

## 5. Communication

- **Language**: Write user-facing replies in the dominant natural language of the recent conversation. If the user clearly switches language for a full message, follow that switch; do not treat short acknowledgements (e.g. "ok", "lgtm") as a language change. Keep code, comments, Javadoc, commit messages, and PR titles in English unless the user explicitly requests otherwise.
- **Clarity**: State trade-offs concisely. Ask when requirements or design choices are ambiguous; otherwise proceed once the direction is clear within the workflow rules above.
- **Completion**: When done, briefly report what changed, how it was verified (e.g., test command run), and the local commit hash if a commit was created.
