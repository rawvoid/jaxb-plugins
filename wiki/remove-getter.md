# RemoveGetterPlugin (`-Xremove-getter`)

## Overview

Removes XJC-generated **getter** methods for bean properties. Identification uses the property model (`prop.getName(true)`), matching XJC naming, rather than stripping every method whose name starts with `get` or `is`.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Walks all class outlines and removes property getters |

## Options

None beyond enabling the plugin:

| Option | Description |
|--------|-------------|
| `-Xremove-getter` | Enable the plugin |

## Behavior

- For each generated bean, removes getters that correspond to model properties.
- Does not remove arbitrary methods named `get*` / `is*` that are not property accessors.

## Usage

```text
-Xremove-getter
```

Often combined with `-Xlombok` (which can remove getters itself) or `-Xremove-setter` when you want field-only beans without Lombok.

## Limitations / notes

- Affects all generated classes; there is no class-name filter on this plugin.
- Collection getters that lazy-initialize a live `List` are removed as well (XJC style). For Lombok-specific retention of list getters, use `-Xlombok -keep-list-getter`.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/RemoveGetterPlugin.java`
