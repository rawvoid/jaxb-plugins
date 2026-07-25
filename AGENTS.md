# Agent Conventions

Instructions for AI assistants working in this repository.

## 1. Workflow & Planning

- **Plan First**: Always conduct thorough research and create an explicit implementation plan before modifying code.
- **Task Breakdown**: Break down complex tasks into atomic, logically structured subtasks within the implementation plan.
- **Approval Gate**: Do **NOT** start code implementation until receiving explicit approval from the user on the implementation plan.

## 2. Code & Research

- **Simplicity & Elegance**: Avoid over-engineering, over-coding, and speculative abstractions. Keep architecture flat and control flow obvious. Write concise, readable, and elegant code with clear structure. Drop unused branches and defensive code for impossible states.
- **Modern Java**: Target **Java 21+**. Use current Java features (`var` for obvious types, records, pattern matching, sequenced collections, text blocks) when they improve clarity.
- **Dependencies**: Do NOT introduce new external libraries or frameworks without explicit user approval.
- **Local Source Inspection**: Prioritize reading local Maven repository (`~/.m2/repository`) source JARs over web searches when investigating third-party APIs. If local source JARs are missing, run `mvn dependency:sources` to download them before attempting online search.
- **Code Hygiene**: No dead code, unused parameters, swallowed exceptions, or redundant comments. Code comments and Javadoc MUST be in **English only**.

## 3. Testing & Verification

- **Execution Requirement**: Always run and verify the smallest relevant Maven test suite before declaring work done (e.g., `mvn test` or `mvn test -Dtest=...`).
- **Test Integrity**: **NEVER** delete failing tests or comment out broken assertions to pass build checks.

## 4. Git & Remote Actions

- **Atomic Local Commits**: Commit locally after completing each coherent, atomic subtask. Avoid combining unrelated changes into a single bulk commit.
- **Accurate Commit Messages**: Write conventional commit messages and PR titles in **English only**.
- **Remote Operations**: **NEVER** `git push`, force-push, amend published history, or open/update PRs unless explicitly instructed by the user.
- **Secrets**: **NEVER** commit secrets, tokens, or credentials.

## 5. Communication

- **Efficiency**: State trade-offs concisely; proceed with implementation once direction is clear.
