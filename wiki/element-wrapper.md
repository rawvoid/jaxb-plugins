# ElementWrapperPlugin (`-Xelement-wrapper`)

## Overview

Flattens single-collection wrapper types into `List` properties with `@XmlElementWrapper`.

Structural rewrites happen in `postProcessModel` so BeanGenerator emits collection fields and item `@XmlElement` annotations. `run` only adds `@XmlElementWrapper`, which XJC never generates (`CElementPropertyInfo#getXmlName()` always returns null).

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Find pure collection-shell wrappers; replace owner properties; optional wrapper class removal; record flatten metadata |
| `run` | Annotate flattened fields with `@XmlElementWrapper` (nillable/required as captured) |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xelement-wrapper` | flag | — | Enable the plugin |
| `-remove-wrapper-class` | boolean | `true` | Remove unused wrapper classes after flattening |

## Behavior

### What is a wrapper?

A wrapper is a **pure collection shell** (`ModelUtils.isPureCollectionShell`): a non-collection property whose type is a class with exactly one non-value-list collection element property and no base class (broader shapes are not flattened). Nested “list of wrappers” is not recursively unwrapped.

### Accepted outer property shapes

1. **`CElementPropertyInfo`** — ordinary element binding (non-nillable optional or required shells).
2. **`CReferencePropertyInfo`** with a single local `CElementInfo` whose content type is the wrapper — XJC default for nillable + optional complex elements (`JAXBElement<Wrapper>`). That shape is rewritten into a repeated element list with `@XmlElementWrapper(nillable = true)` and the synthetic local element info is dropped.

### Removal

When `-remove-wrapper-class` is true, wrapper classes that are no longer referenced are removed from the model.

### Stale flatten records

Flatten records keep the owner `CClassInfo` identity. If a later model plugin merges that owner away (typical dedupe), `run` skips the stale record with an info log. Prefer running this plugin’s model phase **after** other plugins that mutate the C* model (dedupe, promote, rename, flatten-multi). Never use `Outline#getClazz` to test liveness — it can lazily resurrect outlines for beans already removed from `model.beans()`.

## Usage

```text
-Xelement-wrapper
-Xelement-wrapper -remove-wrapper-class=false
```

Recommended ordering with other model plugins:

```text
… -Xdedupe-class -Xrename-class … -Xelement-wrapper
```

## Limitations / notes

- Scope is limited to pure collection shells; broader nested types are intentionally not flattened.
- Works with `-Xdedupe-class -preserve-wrapper-shells` (auto-on when this plugin is active) so dedupe does not merge shells into non-shell hosts before flatten.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/ElementWrapperPlugin.java`
