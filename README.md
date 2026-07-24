# JAXB Plugins Collection

[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![JAXB](https://img.shields.io/badge/JAXB-4.0+-blue.svg)](https://eclipse-ee4j.github.io/jaxb-ri/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://opensource.org/licenses/Apache-2.0)

A suite of extensible XJC (JAXB) plugins designed to streamline XML-to-Java binding, customize generated code structures, and eliminate boilerplate in modern Java projects (Java 21+).

---

## Key Capabilities

- **Modern Standards**: Full support for Java 21+ and `java.time` (JSR-310) date/time API mapping.
- **Flexible Configuration**: Supports compact inline mappings (`pattern->replacement`) and structured nested CLI flags.
- **Boilerplate Reduction**: Direct Lombok integration (`-Xlombok`), automated wrapper class flattening (`-Xelement-wrapper`), and getter/setter removal.
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
  -mapping \
  -xsd-type=dateTime \
  -target-class=java.time.OffsetDateTime
```

#### Command Options

```bash
-Xjsr310 \
  -adapter-package=package.name \      # Optional: defaults to <common_package>.adapter
  -mapping \                           # Group marker
  -xsd-type=xsdType \
  -target-class=java.time.Class \
  -pattern=dateFormat \
  -adapter=custom.AdapterClass \
  -regex=fieldPattern \
  -xsd-type=anotherType \              # Repeated child field starts next item
  -target-class=java.time.LocalDate
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

### Annotate Plugin (`-Xannotate`)

Adds, removes, or modifies annotations on generated classes, fields, methods, and package metadata.

#### Key Features

- Inject JSON binding annotations (e.g., Jackson `@JsonProperty`).
- Add Jakarta validation constraints (`@NotNull`, `@Size`).
- Attach framework-specific metadata.
- Strip unwanted JAXB-generated annotations.

#### Quick Start

```bash
-Xannotate \
  -add-to-class \
  -anno=@com.example.MyAnnotation \
  -regex=.*Person \
  -add-to-field \
  -anno=@com.fasterxml.jackson.annotation.JsonProperty("value") \
  -regex=.*name
```

#### Command Options

```bash
-Xannotate \
  -add-to-class \                      # Target kind: class
  -anno=@AnnotationClass(param="value") \
  -regex=pattern \
  -add-to-field \                      # Target kind: field
  -anno=@AnnotationClass(param="value") \
  -regex=pattern \
  -remove-from-class|-remove-from-field|-remove-from-method|-remove-from-package \
  -anno=AnnotationClass \
  -regex=pattern
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
  -regex=(.*)_ID \
  -name=$1Id
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
  -regex=(.*)_ID \
  -name=$1Id
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

Controls the mapping between XML namespaces and Java package structures.

#### Key Features

- Defines explicit package mappings per namespace URI.
- Prevents default package conflicts across complex multi-schema projects.

#### Quick Start

```bash
-Xnamespace \
  -mapping \
  -ns=http://example.com/schema \
  -package=com.example.schema \
  -prefix=ex
```

#### Command Options

```bash
-Xnamespace \
  # Compact format:
  -mapping=namespaceURI->java.package.name \
  -mapping=namespaceURI->java.package.name:xmlPrefix \
  # Structured format:
  -mapping \
  -ns=namespaceURI \
  -package=java.package.name \
  -prefix=xmlPrefix
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
  -regex=.*Person \                    # Optional class filter
  -remove-getter=true \                # Default: true
  -remove-setter=true \                # Default: true
  -builder                             # Default: false; adds @Builder + @NoArgsConstructor + @AllArgsConstructor
```

> **Note**: Lombok dependencies must be present on both the XJC classpath (for annotation resolution) and compile classpath.

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

### NS Prefix Plugin (`-Xns-prefix`)

Manages XML namespace prefixes in generated `@XmlSchema` annotations and `package-info.java` files.

#### Key Features

- Ensures consistent XML namespace prefixes across generated packages.
- Supports package filtering via regular expressions.

#### Quick Start

```bash
-Xns-prefix \
  -config \
  -xmlns \
  -ns=http://example.com \
  -prefix=ex
```

#### Command Options

```bash
-Xns-prefix \
  -config \
  -package=com\.example\.* \
  -xmlns \
  -ns=http://example.com \
  -prefix=ex
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
      <arg>-Xns-prefix</arg>
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
    -Xns-prefix \
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
    -mapping \
    -xsd-type=dateTime \
    -target-class=java.time.LocalDateTime \
  -Xannotate \
    -add-to-class \
    -anno=@com.fasterxml.jackson.annotation.JsonInclude(JsonInclude.Include.NON_NULL) \
    -add-to-field \
    -anno=@com.fasterxml.jackson.annotation.JsonProperty("value") \
    -regex=.*\.value \
  -Xconvert-name \
    -class-name \
    -regex=(.*)Type \
    -name=$1DTO \
    -variable-name \
    -regex=(.*)_ID \
    -name=$1Id \
  -Xnamespace \
    -mapping \
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
