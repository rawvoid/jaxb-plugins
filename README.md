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

### JSR-310 Plugin (`-Xjsr310`)

Maps XSD date and time types to modern `java.time` (JSR-310) classes.

#### Key Features

- Timezone-aware date/time handling (`OffsetDateTime`, `OffsetTime`).
- Timezone-tolerant unmarshalling (automatically falls back to system local offset if timezone is omitted in XML).
- Custom formatting patterns.
- Automated `XmlAdapter` generation (derived from common package prefixes).

#### Quick Start

```bash
-Xjsr310 \
  -type-mapping \
  -xsd-type=dateTime \
  -target-type=java.time.OffsetDateTime
```

#### Command Options

```bash
-Xjsr310 \
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

Generates Bean Validation (JSR-380) constraint annotations (`jakarta.validation.constraints.*` or legacy `javax.validation.constraints.*`) on generated class fields based on XSD schema constraints (`minOccurs`, `maxOccurs`, `nillable`, `minLength`, `maxLength`, `pattern`, numeric bounds, and digits), as well as `@Valid` for cascade validation.

#### Key Features

- **Standard Mapping**: Automatically converts XSD facets and multiplicity into `@NotNull`, `@Size`, `@Pattern`, `@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`, `@Digits`, and `@Valid`.
- **Modern Defaults**: Uses `jakarta.validation` by default (Java 21+ / Jakarta EE 10 / Spring Boot 3+).
- **Legacy Compatibility**: Supports switching to `javax.validation` via `-api=javax`.
- **Filtering & Controls**: Class/field regular expression filtering and a `-disable-valid=true` escape hatch.

#### Quick Start

```bash
-Xvalidation
```

#### Command Options

```bash
-Xvalidation \
  -api=jakarta \                       # 'jakarta' (default) or 'javax'
  -class-name=.*UserType \             # Optional regex matched against FQCN (repeatable)
  -field-name=username \               # Optional regex matched against field name (repeatable)
  -disable-valid=true                  # Optional: disable @Valid on nested/collection properties (default: false)
```

| Option | Default | Description |
|:-------|:--------|:------------|
| `-api` | `jakarta` | Validation API package mode (`jakarta` or `javax`) |
| `-class-name` | *(all classes)* | Regex matched against fully-qualified class names (repeatable) |
| `-field-name` | *(all fields)* | Regex matched against field names (repeatable) |
| `-disable-valid` | `false` | When `true`, omits `@Valid` cascade validation annotations |

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
- Package bindings use `schemaLocation="*"` and match by `targetNamespace` (CLI schema order independent).

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
  -remove-setter=true \                # Default: true
  -builder                             # Default: false; adds @Builder + @NoArgsConstructor + @AllArgsConstructor
```

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

| Option | Default | Description |
|:-------|:--------|:------------|
| `-include` | `NON_NULL` | `JsonInclude.Include` name, or `none` to omit `@JsonInclude` |
| `-ignore-unknown` | `true` | When `true`, add `@JsonIgnoreProperties(ignoreUnknown = true)` |
| `-class-name` | *(all classes)* | Regex matched against fully-qualified class names (repeatable) |
| `-anno` | *(none)* | Extra class-level annotation via annox syntax (repeatable) |

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

- Rewrites short class names using regex replacement patterns.
- Safe execution: detects squeezed name collisions and rolls back conflicting renames with build warnings.

#### Quick Start

```bash
-Xrename-class \
  -mapping=/(.*)Type/->$1 \
  -mapping=Person->CustomPerson
```

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
      <arg>-Xjsr310</arg>
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
    -Xjsr310 \
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
  -Xjsr310 \
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
