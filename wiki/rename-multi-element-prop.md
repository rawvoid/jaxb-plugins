# RenameMultiElementPropPlugin (`-Xrename-multi-element-prop`)

## Overview

Renames multi-element properties produced by XJC to a short plural base name. XJC often generates long compound names for choice / multi-type lists; this plugin rewrites them to a stable plural base with numeric suffixes when needed.

**Do not enable together with** `-Xflatten-multi-element-prop` — one renames while the other splits.

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Detect multi-element properties and rename them |
| `run` | No-op |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xrename-multi-element-prop` | flag | — | Enable the plugin |
| `-name` | string | `items` | Plural base name for renamed properties |

Invalid or blank `-name` falls back to `items` (with a warning when non-blank but invalid).

## Behavior

### What is multi-element?

A property is multi-element when it binds more than one element/type:

- `CElementPropertyInfo` with `getTypes().size() > 1` (typically `@XmlElements`)
- `CReferencePropertyInfo` with `getElements().size() > 1` (typically `@XmlElementRefs`, including multi-member XJC `rest`/`content` catch-alls)

Single-element lists and single-member catch-alls are left unchanged.

### Naming

- Within a bean, multi-element properties are sorted by original private name (case-insensitive) for stable ordering.
- Allocated names: base, then base+2, base+3, … (`items`, `items2`, `items3`, …) free of clashes with existing properties (case-insensitive).
- Private and public names are derived via the standard `NameConverter`.
- Field renames do not affect `ObjectFactory` (which keys on class squeezed names).

## Usage

```text
-Xrename-multi-element-prop
-Xrename-multi-element-prop -name=choices
```

## Limitations / notes

- Mutually exclusive with `-Xflatten-multi-element-prop`.
- Base name must form valid Java identifiers for both private and public forms after `NameConverter` processing.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/RenameMultiElementPropPlugin.java`
