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
- Walks **all** packages in the CodeModel (not only outline package contexts), then BFS over nested classes under each package that defines classes.
- Covers schema beans, `ObjectFactory`, XJC `AdapterN` from `jaxb:javaType`, and adapters placed in other packages (for example `*.adapter` from `-Xjava-time`).
- Default generator name is `"JAXB RI v" + Options.getBuildID()`.

## Usage

```text
-Xgenerated-anno
-Xgenerated-anno -value=my-codegen -comments="from schema" -date=true

# With plugins that create adapters in run(), put generated-anno last:
-Xjava-time -Xgenerated-anno
```

## Limitations / notes

- Uses the Jakarta annotation FQCN `jakarta.annotation.Generated` (not `javax.annotation.Generated`).
- Date is calendar date only (`LocalDate`), not a full timestamp.
- Plugin order matters for adapters created by other plugins during `run`: enable `-Xgenerated-anno` after those plugins so the adapters already exist.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/GeneratedAnnoPlugin.java`
