# ValidationPlugin (`-Xvalidation`)

## Overview

Adds Bean Validation (JSR-380) annotations on generated JAXB **fields** from XSD multiplicity and simple-type facets: requiredness, collection size, string length, pattern, numeric bounds, digits, and `@Valid` for complex types.

The validation API package is auto-detected from the XJC classpath: `jakarta.validation` is preferred when present; otherwise `javax.validation`. Fails if neither API is available.

## Lifecycle

| Hook | Role |
|------|------|
| `postParseArgument` | Resolve jakarta vs javax validation API |
| `run` | Annotate matching fields from property model + XSOM schema components |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xvalidation` | flag | — | Enable the plugin |
| `-class-name` | regex (repeatable) | *(all)* | Fully-qualified class name filter |
| `-field-name` | regex (repeatable) | *(all)* | Field name filter |
| `-disable-valid` | boolean | `false` | Disable automatic `@Valid` on complex/collection properties |

## Behavior

### Presence / collection size

- Required attributes (non-primitive) → `@NotNull`.
- Element particles: `minOccurs >= 1` and not nillable → `@NotNull` (non-primitive).
- Collections: `@NotNull` when `minOccurs >= 1`; `@Size(min, max)` from particle bounds (`maxOccurs` unbounded is `-1` in XSOM and is not set as max).

### Facets (user-declared restrictions only)

Along the user restriction chain (stops at built-in XML Schema types so facets like `xs:integer` `fractionDigits=0` are ignored):

| XSD facet | Annotation |
|-----------|------------|
| `length` / `minLength` / `maxLength` | `@Size` on `String` fields |
| `pattern` | `@Pattern` (skips CXF noise pattern `\c+`) |
| `minInclusive` / `maxInclusive` | `@Min`/`@Max` for integer values, else `@DecimalMin`/`@DecimalMax` |
| `minExclusive` / `maxExclusive` | `@DecimalMin`/`@DecimalMax` with `inclusive = false` |
| `totalDigits` (+ optional `fractionDigits`) | `@Digits` |

### `@Valid`

Applied to fields whose property refs include a `CClassInfo`, unless `-disable-valid`.

Annotations are added only if absent on the field.

## Usage

```text
-Xvalidation
-Xvalidation -class-name=com\.example\..* -field-name=id|code
-Xvalidation -disable-valid=true
```

## Limitations / notes

Intentional limits:

- No `enumeration` / `whiteSpace` / `fixed`.
- Collection `@Size` is multiplicity only (not item string length).
- Only user-declared facets (built-in XML Schema type facets ignored).
- Field annotations only (not method-level constraints).

Classpath: add `jakarta.validation:jakarta.validation-api` (preferred) or `javax.validation:validation-api` to the XJC plugin dependencies.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/ValidationPlugin.java`
