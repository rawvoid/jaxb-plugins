# Agent conventions

Instructions for AI assistants working in this repository. Keep changes focused, follow existing patterns, and prefer clarity over cleverness.

## Project context

- Java 21+, Maven multi-module project for JAXB / XJC plugins (`plugins` module is the main code).
- Generated code and XJC behavior should be verified against real schemas and tests under `plugins/src/test`.
- Prefer matching the style of neighboring code over introducing new frameworks or patterns.

## Git and remote actions

- After finishing a coherent unit of work, create a **local** git commit (do not wait to be asked).
- Use conventional, complete-sentence commit messages (e.g. `feat(plugin): …`, `fix(plugin): …`, `refactor: …`).
- **Do not** `git push`, force-push, amend published history, or open/update GitHub PRs unless the user explicitly asks.
- Never commit secrets (API keys, tokens, credentials).

## Java code style

- Prefer `var` for local variables when the type is clear from the right-hand side.
- Keep methods small; extract non-trivial predicates or multi-step decisions into named private methods with short comments explaining *why*.
- Avoid redundant defensive code when the caller already established the invariant (no re-scan “just in case” without a real failure mode).
- Prefer simple control flow over nested abstraction layers.
- Do not leave dead branches, unused parameters, or comments that restate the code.

## Design principles

- Solve the stated problem; do not invent multi-pass, multi-strategy, or “future-proof” machinery that the current requirements do not need.
- When extending XJC / Codemodel / JAXB APIs, prefer official public entry points over reflection. Use reflection only when package-private APIs block a correct design, and keep it localized.
- Preserve observable ordering (e.g. property / field / `propOrder` order) when rewriting models or generated structure.
- Generated annotations should follow the same defaulting rules as stock XJC where possible (omit members that `##default` or `package-info` already cover).
- Document intentional limitations in Javadoc rather than implementing incomplete stand-ins.

## Testing and verification

- Prefer existing test harnesses under `plugins/src/test` (`AbstractXJCMojoTestCase` and related helpers).
- For behavior changes, add or update focused tests; run the smallest relevant Maven test set before considering work done.
- Typical check: `mvn -pl plugins test` or `mvn -pl plugins test -Dtest=…`.

## Communication

- Explain trade-offs briefly when the user is reviewing design; implement after direction is clear.
- When the user corrects an assumption, adjust the code and conventions accordingly instead of defending a weaker approach.
- Keep PR / commit descriptions proportional to the change; focus on *what* and *why*.
