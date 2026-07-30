# JacksonPlugin (`-Xjackson`)

## Overview

Adds common Jackson annotations to generated JAXB classes. Zero-config usage annotates every generated class with `@JsonInclude(NON_NULL)` and `@JsonIgnoreProperties(ignoreUnknown = true)` when those annotations are not already present.

Jackson types are referenced only by FQCN so the plugin can load via XJC SPI without `jackson-annotations` on the classpath. That dependency is required only when `-Xjackson` is **enabled**.

## Lifecycle

| Hook | Role |
|------|------|
| `postParseArgument` | Require Jackson annotations on the XJC classpath; validate `-include` |
| `run` | Apply built-in and extra annotations on matching classes |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xjackson` | flag | — | Enable the plugin |
| `-include` | string | `NON_NULL` | `JsonInclude.Include` name, or `none` to skip `@JsonInclude` |
| `-ignore-unknown` | boolean | `true` | Add `@JsonIgnoreProperties(ignoreUnknown = true)` when true |
| `-class-name` | regex (repeatable) | *(all classes)* | Fully-qualified class name filter |
| `-anno` | annox annotation (repeatable) | — | Extra class-level annotations |

### Known `-include` values

`ALWAYS`, `NON_NULL`, `NON_ABSENT`, `NON_EMPTY`, `NON_DEFAULT`, `CUSTOM`, `USE_DEFAULTS`, or `none`.

## Behavior

- Built-in `@JsonInclude` / `@JsonIgnoreProperties` are **skipped** when already present (not replaced).
- User `-anno` values use normal non-repeatable replace semantics via annox application.
- Empty or blank `-include` is treated as `NON_NULL`.

## Usage

```text
-Xjackson
-Xjackson -include=NON_EMPTY -ignore-unknown=false
-Xjackson -class-name=com\.example\..*Dto -anno=@com.fasterxml.jackson.annotation.JsonIgnoreType
```

## Limitations / notes

Intentional MVP limits:

- Class-level annotations only; no field `@JsonProperty` from XML names.
- No `@JsonFormat`, `@JsonPropertyOrder`, `@JsonRootName`, or type info.
- Does not configure `ObjectMapper`; consumers must provide `jackson-annotations` at generation and compile time when this plugin is used.

Classpath requirement when enabled:

```text
com.fasterxml.jackson.core:jackson-annotations
```

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/JacksonPlugin.java`
