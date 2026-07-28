# JAXB Plugins Collection

[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![JAXB](https://img.shields.io/badge/JAXB-4.0+-blue.svg)](https://eclipse-ee4j.github.io/jaxb-ri/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://opensource.org/licenses/Apache-2.0)

A suite of extensible XJC (JAXB) plugins designed to streamline XML-to-Java binding, customize generated code structures, and eliminate boilerplate in modern Java projects (Java 21+).

---

## Key Capabilities

- **Modern Standards**: Full support for Java 21+ and `java.time` (JSR-310) date/time API mapping.
- **Flexible Configuration**: Supports compact inline mappings (`pattern->replacement`) and structured nested CLI flags.
- **Boilerplate Reduction**: Direct Lombok integration (`-Xlombok`), Jackson DTO defaults (`-Xjackson`), automated wrapper class flattening (`-Xelement-wrapper`), and getter/setter removal.
- **Fine-Grained Customization**: Comprehensive annotation injection/removal, custom naming transformations, and namespace/prefix management.

---

## Available Plugins

### Java Time Plugin (`-Xjava-time`)

Maps XSD date and time types to modern `java.time` classes.

#### Key Features

- Timezone-aware date/time handling (`OffsetDateTime`, `OffsetTime`).
- Timezone-tolerant unmarshalling (automatically falls back to system local offset if timezone is omitted in XML).
- Custom formatting patterns.
- Automated `XmlAdapter` generation (derived from common package prefixes).

#### Quick Start

```bash
-Xjava-time \
  -type-mapping \
  -xsd-type=dateTime \
  -target-type=java.time.OffsetDateTime
```

#### Command Options

```bash
-Xjava-time \
  -adapter-package=package.name \      # Optional: defaults to <common_package>.adapter
  -type-mapping \                      # Group marker
  -xsd-type=xsdType \
  -target-type=java.time.Class \
  -format=dateFormat \
  -adapter=custom.AdapterClass \
  -field=fieldPattern \
  -xsd-type=anotherType \              # Repeated child field starts next item
  -target-type=java.time.LocalDate
```

#### Default Mappings

| XSD Type        | Java Target Type           | Notes             |
|:----------------|:---------------------------|:------------------|
| `xs:dateTime`   | `java.time.OffsetDateTime` | Timezone-tolerant |
| `xs:date`       | `java.time.LocalDate`      | `ISO_DATE`        |
| `xs:time`       | `java.time.OffsetTime`     | Timezone-tolerant |
| `xs:gYearMonth` | `java.time.YearMonth`      |                   |
| `xs:gYear`      | `java.time.Year`           |                   |
| `xs:gMonthDay`  | `java.time.MonthDay`       |                   |
| `xs:gDay`       | `java.lang.Integer`        |                   |
| `xs:gMonth`     | `java.time.Month`          |                   |
| `xs:duration`   | `java.time.Duration`       |                   |

---

### Validation Plugin (`-Xvalidation`)

Generates Bean Validation (JSR-380) constraint annotations on generated class fields from XSD multiplicity and simple-type facets.

#### API auto-detection

The plugin picks the validation package from the **XJC classpath** (not the main module compile classpath):

1. Prefer `jakarta.validation` when present
2. Else use `javax.validation` when present
3. If neither is found, XJC fails with a clear error

Add `jakarta.validation:jakarta.validation-api` (preferred) or `javax.validation:validation-api` to the `jaxb-maven-plugin` / XJC plugin dependencies.

#### Mapping

| XSD | Annotation |
|:----|:-----------|
| Element `minOccurs≥1` (non-nillable, non-primitive) | `@NotNull` |
| Element `nillable="true"` | no `@NotNull` |
| Attribute `use="required"` | `@NotNull` |
| Collection `minOccurs` / `maxOccurs` | `@NotNull` + `@Size` |
| `minLength` / `maxLength` / `length` (string fields) | `@Size` |
| `pattern` | `@Pattern` |
| `minInclusive` / `maxInclusive` | `@Min` / `@Max` or `@DecimalMin` / `@DecimalMax` |
| `minExclusive` / `maxExclusive` | `@DecimalMin` / `@DecimalMax(inclusive=false)` |
| `totalDigits` / `fractionDigits` (user-declared) | `@Digits` |
| Complex type / `List` of complex | `@Valid` |
| simpleContent (`@XmlValue`) facets | same as simple type |

#### Intentional limitations

- No mapping for `enumeration`, `whiteSpace`, or `fixed`.
- Collection **item** length/pattern is not expressed (`@Size` on a list is multiplicity only).
- Facets are taken from the **user restriction chain** only; built-in XML Schema facets (e.g. `xs:integer` `fractionDigits`) are ignored.
- Annotations are applied to **fields** only (JAXB default `FIELD` access).

#### Quick Start

```bash
-Xvalidation
```

#### Command Options

```bash
-Xvalidation \
  -class-name=.*UserType \             # Optional regex matched against FQCN (repeatable)
  -field-name=username \               # Optional regex matched against field name (repeatable)
  -disable-valid=true                  # Optional: disable @Valid on nested/collection properties (default: false)
```

| Option           | Default         | Description                                                    |
|:-----------------|:----------------|:---------------------------------------------------------------|
| `-class-name`    | *(all classes)* | Regex matched against fully-qualified class names (repeatable) |
| `-field-name`    | *(all fields)*  | Regex matched against field names (repeatable)                 |
| `-disable-valid` | `false`         | When `true`, omits `@Valid` cascade validation annotations     |

---

### Inheritance Plugin (`-Xinheritance`)

Injects Java supertypes into generated JAXB classes: interface implementations (`implements`) and superclasses (`extends`), with optional `Serializable` support. Similar in spirit to the jaxb-tools inheritance plugin.

#### Key Features

- **Interface Implementation**: Adds `implements InterfaceFQCN` via compact or structured mapping. Accumulates multiple interfaces without duplicates. Select targets with each rule's left-hand pattern.
- **Superclass Extension**: Adds `extends SuperClassFQCN` only when the class currently extends `Object` (preserves XSD inheritance). Multiple matching rules are **first-wins**; further matches or existing non-`Object` parents emit XJC **warnings**.
- **Serializable Shortcut**: Adds `implements java.io.Serializable` and `serialVersionUID` when missing. Default UID is `1L` (reproducible); override with `-serial-version-uid`.

#### Application order (per class)

1. `-serializable` (+ optional `-serial-version-uid`)
2. `-interface` rules in declaration order (cumulative)
3. `-super-class` rules in declaration order (first-wins)

#### Quick Start

```bash
-Xinheritance \
  -serializable=true \
  -serial-version-uid=1 \
  -interface=.*Request->com.example.BaseRequest \
  -super-class=.*Dto->com.example.AbstractDto
```

#### Command Options

```bash
-Xinheritance \
  -interface=pattern->InterfaceFQCN \    # Compact interface mapping (repeatable)
  -super-class=pattern->SuperClassFQCN \ # Compact superclass mapping (repeatable)
  -serializable=true \                  # Add Serializable + serialVersionUID when missing
  -serial-version-uid=1                 # UID value when -serializable is true (default: 1)
```

Structured form is also supported:

```bash
-Xinheritance -interface -name=.*Request -to=com.example.BaseRequest
```

| Option                | Default  | Description                                                            |
|:----------------------|:---------|:-----------------------------------------------------------------------|
| `-interface`          | *(none)* | Compact mapping (`pattern->InterfaceFQCN`) for implementing interfaces |
| `-super-class`        | *(none)* | Compact mapping (`pattern->SuperClassFQCN`) for extending superclasses |
| `-serializable`       | `false`  | When `true`, injects `Serializable` and `serialVersionUID` if missing  |
| `-serial-version-uid` | `1`      | `serialVersionUID` value used only when `-serializable=true`           |

---

### Annotate Plugin (`-Xannotate`)

Adds, removes, or modifies annotations on generated classes, fields, methods, and package metadata.

#### Key Features

- Inject arbitrary annotations (validation, framework metadata, field-level Jackson, etc.).
- For common class-level Jackson defaults (`@JsonInclude`, `ignoreUnknown`), prefer dedicated `-Xjackson`.
- Strip unwanted JAXB-generated annotations.

#### Quick Start

```bash
-Xannotate \
  -add-to-class \
  -anno=@com.example.MyAnnotation \
  -target=.*Person \
  -add-to-field \
  -anno=@com.fasterxml.jackson.annotation.JsonProperty("value") \
  -target=.*name
```

#### Command Options

```bash
-Xannotate \
  -add-to-class \                      # Target kind: class
  -anno=@AnnotationClass(param="value") \
  -target=pattern \
  -add-to-field \                      # Target kind: field
  -anno=@AnnotationClass(param="value") \
  -target=pattern \
  -remove-from-class|-remove-from-field|-remove-from-method|-remove-from-package \
  -anno=AnnotationClass \
  -target=pattern
```

---

### Convert Name Plugin (`-Xconvert-name`)

Customizes how XJC maps XML element names to Java identifiers (class names, field names, method names, and package names).

#### Key Features

- Convert `snake_case` XML definitions into `camelCase` Java properties.
- Apply regular expression replacements to auto-generated class/variable names.
- Resolve conflicts with legacy naming conventions.

#### Quick Start

```bash
-Xconvert-name \
  -class-name=XMLDocument->Document \
  -variable-name \
  -name=(.*)_ID \
  -to=$1Id
```

#### Command Options

```bash
-Xconvert-name \
  # Compact format:
  -class-name=originalName->newName \
  -class-name=/(.*)_ID/->$1Id \
  -package-name=http://example.com/a->com.example.a \
  # Structured format:
  -variable-name \
  -name=(.*)_ID \
  -to=$1Id \
  # Or exact match on original NameConverter input:
  -class-name \
  -input=Person \
  -to=CustomPerson
```

---

### Element Wrapper Plugin (`-Xelement-wrapper`)

Flattens XML collection wrapper elements by moving `@XmlElementWrapper` and `@XmlElement` annotations directly to the collection field and optionally deleting unnecessary wrapper classes.

#### Key Features

- Removes redundant wrapper DTO classes.
- Simplifies object graphs and cleans up API signatures.

#### Plugin order

Run **`-Xelement-wrapper` after every other model-mutating plugin** (`-Xdedupe-class`, `-Xpromote-nested-class`, `-Xrename-class`, `-Xflatten-multi-element-prop`, …). Flatten records keep the owner class identity; if a later model plugin deletes that owner, generation fails with a clear error instead of emitting incomplete `@XmlElementWrapper` annotations.

#### Quick Start

```bash
-Xelement-wrapper \
  -remove-wrapper-class=true
```

---

### Promote Nested Class Plugin (`-Xpromote-nested-class`)

Promotes nested static classes and enums toward package scope level by level, stopping automatically upon name collisions.

#### Key Features

- Un-nests deeply scoped anonymous complex types and local enums.
- Avoids unsafe renames by respecting namespace boundaries.
- Executes during the `postProcessModel` phase.

#### Quick Start

```bash
-Xpromote-nested-class
```

---

### Namespace Plugin (`-Xnamespace`)

Customizes Java package names and XML namespace prefixes for XML namespaces.
Replaces the former `-Xns-prefix` plugin.

#### Key Features

- Explicit Java package mapping per XML target namespace URI.
- Optional XML prefix on `@XmlSchema` for that namespace.
- Package-scoped multi-`xmlns` prefix mappings with optional Java package regex filter.
- Package bindings use SCD (`x-schema::…`) so each mapping attaches once per target namespace (safe when many XSD files share one namespace).

#### Quick Start

```bash
-Xnamespace \
  -package-mapping \
  -ns=http://example.com/schema \
  -package=com.example.schema \
  -prefix=ex
```

#### Command Options

```bash
-Xnamespace \
  # Compact package mapping:
  -package-mapping=namespaceURI->java.package.name \
  -package-mapping=namespaceURI->java.package.name:xmlPrefix \
  # Structured package mapping (+ optional xmlns set for that package):
  -package-mapping \
  -ns=namespaceURI \
  -package=java.package.name \
  -prefix=xmlPrefix \
  -xmlns=http://other.example.com->ot \
  -xmlns \
  -ns=http://third.example.com \
  -prefix=th \
  # Prefix-only / multi-xmlns without package mapping (optional package filter):
  -ns-prefix \
  -package=com\.example\.* \
  -xmlns=namespaceURI->xmlPrefix \
  -xmlns \
  -ns=http://other.example.com \
  -prefix=ot
```

---

### Lombok Plugin (`-Xlombok`)

Generates Lombok-annotated beans and strips default XJC getters/setters. Replaces hand-crafted `-Xannotate` + `-Xremove-getter` + `-Xremove-setter` pipelines.

#### Key Features

- Replaces getter/setter boilerplate with `@Data`.
- Supports optional `@Builder` pattern generation.
- Automatically handles `@EqualsAndHashCode(callSuper = true)` for non-`Object` subclasses.
- Optional `-keep-list-getter` retains XJC lazy-init live-list getters for collection properties.

#### Quick Start

```bash
-Xlombok
```

#### Options

```bash
-Xlombok \
  -anno=@lombok.Data \                 # Repeatable; defaults to @Data
  -class-name=.*Person \               # Optional class filter
  -remove-getter=true \                # Default: true
  -keep-list-getter=false \            # Default: false; keep XJC List/collection getters when removing getters
  -remove-setter=true \                # Default: true
  -builder                             # Default: false; adds @Builder + @NoArgsConstructor + @AllArgsConstructor
```

By default, stripping getters also removes XJC collection getters that lazy-initialize a live `List`
(`if (field == null) field = new ArrayList<>()`). Lombok then returns the field as-is (may be `null`).
Pass `-keep-list-getter` to keep those list getters while still removing scalar getters.

> **Note**: Lombok dependencies must be present on both the XJC classpath (for annotation resolution) and compile classpath.

---

### Jackson Plugin (`-Xjackson`)

Adds common Jackson annotations on generated classes. Zero-config replaces the usual
`-Xannotate -add-to-class -anno=@JsonInclude(...)` recipe for class-level defaults.

#### Key Features

- Default `@JsonInclude(JsonInclude.Include.NON_NULL)` on generated classes.
- Default `@JsonIgnoreProperties(ignoreUnknown = true)` for deserialization-friendly DTOs.
- Optional class-name filter and `-anno` escape hatch for extra class-level annotations.
- Built-in annotations are **skipped** when already present (does not overwrite `-Xannotate` or hand-written values).

#### Quick Start

```bash
-Xjackson
```

#### Options

```bash
-Xjackson \
  -include=NON_NULL \                  # JsonInclude.Include; default NON_NULL; use none to skip
  -ignore-unknown=true \               # Default: true; false skips @JsonIgnoreProperties
  -class-name=com\.example\.api\..* \  # Optional FQCN regex filter (repeatable)
  -anno=@com.fasterxml.jackson.annotation.JsonPropertyOrder({"id","name"})
```

| Option            | Default         | Description                                                    |
|:------------------|:----------------|:---------------------------------------------------------------|
| `-include`        | `NON_NULL`      | `JsonInclude.Include` name, or `none` to omit `@JsonInclude`   |
| `-ignore-unknown` | `true`          | When `true`, add `@JsonIgnoreProperties(ignoreUnknown = true)` |
| `-class-name`     | *(all classes)* | Regex matched against fully-qualified class names (repeatable) |
| `-anno`           | *(none)*        | Extra class-level annotation via annox syntax (repeatable)     |

#### Intentional limitations (MVP)

- Class-level only: no field `@JsonProperty` derived from `@XmlElement` / `@XmlAttribute` (planned later).
- No `@JsonFormat`, `@JsonPropertyOrder` (except via `-anno`), `@JsonRootName`, or polymorphic type info.
- Does not configure `ObjectMapper`.

> **Note**: `jackson-annotations` must be present on both the XJC classpath (for annotation resolution)
> and the compile classpath (same pattern as Lombok).

---

### Remove Getter Plugin (`-Xremove-getter`)

Removes property getter methods generated by XJC (matched against the property model rather than raw method names).

#### Quick Start

```bash
-Xremove-getter
```

---

### Remove Setter Plugin (`-Xremove-setter`)

Removes property setter methods generated by XJC to create read-only DTOs and enforce immutability.

#### Quick Start

```bash
-Xremove-setter
```

---

### Flatten Multi-Element Property Plugin (`-Xflatten-multi-element-prop`)

Flattens multi-element properties (such as `@XmlElements` choice groups or `@XmlElementRefs`) into individual single-element fields.

#### Key Features

- Splits heterogenous multi-element collection properties into distinct fields per element type.
- Preserves relative field order in generated classes.

#### Quick Start

```bash
-Xflatten-multi-element-prop
```

---

### Generated Annotation Plugin (`-Xgenerated-anno`)

Adds `@jakarta.annotation.Generated` annotations to generated classes and package-info files.

#### Key Features

- Automatically decorates generated classes with standard `@Generated` markers.
- Supports optional generation date timestamp and custom comments.

#### Quick Start

```bash
-Xgenerated-anno \
  -value="JAXB Generator" \
  -comments="Auto-generated file" \
  -date=true
```

---

### Rename Class Plugin (`-Xrename-class`)

Renames generated classes, enums, and element classes post-model building with simulated conflict detection.

#### Key Features

- Rewrites short class names using regex replacement patterns (`-mapping`).
- Mappings run as an ordered pipeline: each rule sees the intermediate name from previous rules (single forward pass).
- **`-strip-type-suffix`** (default off): industry-convention strip of a trailing `Type` on **named** schema types only (`complexType` / `simpleType` whose type name ends with `Type`, including `Foo_Type`). Anonymous types and element classes are left alone, so an element named `ActionType` is not mis-renamed to `Action`.
- Explicit `-mapping` still applies to every class (use it to force renames, including anonymous / element-derived names).
- Safe execution: detects simple-name, parent-child, and ObjectFactory squeezed-name collisions and rolls back conflicting renames with build warnings.

#### Quick Start

Prefer the dedicated flag for bulk type-suffix cleanup (NDC / EDIST style):

```bash
-Xrename-class \
  -strip-type-suffix
```

Avoid a bare `-mapping=^(.+)Type$->$1` when the schema also has **elements** whose local name ends with `Type` — that regex cannot tell type-suffix convention from element business names.

Custom renames and forced names still use mappings (run before the optional strip):

```bash
-Xrename-class \
  -mapping=^IATA(.+)$->$1 \
  -mapping=Person->CustomPerson \
  -strip-type-suffix
```

Example: named `IATAFooType` → after optional prefix mapping / strip → `Foo`; element-derived `ActionType` stays `ActionType` unless you map it explicitly.

When used with `-Xpromote-nested-class`, running **rename before promote** lets global named types claim short names first; promote-first can leave dual names such as `Fare` + `FareType`.

---

### Dedupe Class Plugin (`-Xdedupe-class`)

Merges structurally redundant generated beans to reduce class count (NDC-style anonymous type copies).

#### Key Features

- Groups candidates by **name key**: short name with a trailing `Type` removed (`AircraftCodeType` ≡ `AircraftCode`). Different name keys never merge.
- **Exact** merge: identical property structure (names, collection, attribute/element/value, recursive type shape).
- **`-merge-subset`**: when every property of `B` exists on `A` with a compatible type, merge `B` into `A` (default off; surplus host fields remain).
- **`-anonymous-only`** (default true): only delete anonymous beans; named global types may still be merge hosts.
- **`-dry-run`**: log planned merges without changing the model.
- **`-preserve-wrapper-shells`** (default false): do not merge pure collection-wrapper shells into non-shell hosts. Keeps later `-Xelement-wrapper` flatten opportunities when Dedupe runs first; shell→shell exact merges still allowed.

#### Quick Start

```bash
-Xdedupe-class \
  -merge-subset \
  -preserve-wrapper-shells
```

Suggested order with other model plugins:

```bash
-Xflatten-multi-element-prop \
-Xpromote-nested-class \
-Xdedupe-class -merge-subset -preserve-wrapper-shells \
-Xrename-class -strip-type-suffix \
-Xelement-wrapper
```

Put **`-Xelement-wrapper` last among model plugins** so flatten owners are not removed by dedupe/rename/promote after flatten records are captured. When Dedupe runs first, enable **`-preserve-wrapper-shells`** so subset merges cannot replace pure wrapper shells with fatter non-shell types.

Nested copies merge into **package-level** hosts (named global types, or types already promoted). Prefer **promote before dedupe** so more hosts sit at package scope. Cross-outer nested-to-nested merges are skipped (avoids ObjectFactory / FieldOutline corruption).

Subset merge can make extra fields visible on former subset sites when marshalling — review with `-dry-run` first on large schemas.

---

### Rename Multi-Element Property Plugin (`-Xrename-multi-element-prop`)

Renames multi-element properties produced by XJC to a short plural base name (e.g. `items`, `items2`).

#### Key Features

- Replaces verbose generated property names (like `rest` or `content`) with clean plural naming conventions.

#### Quick Start

```bash
-Xrename-multi-element-prop \
  -name=elements
```

---

## Getting Started

### Installation

Add the plugin dependency to your project:

```xml

<dependency>
  <groupId>io.github.rawvoid</groupId>
  <artifactId>jaxb-plugins</artifactId>
  <version>${latest-version}</version>
</dependency>
```

### Maven Plugin Configuration

Configure `jaxb-maven-plugin` with desired plugin arguments:

```xml

<plugin>
  <groupId>org.jvnet.jaxb</groupId>
  <artifactId>jaxb-maven-plugin</artifactId>
  <version>4.0.12</version>
  <configuration>
    <plugins>
      <plugin>
        <groupId>io.github.rawvoid</groupId>
        <artifactId>jaxb-plugins</artifactId>
        <version>${latest-version}</version>
      </plugin>
    </plugins>
    <args>
      <arg>-Xjava-time</arg>
      <arg>-Xannotate</arg>
      <arg>-Xconvert-name</arg>
      <arg>-Xnamespace</arg>
    </args>
  </configuration>
</plugin>
```

### CLI Usage

```bash
xjc -d src -p com.example \
    -extension \
    -Xjava-time \
    -Xannotate \
    -Xconvert-name \
    -Xnamespace \
    schema.xsd
```

---

## Advanced Recipes

### Full Stack Pipeline Example

```bash
xjc schema.xsd \
  -d src/main/java \
  -p com.example.api \
  -extension \
  -Xjava-time \
    -adapter-package=com.example.adapters \
    -type-mapping \
    -xsd-type=dateTime \
    -target-type=java.time.LocalDateTime \
  -Xjackson \
  -Xannotate \
    -add-to-field \
    -anno=@com.fasterxml.jackson.annotation.JsonProperty("value") \
    -target=.*\.value \
  -Xconvert-name \
    -class-name \
    -name=(.*)Type \
    -to=$1DTO \
    -variable-name \
    -name=(.*)_ID \
    -to=$1Id \
  -Xnamespace \
    -package-mapping \
    -ns=http://api.example.com \
    -package=com.example.api
```

### Lombok Integration Recipe

```bash
xjc schema.xsd \
  -d src/main/java \
  -p com.example.domain \
  -extension \
  -Xlombok \
    -builder \
    -anno=@lombok.Data \
    -anno=@lombok.experimental.Accessors(chain = true)
```

### Jackson Integration Recipe

```bash
xjc schema.xsd \
  -d src/main/java \
  -p com.example.api \
  -extension \
  -Xjackson \
    -include=NON_NULL \
    -ignore-unknown=true \
    -class-name=com\.example\.api\..* \
  -Xlombok \
    -builder
```

Disable built-ins and only apply an extra annotation:

```bash
-Xjackson \
  -include=none \
  -ignore-unknown=false \
  -anno=@com.fasterxml.jackson.annotation.JsonPropertyOrder({"id","name"})
```

---

## Building from Source

### Prerequisites

- JDK 21+
- Apache Maven 3.6+

```bash
git clone https://github.com/rawvoid/jaxb-plugins.git
cd jaxb-plugins
mvn clean install
```

---

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository.
2. Create a feature branch (`git checkout -b feat/new-plugin-feature`).
3. Commit your changes using [Conventional Commits](https://www.conventionalcommits.org/) format (`git commit -m 'feat(plugin): add feature X'`).
4. Push to your branch (`git push origin feat/new-plugin-feature`).
5. Submit a Pull Request.

---

## License

Distributed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
