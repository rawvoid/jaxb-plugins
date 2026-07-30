# JavaTimePlugin (`-Xjava-time`)

## Overview

Enables modern `java.time` (and related) types on generated JAXB classes. For matching fields, the plugin rewrites field/accessor types and attaches `@XmlJavaTypeAdapter`, either auto-generating adapter classes or using a user-supplied adapter.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Map fields by XSD type / config, rewrite types, generate or attach adapters |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xjava-time` | flag | — | Enable the plugin |
| `-adapter-package` | string | common package of generated classes + `.adapter` | Package for auto-generated `XmlAdapter` classes |
| `-type-mapping` | mapping config (repeatable) | — | Override mapping for matching XSD types or fields |

### Type mapping config (`TypeMappingConfig`)

| Nested option | Description |
|---------------|-------------|
| `-xsd-type` | XSD built-in local name (e.g. `dateTime`, `date`, `gDay`) |
| `-target-type` | Target Java class (typically from `java.time`) |
| `-format` | `DateTimeFormatter` pattern for auto-generated adapter |
| `-adapter` | Custom `XmlAdapter` class instead of auto-generated |
| `-field` | Regex against fully-qualified field name `BeanClass.field` (repeatable) |

A mapping matches when field filters (if any) match **and** `xsd-type` is empty or equals the property’s schema type local part. First matching config wins.

## Behavior

### Default XSD → Java mapping

| XSD type | Java type |
|----------|-----------|
| `duration` | `Duration` |
| `dateTime` | `OffsetDateTime` (timezone-tolerant) |
| `time` | `OffsetTime` (timezone-tolerant) |
| `date` | `LocalDate` (optional timezone stripped via `ISO_DATE`) |
| `gYearMonth` | `YearMonth` |
| `gYear` | `Year` |
| `gMonthDay` | `MonthDay` |
| `gDay` | `Integer` (with `---DD` adapter) |
| `gMonth` | `Month` |

### Adapters

- Auto-generated adapters extend `XmlAdapter<String, T>` and live under `-adapter-package` (or derived common package).
- Custom `-format` embeds a pattern-based formatter; class name includes a sanitized format fragment and hash.
- `OffsetDateTime` / `OffsetTime` fall back to system default offset when the XML value has no timezone.
- Supported auto targets include: `Duration`, `Instant`, `Period`, `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `LocalTime`, `OffsetTime`, `YearMonth`, `Year`, `MonthDay`, `Month`, `DayOfWeek`, plus special `gDay` → `Integer`.

### Field rewrite

- Updates field type (including arrays and collection element types).
- Replaces existing `@XmlJavaTypeAdapter` on the field.
- Updates matching getter return type and single-arg setter parameter type.

Schema type resolution covers `CElementPropertyInfo` (single type ref), `CAttributePropertyInfo`, and `CValuePropertyInfo`.

## Usage

```text
-Xjava-time
-Xjava-time -adapter-package=com.example.adapters
-Xjava-time -type-mapping -xsd-type=dateTime -target-type=java.time.LocalDateTime
-Xjava-time -type-mapping -field=.*\.createdAt -target-type=java.time.Instant -format=yyyy-MM-dd'T'HH:mm:ssX
```

## Limitations / notes

- Only fields whose schema type maps (default or via config) are rewritten; other types are left unchanged.
- Custom `-target-type` values outside the built-in adapter generator map require `-adapter` or will fail for auto generation.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/JavaTimePlugin.java`
