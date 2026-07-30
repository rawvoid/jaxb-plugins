# DedupeClassPlugin (`-Xdedupe-class`)

## Overview

Merges structurally redundant generated beans in `postProcessModel`. Candidates share an owner package and a **name key** (`AircraftCodeType` ≡ `AircraftCode` after stripping a trailing `Type` when the remainder is non-empty). Passes alternate until a fixed point.

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Exact merges, then related (empty extension / optional subset) merges; rewrite references; remove victims |
| `run` | No-op |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xdedupe-class` | flag | — | Enable the plugin |
| `-merge-subset` | boolean | `false` | Merge subset beans into superset hosts |
| `-anonymous-only` | boolean | `true` | Only **delete** anonymous beans; named types may still be hosts |
| `-dry-run` | boolean | `false` | Log planned merges without changing the model |
| `-preserve-wrapper-shells` | tri-state boolean | **auto** | Do not merge pure collection-wrapper shells into non-shell hosts |

### `preserve-wrapper-shells` (tri-state)

- Explicit `true` / `false` force the behavior.
- Default **auto**: on when `-Xelement-wrapper` is also in `Options.activePlugins`, so later wrapper flatten opportunities remain when Dedupe runs first.
- Shell-to-shell exact merges remain allowed even when preserve is on.

## Behavior

### Pass order (until fixed point)

1. **Exact** — cycle-safe structural equality. Nested bean/enum targets must share package + name key (isomorphic but differently named types are not interchangeable).
2. **Related** — empty extension of the host (always), and optional property-subset merges when `-merge-subset`.

### Host preference

Hosts are preferred by score: named over anonymous, package-level over nested, more properties, short names ending in `Type` (tie-breaker details in source).

### Nested types

Nested beans under a merge pair are aligned before the outer merge; nested enums are merged or re-parented without short-name collisions. Element-class cleanup is scoped to `package + nameKey` pairs involved in merges.

### Name key

`nameKey`: strip a trailing `Type` when the remainder is non-empty. Case-sensitive (`AnyType` → `Any`; `Prototype` is unchanged).

## Usage

```text
-Xdedupe-class
-Xdedupe-class -merge-subset=true
-Xdedupe-class -anonymous-only=false
-Xdedupe-class -dry-run=true
-Xdedupe-class -preserve-wrapper-shells=true
```

With element wrapper:

```text
-Xdedupe-class -Xelement-wrapper
```

(`preserve-wrapper-shells` auto-enables)

## Limitations / notes

- By default only anonymous beans are deleted as victims; named types can still act as hosts.
- Prefer `-Xelement-wrapper` after other model-mutating plugins; see [element-wrapper](element-wrapper.md).

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/DedupeClassPlugin.java`
