# NamespacePlugin (`-Xnamespace`)

## Overview

Customizes Java package names and XML namespace prefixes for XML namespaces.

- **Package mappings** are applied through injected SCD-based external bindings (one `schemaBindings` per target namespace, safe when a namespace spans many XSD files).
- **Ns-prefix rules** are applied on generated `@XmlSchema` annotations (including package-filtered multi-namespace rules).

## Lifecycle

| Hook | Role |
|------|------|
| `postParseArgument` | Inject external binding XML; enable extension mode when package mappings exist (SCD requires it) |
| `run` | Apply `xmlns` prefix entries on matching `@XmlSchema` annotations |

## Options

| Option | Type | Description |
|--------|------|-------------|
| `-Xnamespace` | flag | Enable the plugin |
| `-package-mapping` | package mapping (repeatable) | Map XML target namespace → Java package (optional prefixes) |
| `-ns-prefix` | ns-prefix config (repeatable) | Package-scoped namespace prefix mapping |

### Package mapping (`PackageMappingConfig`)

Compact: `{ns}->{package}` or `{ns}->{package}:{prefix}`.

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-ns` | yes | XML target namespace URI |
| `-package` | yes | Target Java package name |
| `-prefix` | no | Prefix on `@XmlSchema` for this package’s own namespace (`null` = unset; empty string allowed via compact `ns->pkg:`) |
| `-xmlns` | no (repeatable) | Extra `XmlNs` entries on the mapped package |

### Ns-prefix config (`NsPrefixConfig`)

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-package` | no | Java package name **regex**; omit to match all packages |
| `-xmlns` | yes for effect | Namespace → prefix mappings |

### XmlNs config (`XmlNsConfig`)

Compact: `{ns}->{prefix}`.

| Nested option | Required | Description |
|---------------|----------|-------------|
| `-ns` | yes | XML namespace URI |
| `-prefix` | yes | XML namespace prefix |

## Behavior

### Package mapping bindings

- Generates a JAXB 3.0 external bindings document using SCD selectors `x-schema::prefix` so each mapping attaches `schemaBindings` once per target namespace.
- Empty target namespace uses `scd="x-schema::"` with default `xmlns=""`.
- Enables `Options.EXTENSION` when bindings are injected (`SCD_NOT_ENABLED` otherwise).

### Prefix application

- For package mappings: updates `@XmlSchema` only when the package’s `namespace` member equals the mapped namespace; upserts `xmlns` entries for `-prefix` and nested `-xmlns`.
- For ns-prefix rules: filters packages by optional regex, then upserts listed `xmlns` entries on that package’s `@XmlSchema`.
- Existing entries for the same `namespaceURI` are updated in place (prefix overwrite).

## Usage

```text
// Map namespace to package (optional prefix for that package's own namespace)
-Xnamespace -package-mapping=http://example.com->com.example:ex
-Xnamespace -package-mapping -ns=http://example.com -package=com.example -prefix=ex

// Map package and declare multiple xmlns entries for that package
-Xnamespace -package-mapping -ns=http://a.com -package=com.a -prefix=a \
  -xmlns=http://b.com->b -xmlns -ns=http://c.com -prefix=c

// Prefix-only rules on all packages (or a package regex filter)
-Xnamespace -ns-prefix -xmlns -ns=http://example.com -prefix=ex
-Xnamespace -ns-prefix -package=com\.example\.* -xmlns=http://example.com->ex

// Multiple xmlns on one package (one -xmlns; repeated -ns starts the next item)
-Xnamespace -ns-prefix -xmlns -ns=http://a.com -prefix=a -ns=http://b.com -prefix=b
```

## Limitations / notes

- Prefix rules no-op when the package has no `@XmlSchema` annotation.
- Package mapping relies on SCD / extension mode as described above.

## Source

`plugins/src/main/java/io/github/rawvoid/jaxb/plugin/NamespacePlugin.java`
