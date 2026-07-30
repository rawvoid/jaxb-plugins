# ConvertNamePlugin (`-Xconvert-name`)

## Overview

Customizes naming conversion during JAXB code generation by replacing XJC’s default `NameConverter`. Control names of generated classes, variables, interfaces, properties, constants, and packages via exact input matches or regular expressions on the converted name.

Unlike `-Xrename-class` (post-model short-name rewrite), this plugin acts **during schema binding** through the name converter.

## Lifecycle

| Hook | Role |
|------|------|
| `postParseArgument` | Validate mappings; install custom `NameConverter` on `Options` |
| `run` | No-op (`true`) |

## Options

| Option | Type | Description |
|--------|------|-------------|
| `-Xconvert-name` | flag | Enable the plugin |
| `-name-converter` | class | Fully-qualified custom `NameConverter` implementation; if set, **other mapping configs are ignored** |
| `-class-name` | mapping (repeatable) | Class name conversion rules |
| `-variable-name` | mapping (repeatable) | Variable name conversion rules |
| `-interface-name` | mapping (repeatable) | Interface name conversion rules |
| `-property-name` | mapping (repeatable) | Property name (including getter/setter base) conversion rules |
| `-constant-name` | mapping (repeatable) | Constant name conversion rules |
| `-package-name` | mapping (repeatable) | Package name conversion rules (input is often a namespace URI) |

### Name mapping (`NameMappingConfig`)

Each rule must set **exactly one** of `-input` or `-name` (not both, not neither). Validated at parse time.

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-input` | exclusive | Exact match on the original NameConverter input (XML local name, enum value, or namespace URI); result is `-to` as a whole |
| `-name` | exclusive | Regex match on the name **after** standard conversion; replaced via `String.replaceAll` with `-to` |
| `-to` | yes | Target name (`$n` groups when `-name` is used) |

Compact formats (more specific first): `/{name}/->{to}`, `{input}->{to}`.

## Behavior

- Default pipeline: call `NameConverter.Standard` then apply the first matching mapping for that name category.
- With `-name-converter`, the given class is instantiated via no-arg constructor and set as the converter for the whole XJC run.

## Usage

```text
-Xconvert-name -class-name=Person->CustomPerson
-Xconvert-name -class-name=/(.*)_ID/->$1Id
-Xconvert-name -package-name=http://example.com/ns->com.example.ns
-Xconvert-name -name-converter=com.example.MyNameConverter
```

## Limitations / notes

- Mapping validation errors throw `IllegalArgumentException` with a clear message about `-input` vs `-name`.
- Custom converter must provide a public no-arg constructor.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/ConvertNamePlugin.java`
