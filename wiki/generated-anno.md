# GeneratedAnnoPlugin (`-Xgenerated-anno`)

## Overview

Annotates all generated packages and classes with `@jakarta.annotation.Generated`, including nested classes.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Adds `@Generated` on each package (`package-info`) and every class in the package tree |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xgenerated-anno` | flag | — | Enable the plugin |
| `-value` | string | `JAXB RI v[BuildID]` | `value` attribute of `@Generated` |
| `-comments` | string | *(none)* | `comments` attribute |
| `-date` | boolean | `false` | When true, set `date` to `LocalDate.now()` (ISO date string) |

## Behavior

- Skips a target that already has `@jakarta.annotation.Generated`.
- Walks packages via outline package contexts, then BFS over nested classes under each package.
- Default generator name is `"JAXB RI v" + Options.getBuildID()`.

## Usage

```text
-Xgenerated-anno
-Xgenerated-anno -value=my-codegen -comments="from schema" -date=true
```

## Limitations / notes

- Uses the Jakarta annotation FQCN `jakarta.annotation.Generated` (not `javax.annotation.Generated`).
- Date is calendar date only (`LocalDate`), not a full timestamp.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/GeneratedAnnoPlugin.java`
