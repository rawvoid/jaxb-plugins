# RenameClassPlugin (`-Xrename-class`)

## Overview

Renames generated class-like types in `postProcessModel`. Unlike `-Xconvert-name`, which installs a `NameConverter` during schema binding, this plugin rewrites model short names after the model is complete so conflicts can be simulated and reported together instead of aborting on the first collision.

**Scope:** `CClassInfo` beans, `CEnumLeafInfo` enums, and `CElementInfo` instances with `hasClass()`. Beans, enums, and element classes share one simple-name namespace under each parent (package or outer class). Name checks are case-insensitive for short names.

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Apply mapping pipeline, detect conflicts, write short names |
| `run` | No-op |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xrename-class` | flag | — | Enable the plugin |
| `-mapping` | mapping (repeatable) | — | Class name mapping (package filter + from pattern + to) |
| `-strip-type-suffix` | boolean flag | off | Strip trailing `Type` from **named** schema types only |

If no mappings and strip is off, the plugin does nothing.

### Mapping config (`MappingConfig`)

Compact: `/{from}/->{to}` or `{from}->{to}`.

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-package` | no | Exact owner package name filter |
| `-from` | yes | Regex matching the **current intermediate** short name |
| `-to` | yes | Target short name (`$n` replacement groups allowed) |

## Behavior

### Mapping pipeline

1. Explicit `-mapping` entries run first, in declaration order, as a single forward pass.
2. Each rule matches against the **current intermediate** short name (not only the original), so multi-step renames work (e.g. strip `Type` then strip `IATA`).
3. A rule that would produce a non-identifier is skipped with a warning; later rules still run.
4. Optional package filters always use the type’s owner package.
5. Mappings apply to every candidate (including anonymous / element-derived classes).

### Named-type `Type` suffix strip

Optional `-strip-type-suffix` removes a trailing `Type` from the short name of **named** schema types only (`getTypeName()` / enum type name non-null and local part ends with `Type`, including `Foo_Type`). Anonymous types and element classes are skipped so element names like `ActionType` are not mis-renamed. Eligibility uses the schema type name; replacement applies to the current short name after mappings.

Prefer this flag over a bare `-mapping=^(.+)Type$->$1` when schemas mix type-suffix conventions with element names ending in `Type`.

### Conflict policy

Conflicting types keep their **original** names; non-conflicting renames still apply. Conflicts are **warnings** (build does not fail):

- Same simple name under one parent (beans / enums / element classes)
- An ancestor and a nested type sharing the same simple name after rename
- Duplicate `CClassInfo.getSqueezedName()` values in a package (ObjectFactory value-factory methods); parent renames that cause nested squeezed-name collisions are rolled back

## Usage

```text
-Xrename-class -mapping=Person->CustomPerson
-Xrename-class -mapping=/(.*)Type/->$1
-Xrename-class -strip-type-suffix
-Xrename-class -mapping -package=com.example -from=Foo -to=Bar -strip-type-suffix
```

## Limitations / notes

- When both this plugin and `-Xpromote-nested-class` are active: rename-before-promote lets named global types claim short names first; promote-first is safer for some ObjectFactory edge cases but can leave dual names (`Foo` + `FooType`).
- Bare short name `Type` is not stripped by the suffix pattern (`^(.+)Type$` requires a non-empty prefix).

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/RenameClassPlugin.java`
