# CommonInterfacePlugin (`-Xcommon-interface`)

## Overview

Detects common bean properties across user-selected sets of generated classes and emits Java interfaces declaring their accessors. Matching classes then `implements` those interfaces. Fields and XML bindings are left untouched.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Intersect properties, generate interfaces, implement them on matched classes |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xcommon-interface` | flag | — | Enable the plugin |
| `-group` | group config (required, repeatable) | — | One interface generation unit |

### Group config (`GroupConfig`)

Compact form: `{class}->{interface}` (single class pattern).

Structured form supports multiple `-class` patterns and optional `-fields`.

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-class` | yes (repeatable in structured form) | Regex against generated class FQCN |
| `-interface` | yes | FQCN of the interface to generate |
| `-fields` | no | Comma-separated Java property names; omit for full intersection |

## Behavior

Per group:

- Property discovery uses the field model (`FieldOutline`), not whether XJC accessors are still present in source — works after `-Xlombok` strips methods.
- Declared types are taken from the CodeModel field (after plugins such as `-Xjava-time`), not from `FieldOutline#getRawType()`.
- **Without** `@lombok.Data`: XJC-style accessors. Getter always; setter only for non-collection properties (stock XJC has no `set` for live lists).
- **With** `@Data` (already on the class, or applied by an active `LombokPlugin` with default/`@Data` annos): Lombok-style names via `LombokAccessors`, and setters for collections too. Detection does **not** depend on plugin `run` order.
- Setter is declared only when every participating class will have a same-signature setter.
- Zero class matches → error. Empty common property set → warning, skip that group.
- Duplicate `-interface` FQCN across groups → error.
- Naming follows Lombok *defaults* (no project `lombok.config` / `@Accessors`).

## Usage

Compact groups:

```text
-Xcommon-interface \
  -group=.*Request->com.example.CommonRequest \
  -group=.*Response->com.example.CommonResponse
```

Structured groups (needed for `-fields` or multiple `-class` patterns):

```text
-Xcommon-interface \
  -group \
  -class=.*Request \
  -interface=com.example.CommonRequest \
  -fields=id,timestamp \
  -group \
  -class=.*Response \
  -interface=com.example.CommonResponse
```

## Limitations / notes

- Does not rewrite XML annotations or field layout.
- Interface must not already exist in the CodeModel.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/CommonInterfacePlugin.java`
