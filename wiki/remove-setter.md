# RemoveSetterPlugin (`-Xremove-setter`)

## Overview

Removes XJC-generated **setter** methods for bean properties. Identification uses the property model (`prop.getName(true)`), matching XJC naming, rather than stripping every method whose name starts with `set`.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Walks all class outlines and removes property setters |

## Options

None beyond enabling the plugin:

| Option | Description |
|--------|-------------|
| `-Xremove-setter` | Enable the plugin |

## Behavior

- For each generated bean, removes setters that correspond to model properties.
- Does not remove arbitrary methods named `set*` that are not property accessors.

## Usage

```text
-Xremove-setter
```

Often used with `-Xremove-getter` or `-Xlombok` for immutable-style or Lombok-managed accessors.

## Limitations / notes

- Affects all generated classes; there is no class-name filter on this plugin.
- Stock XJC already omits setters for some live-list properties; this plugin only removes setters that were generated for model properties.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/RemoveSetterPlugin.java`
