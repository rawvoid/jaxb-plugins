# AnnotatePlugin (`-Xannotate`)

## Overview

Adds custom annotations to generated Java artifacts, or removes existing annotations, on classes, fields, methods, and packages. Annotation text uses the annox `XAnnotation` syntax.

## Lifecycle

| Hook | Role |
|------|------|
| `run` | Match targets by FQCN regex and apply add/remove configs |

## Options

| Option | Type | Description |
|--------|------|-------------|
| `-Xannotate` | flag | Enable the plugin |
| `-add-to-class` | add config (repeatable) | Add annotations to classes |
| `-add-to-field` | add config (repeatable) | Add annotations to fields |
| `-add-to-method` | add config (repeatable) | Add annotations to methods |
| `-add-to-package` | add config (repeatable) | Add annotations to packages |
| `-remove-from-class` | remove config (repeatable) | Remove annotations from classes |
| `-remove-from-field` | remove config (repeatable) | Remove annotations from fields |
| `-remove-from-method` | remove config (repeatable) | Remove annotations from methods |
| `-remove-from-package` | remove config (repeatable) | Remove annotations from packages |

### Add config (`AddConfig`)

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-anno` | yes (repeatable) | Annotation to add (annox form, e.g. `@java.lang.Deprecated`) |
| `-target` | no (repeatable) | Regex against the fully-qualified target name |

Target name shapes:

- Class: `com.example.Person`
- Field: `com.example.Person.fieldName`
- Method: `com.example.Person.methodName`
- Package: `com.example`

If `-target` is omitted or empty, all targets of that kind match.

### Remove config (`RemoveConfig`)

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-anno` | yes (repeatable) | Annotation **class** to remove |
| `-target` | no (repeatable) | Regex against the fully-qualified target name |

## Behavior

- Add configs apply every listed `XAnnotation` on matched targets via `AnnotationUtils.applyXAnnotation`.
- Remove configs remove all instances of the listed annotation classes on matched targets.
- Package operations use outline package contexts (including `package-info`).

## Usage

```text
-Xannotate \
  -add-to-class \
  -anno=@java.lang.Deprecated \
  -target=com\.example\..*

-Xannotate \
  -add-to-field \
  -anno=@jakarta.xml.bind.annotation.XmlTransient \
  -target=.*\.internalId

-Xannotate \
  -remove-from-class \
  -anno=jakarta.xml.bind.annotation.XmlType \
  -target=com\.example\.Person
```

## Limitations / notes

- Annotation types used in `-anno` must be loadable by XJC (same constraint as other annox-based plugins).
- Field/method matching uses simple names in the FQCN path after the class name (not Java method descriptors).

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/AnnotatePlugin.java`
