# RemoveUnusedClassPlugin (`-Xremove-unused-class`)

## Overview

Removes unreferenced JAXB classes and enums from the model during `postProcessModel`. Uses a graph reachability analysis (mark & sweep) starting from global XML root elements and user-configured white-list patterns to identify and prune unreachable classes and enums.

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Collect roots, traverse reachability, remove dead beans/enums and orphan element infos |
| `run` | No-op |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xremove-unused-class` | flag | — | Enable the plugin |
| `-keep-classes` | regex (repeatable) | — | Forcibly keep matching classes or enums as roots (`find` on full name or short name) |
| `-preserve-polymorphism` | boolean | `false` | Treat subclasses of a reachable base class as reachable |
| `-verbose` | boolean | `false` | Detailed logging of reachability and deleted types |

## Behavior

### Root set

1. Global `CElementInfo` declarations (content type + property refs).
2. Beans marked as root elements (`isElement` or non-null `elementName`).
3. Types matching any `-keep-classes` pattern.

### Reachability edges

From a `CClassInfo`:

- Superclass
- Subclasses when `-preserve-polymorphism`
- Property refs
- Adapter types (`adapterType` / `customType` when they are model type infos)

From a `CElementInfo`: content type and property refs.

Only `CClassInfo`, `CEnumLeafInfo`, and `CElementInfo` targets are tracked as reachable.

### Cleanup

- Dead beans removed via `ModelUtils.removeClass`.
- Dead enums removed via `ModelUtils.removeEnum`.
- Orphan `CElementInfo` objects whose content type was a dead class/enum are removed.

## Usage

```text
-Xremove-unused-class
-Xremove-unused-class -keep-classes=com\.example\.KeepMe
-Xremove-unused-class -preserve-polymorphism=true -verbose=true
```

## Limitations / notes

- Without `-preserve-polymorphism`, unused subclasses of a reachable base are pruned even if the base is kept.
- Patterns use `Matcher.find()` (substring match), not full-string `matches()`.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/RemoveUnusedClassPlugin.java`
