# InheritancePlugin (`-Xinheritance`)

## Overview

Injects interface implementations (`implements`) and superclasses (`extends`) into generated JAXB classes (Java supertypes / inheritance edges).

Application order per class: `-serializable` (optional UID), then `-interface` rules in declaration order (cumulative), then `-super-class` rules in declaration order (first-wins). Target classes are selected by each rule’s left-hand pattern.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Mutate CodeModel implements/extends and optional `serialVersionUID` |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Xinheritance` | flag | — | Enable the plugin |
| `-interface` | mapping (repeatable) | — | Pattern → interface FQCN |
| `-super-class` | mapping (repeatable) | — | Pattern → superclass FQCN |
| `-serializable` | boolean | `false` | Add `implements java.io.Serializable` and `serialVersionUID` when missing |
| `-serial-version-uid` | long | `1` | UID used when `-serializable` is true |

### Mapping config (`InheritanceConfig`)

Compact formats: `/{name}/->{to}` or `{name}->{to}`.

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-name` | yes | Regex matching fully-qualified class names |
| `-to` | yes | Target interface or superclass FQCN |

## Behavior

- Bean classes only (`outline.getClasses()`); enums are not modified.
- Does not replace an existing non-`Object` superclass (XSD inheritance or a prior matching `-super-class` rule). Multiple matching super-class rules are first-wins; skips are reported as XJC warnings.
- `-serializable` adds `Serializable` and `serialVersionUID` (default `1L`) when the field is absent.
- Interface injection is cumulative and skips duplicates already present on the class.
- Does not validate that targets are interfaces/classes or resolvable at generation time; invalid FQCNs fail later at Java compile.

## Usage

```text
-Xinheritance -serializable=true
-Xinheritance -serializable=true -serial-version-uid=42
-Xinheritance -interface=.*Request->com.example.BaseRequest
-Xinheritance -super-class=.*Dto->com.example.AbstractDto
```

## Limitations / notes

- Target types must be on the consumer compile classpath.
- Superclass replacement is never forced over an existing non-Object parent.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/InheritancePlugin.java`
