# Agent conventions

Instructions for AI assistants working in this repository.

## Priorities

1. **Simple and elegant first.** Readable structure, minimal moving parts, obvious control flow. Prefer a short clear solution over a “complete” or clever one. Drop unused branches, speculative extension points, and defensive code that only guards impossible states.
2. **Use modern Java.** This project targets **Java 21+**. Prefer current language and standard-library features the project JDK already supports (e.g. `var`, records, pattern matching, sequenced collections, text blocks) when they make the code clearer—not novelty for its own sake.

Everything below serves these two priorities.

## Project context

- Maven multi-module JAXB / XJC plugins; main code lives in `plugins`.
- Match neighboring style; do not introduce new frameworks without need.
- Verify XJC / generated behavior with schemas and tests under `plugins/src/test`.

## Java style

- Local variables: prefer `var` when the type is obvious from the initializer.
- Keep methods small; extract non-trivial decisions into well-named helpers with brief *why* comments.
- Favor straightforward APIs over deep abstraction layers.
- No dead code, unused parameters, or comments that merely restate the next line.
- **Code comments and Javadoc: English only.**

## Design when changing XJC / generated code

- Prefer official public entry points over reflection; localize reflection only when package-private APIs leave no better option.
- Preserve observable order (properties, fields, `propOrder`) when rewriting models or structure.
- Align generated annotations with stock XJC defaulting (`##default`, `package-info`) instead of restating redundant members.
- Document intentional limitations in Javadoc rather than half-implementing unsupported cases.

## Git and remote actions

- After a coherent unit of work, make a **local** commit without waiting to be asked.
- Use conventional, complete-sentence messages (e.g. `feat(plugin): …`, `fix: …`).
- Do **not** `git push`, force-push, amend published history, or open/update PRs unless the user explicitly asks.
- Never commit secrets.

## Testing

- Prefer existing harnesses (`AbstractXJCMojoTestCase` and related helpers).
- Behavior changes need focused tests; run the smallest relevant Maven suite before calling work done.
- Typical: `mvn -pl plugins test` or `mvn -pl plugins test -Dtest=…`.

## Communication

- **Reply in the same language the user is using.** Code comments / Javadoc stay English (see above).
- When reviewing design, state trade-offs briefly; implement once direction is clear.
- If the user corrects an assumption, update code and conventions accordingly.
- Keep commit and PR text proportional to the change: *what* and *why*.
