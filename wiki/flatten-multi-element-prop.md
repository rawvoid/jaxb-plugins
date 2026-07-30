# FlattenMultiElementPropPlugin (`-Xflatten-multi-element-prop`)

## Overview

Flattens multi-element properties into individual single-element properties. XJC merges multiple alternative elements (from `xs:choice` or inheritance collisions) into a single list property annotated with `@XmlElements` or `@XmlElementRefs`. This plugin splits such properties back into individual fields — one per bound element — preserving the original position in the property order.

**Do not enable together with** `-Xrename-multi-element-prop` — one splits while the other renames.

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Split multi-element properties in place |
| `run` | No-op |

## Options

| Option | Description |
|--------|-------------|
| `-Xflatten-multi-element-prop` | Enable the plugin |

No sub-options.

## Behavior

### Handled shapes

1. **`CElementPropertyInfo` with `getTypes().size() > 1` (`@XmlElements`)**  
   Each `CTypeRef` becomes its own `CElementPropertyInfo`.

2. **`CReferencePropertyInfo` with `getElements().size() > 1` (`@XmlElementRefs`)**  
   Each `CElement` is converted to a `CElementPropertyInfo` when possible (avoiding `JAXBElement` wrappers), falling back to a single-element `CReferencePropertyInfo` otherwise. If any element cannot be named (`getElementName() == null`), flattening of that property is aborted.

### Naming

- New private names are derived from the XML local name via the standard `NameConverter`, with clash-safe allocation against other properties on the bean.
- Collection mode is preserved: repeated vs non-repeated from the original multi property.

### Property order

The multi property is replaced at its index with the list of single-element properties so `propOrder` / field order stay aligned with the original position.

## Usage

```text
-Xflatten-multi-element-prop
```

## Limitations / notes

- Mutually exclusive with `-Xrename-multi-element-prop`.
- Abort path for unnameable reference elements leaves the original multi property unchanged for that case.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/FlattenMultiElementPropPlugin.java`
