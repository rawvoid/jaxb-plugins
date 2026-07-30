# LombokPlugin (`-Xlombok`)

## Overview

Adds Lombok annotations to generated JAXB classes and optionally removes XJC-generated getters/setters.

Zero-config usage (`-Xlombok`) annotates every generated class with `@lombok.Data` and removes getters and setters so Lombok owns them.

Does not generate bytecode; consumers must provide Lombok and annotation processing at compile time. Lombok annotation classes must be visible to the XJC process (same as `-Xannotate` with Lombok).

## Lifecycle

| Hook | Role |
|------|------|
| `postParseArgument` | Reject simultaneous `-builder` and `-super-builder` |
| `run` | Resolve annotations, apply them, optional `@Singular`, remove accessors |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xlombok` | flag | — | Enable the plugin |
| `-anno` | annox annotation (repeatable) | `@lombok.Data` when omitted | Lombok (or other) annotations to add |
| `-class-name` | regex (repeatable) | *(all)* | Fully-qualified class name filter |
| `-remove-getter` | boolean | `true` | Remove generated getter methods |
| `-keep-list-getter` | boolean | `false` | When removing getters, keep XJC getters for List/collection properties (lazy-init live list) |
| `-remove-setter` | boolean | `true` | Remove generated setter methods |
| `-builder` | boolean | `false` | Smart builders: `@Builder` or `@SuperBuilder` by inheritance; `@Singular` on collections (**exclusive** with `-super-builder`) |
| `-super-builder` | boolean | `false` | `@SuperBuilder(toBuilder=true)` on every matched class; `@Singular` on collections (**exclusive** with `-builder`) |

## Behavior

### Default annotations

- If `-anno` is omitted → `@lombok.Data`.
- When the class has a non-`Object` superclass and the resolved set includes `@Data`, auto-add `@EqualsAndHashCode(callSuper = true)` unless the user already supplies `@EqualsAndHashCode`.

### Builder modes (mutually exclusive)

- **`-builder` (smart mix):**
  - Standalone concrete types → `@Builder(toBuilder = true)`
  - Types in an inheritance chain (non-`Object` super, including episode/external parents, or having generated subclasses) → `@SuperBuilder(toBuilder = true)`
- **`-super-builder`:** every matched class gets `@SuperBuilder(toBuilder = true)` (including abstract types). No inheritance heuristic.
- Either mode also adds `@NoArgsConstructor` / `@AllArgsConstructor` as appropriate (AllArgs only when there are fields and the class is not abstract), and annotates `Collection`/`Map` fields with `@Singular(ignoreNullCollections = true)` (singular names via Lombok `Singulars.autoSingularize`; explicit field name when auto fails).
- `@SuperBuilder` remains under `lombok.experimental`.

### Accessors

- By default all XJC getters are removed, including collection getters that lazy-init a live `List`. Lombok then returns the field as-is (may be `null`). Use `-keep-list-getter` to retain those XJC list getters while still stripping scalar getters.

### Interaction with CommonInterfacePlugin

`LombokPlugin.appliesDataTo` / `anyActiveAppliesData` allow other plugins to detect whether `@Data` will apply without depending on `run` order.

## Usage

```text
-Xlombok
-Xlombok -anno=@lombok.Value -remove-getter=true -remove-setter=true
-Xlombok -builder=true -keep-list-getter=true
-Xlombok -super-builder=true -class-name=com\.example\..*
```

## Limitations / notes

- See class Javadoc “Intentional limitations” in source for full list.
- Requires Lombok types on the XJC classpath when enabled.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/LombokPlugin.java`
