# PromoteNestedClassPlugin (`-Xpromote-nested-class`)

## Overview

Lifts nested beans and enums toward package scope one parent level at a time. Stock XJC nests anonymous complex types and local enums as member types. This plugin rewrites nesting on the model before BeanGenerator runs (`CClassInfo.parent()` for beans, `CEnumLeafInfo.parent` for enums).

## Lifecycle

| Hook | Role |
|------|------|
| `postProcessModel` | Fixed-point: promote every nested type at most one level per pass until none move |
| `run` | No-op |

## Options

| Option | Description |
|--------|-------------|
| `-Xpromote-nested-class` | Enable the plugin |

No sub-options.

## Behavior

### Algorithm

Each pass every nested type (parent is a `CClassInfo`) proposes a one-level move to its grandparent. A proposal is applied only when:

- the simple name is free under the target
- no other type claims the same slot in that pass (symmetric stop — no arbitrary winner)
- for beans: the move does not introduce a duplicate `getSqueezedName()` in the owner package (ObjectFactory value-factory methods use that name)

Passes repeat until none move. Name checks are **case-insensitive**.

### Outer self-name

BeanGenerator rejects a nested type whose simple name equals its enclosing class. Each bean therefore reserves its own short name under itself, so a deep nested `TaxCouponInfo` is not lifted into a parent that was renamed from `TaxCouponInfoType` to `TaxCouponInfo` (rename-before-promote). Immediate siblings already occupy names under the parent; this covers the outer class itself.

### Shared namespace

Beans and enums share one namespace under each parent: a bean named `Status` and an enum named `Status` block each other. Types whose parent is already a package (or a `CElementInfo`) are left alone.

### Package identity

XJC sometimes builds `CClassInfoParent.Package` via `new Package(jPackage)` instead of `Model#getPackage`. Occupancy and promotion always canonicalize package parents through `model.getPackage` so those wrappers compare as the same parent.

### Side effect

`getSqueezedName()` follows the parent chain, so ObjectFactory method names become shorter after a successful lift (e.g. `createFlattenRootGroup` → `createGroup`). That is also why a lift can collide with another type whose squeezed name already equals the shortened form — those lifts are undone.

## Usage

```text
-Xpromote-nested-class
```

Typical combination:

```text
-Xrename-class -strip-type-suffix -Xpromote-nested-class
```

(rename before promote so named types claim short names first)

## Limitations / notes

- No force-promote or rename-on-conflict: types that cannot move stop at the deepest safe level.
- Interaction with `-Xrename-class` is documented on both pages.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/PromoteNestedClassPlugin.java`
