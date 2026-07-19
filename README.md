# 🚀 JAXB Plugins Collection

> **Supercharge your XML binding with next-gen JAXB plugins!**

[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![JAXB](https://img.shields.io/badge/JAXB-4.0+-blue.svg)](https://eclipse-ee4j.github.io/jaxb-ri/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://opensource.org/licenses/Apache-2.0)

Tired of boring, boilerplate-heavy JAXB code generation? Say hello to **JAXB Plugins Collection** – your ultimate toolkit for making XML binding actually *fun* again! 🎉

This collection of powerful plugins transforms the way you work with JAXB, giving you superpowers to customize, optimize, and streamline your generated code like never before.

---

## ✨ What's Inside?

### 🔧 **Core Framework**

Built on a rock-solid foundation with our `AbstractPlugin` class that makes creating new plugins a breeze. Features annotation-based configuration, automatic option parsing, and extensible text parsers.

---

## 🎯 Plugin Arsenal

### 📅 **JSR310Plugin** `-Xjsr310`

*Time travel made easy!*

Bring your JAXB code into the 21st century with full JSR-310 (java.time) support. Automatically maps XSD date/time types to modern Java time API classes.

**🔥 Use Cases:**

- Use `LocalDateTime` instead of `XMLGregorianCalendar`
- Support `ZonedDateTime` for timezone-aware dates
- Custom date formatting with patterns
- Auto-generate XmlAdapter classes

**⚡ Quick Start:**

```bash
-Xjsr310 \
  -adapter-package=com.example.adapters \
  -mapping \
  -xsd-type=dateTime \
  -target-class=java.time.LocalDateTime \
  -pattern=yyyy-MM-dd HH:mm:ss
```

**📝 Command Structure:**

```bash
-Xjsr310 \
  -adapter-package=package.name \
  -mapping \
  -xsd-type=xsdType \
  -target-class=java.time.Class \
  -pattern=dateFormat \
  -adapter=custom.AdapterClass \
  -regex=fieldPattern
```

**🎯 Default Mappings:**

- `xs:dateTime` → `LocalDateTime`
- `xs:date` → `LocalDate`
- `xs:time` → `LocalTime`
- `xs:gYearMonth` → `YearMonth`
- `xs:gYear` → `Year`
- `xs:gMonthDay` → `MonthDay`
- `xs:gDay` → `Integer`
- `xs:gMonth` → `Month`
- `xs:duration` → `Duration`

---

### 🏷️ **AnnotatePlugin** `-Xannotate`

*Your annotation wizard!*

Add, remove, or customize annotations on generated classes, fields, methods, and packages. Perfect for integrating with frameworks like Jackson, Hibernate, or your custom annotations.

**🔥 Use Cases:**

- Add `@JsonProperty` annotations for JSON serialization
- Inject validation annotations (`@NotNull`, `@Size`)
- Add Lombok annotations (`@Data`, `@Builder`, `@Accessors`) — or prefer dedicated `-Xlombok`
- Remove unwanted JAXB annotations
- Apply custom framework annotations

**⚡ Quick Start:**

```bash
-Xannotate \
  -add-to-class \
  -anno=@com.example.MyAnnotation \
  -regex=.*Person \
  -add-to-field \
  -anno=@com.fasterxml.jackson.annotation.JsonProperty("value") \
  -regex=.*name
```

**📝 Command Structure:**

```bash
-Xannotate \
  -add-to-class|-add-to-field|-add-to-method|-add-to-package \
  -anno=@AnnotationClass(param="value") \
  -regex=pattern \
  -remove-from-class|-remove-from-field|-remove-from-method|-remove-from-package \
  -anno=AnnotationClass \
  -regex=pattern
```

---

### 🔄 **ConvertNamePlugin** `-Xconvert-name`

*The naming ninja!*

Take control of how JAXB converts XML names to Java identifiers. Customize class names, field names, method names, and package names with precision.

**🔥 Use Cases:**

- Convert snake_case XML to camelCase Java
- Apply custom naming conventions
- Fix awkward auto-generated names
- Map legacy XML to modern Java standards

**⚡ Quick Start:**

```bash
-Xconvert-name \
  -class-name \
  -token=XMLDocument \
  -name=Document \
  -variable-name \
  -regex=(.*)_ID \
  -name=$1Id
```

**📝 Command Structure:**

```bash
-Xconvert-name \
  -class-name|-variable-name|-interface-name|-property-name|-constant-name|-package-name \
  -token=originalName \
  -regex=pattern \
  -name=newName
```

---

### 📦 **ElementWrapperPlugin** `-Xelement-wrapper`

*The wrapper eliminator!*

Simplify your generated code by automatically flattening wrapper classes. Moves `@XmlElementWrapper` and `@XmlElement` annotations to the using field and optionally removes the wrapper class entirely.

**🔥 Use Cases:**

- Clean up collection wrapper classes
- Reduce boilerplate code
- Improve API readability
- Optimize memory usage

**⚡ Quick Start:**

```bash
-Xelement-wrapper \
  -remove-wrapper-class=true
```

---

### 🧩 **PromoteNestedClassPlugin** `-Xpromote-nested-class`

*The nested-type promoter!*

Promotes nested beans **and enums** toward package scope one parent level at a time. Promotion stops when a simple name is already taken under the target parent (beans and enums share that namespace). Keeps generated APIs shallow without unsafe renames.

**🔥 Use Cases:**

- Promote nested classes and enums toward top-level types
- Reduce deeply nested anonymous complex types / local enums
- Stop cleanly on name collisions (including class vs enum)

**⚡ Quick Start:**

```bash
-Xpromote-nested-class
```

**Notes:**

- Runs in `postProcessModel` (rewrites model parents before code generation)
- Name checks are case-insensitive
- ObjectFactory method names may shorten after a successful lift

---

### 🌍 ~~**NamespacePlugin** `-Xnamespace`~~

~~*The namespace navigator!*~~

~~**This plugin is deprecated and may be removed in future versions.**~~

~~Take control of XML namespace to Java package mappings. Define custom mappings and automatically generate JAXB binding files.~~

~~**🔥 Use Cases:**~~
~~- Map namespaces to meaningful package names~~
~~- Avoid default package naming conflicts~~
~~- Support multiple schema versions~~
~~- Clean up package structure~~

~~**⚡ Quick Start:**~~

```bash
-Xnamespace \
  -mapping \
  -ns=http://example.com/schema \
  -package=com.example.schema \
  -prefix=ex
```

~~**📝 Command Structure:**~~

```bash
-Xnamespace \
  -mapping \
  -ns=namespaceURI \
  -package=java.package.name \
  -prefix=xmlPrefix
```

---

### ☕ **LombokPlugin** `-Xlombok`

*Lombok-powered JAXB beans in one switch!*

Adds Lombok annotations to generated classes and removes XJC-generated getters/setters so Lombok owns accessors. Prefer this over combining `-Xannotate` + `-Xremove-getter` + `-Xremove-setter`.

**🔥 Use Cases:**

- Replace boilerplate getters/setters with `@Data`
- Optional `@Builder` (with JAXB-friendly constructors)
- Auto `@EqualsAndHashCode(callSuper = true)` for subclasses of non-`Object` types

**⚡ Quick Start:**

```bash
-Xlombok
```

**📝 Options:**

```bash
-Xlombok \
  -anno=@lombok.Data \                 # repeatable; omit for default @Data
  -regex=.*Person \                    # optional class filter
  -remove-getter=true \                # default true
  -remove-setter=true \                # default true
  -builder                             # default false; adds @Builder + @NoArgsConstructor + @AllArgsConstructor
```

**🎯 Defaults:**

- Annotates with `@lombok.Data`
- Removes getters and setters
- If the class extends a non-`Object` type and `@Data` is present, adds `@EqualsAndHashCode(callSuper = true)`
- `-builder` is off; when enabled on **concrete** classes, adds `@Builder` + `@NoArgsConstructor`, and `@AllArgsConstructor` when the class has fields (JAXB-friendly)

**⚠️ Notes:**

- Lombok must be on the XJC classpath (to resolve annotation types) and on the compile classpath with annotation processing enabled
- Uses standard `@Builder`, not `@SuperBuilder` (pass `@SuperBuilder` via `-anno` if needed); abstract classes are not annotated with `@Builder`

---

### 🗑️ **RemoveGetterPlugin** `-Xremove-getter`

*The getter ghost!*

Remove XJC-generated **property** getters (matched via the property model, not every `get*`/`is*` method).

**🔥 Use Cases:**

- Create immutable-like structures
- Reduce method count for cleaner APIs
- Optimize for specific use cases
- Custom access patterns
- Pair with Lombok (`-Xlombok`) instead of hand-rolling annotate + remove

**⚡ Quick Start:**

```bash
-Xremove-getter
```

---

### 🗑️ **RemoveSetterPlugin** `-Xremove-setter`

*The setter slayer!*

Remove XJC-generated **property** setters (matched via the property model, not every `set*` method).

**🔥 Use Cases:**

- Create read-only DTOs
- Enforce immutability
- Secure data transfer objects
- Clean API design

**⚡ Quick Start:**

```bash
-Xremove-setter
```

---

### 🏷️ **NsPrefixPlugin** `-Xns-prefix`

*The namespace prefix master!*

Take control of XML namespace prefixes in generated @XmlSchema annotations. Define custom mappings between XML namespaces and their prefixes, and automatically update package-info.java files.

**🔥 Use Cases:**

- Set consistent XML namespace prefixes across generated code
- Customize prefixes for specific packages using regex patterns
- Manage multiple namespaces in complex XML schemas
- Replace default JAXB-generated prefixes with meaningful ones

**⚡ Quick Start:**

```bash
-Xns-prefix \
  -config \
  -xmlns \
  -ns=http://example.com \
  -prefix=ex
```

**📝 Command Structure:**

```bash
-Xns-prefix \
  -config \
    -package=package.regex.pattern \
    -xmlns \
    -ns=namespaceURI \
    -prefix=xmlPrefix \
    -xmlns \
    -ns=anotherNamespace \
    -prefix=anotherPrefix \
  -config \
    -package=another.package.regex \
    -xmlns \
    -ns=namespaceURI \
    -prefix=prefix
```

**🎯 Advanced Examples:**

```bash
# Apply to all packages
-Xns-prefix -config -xmlns -ns=http://example.com -prefix=ex

# Package-specific configuration
-Xns-prefix -config -package=com\.example\.* -xmlns -ns=http://example.com -prefix=ex

# Multiple namespaces for a package
-Xns-prefix -config -package=com\.example\.* -xmlns -ns=http://example.com -prefix=ex -xmlns -ns=http://test.com -prefix=tst
```

---

## 🚀 Getting Started

### 📦 Installation

Add this to your Maven `pom.xml`:

```xml

<dependency>
  <groupId>io.github.rawvoid</groupId>
  <artifactId>jaxb-plugins</artifactId>
  <version>2.0.0-beta.1</version>
</dependency>
```

### 🔧 Maven Plugin Setup

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
        <version>2.0.0-beta.1</version>
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

### 🎯 Command Line Usage

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

## 🎨 Advanced Examples

### 🌟 **Full Stack Example**

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

### 🔥 **Microservice Example**

```bash
xjc -d src \
  -Xjsr310 \
    -mapping \
    -xsd-type=dateTime \
    -target-class=java.time.Instant \
    -mapping \
    -xsd-type=date \
    -target-class=java.time.LocalDate \
    -pattern=yyyy-MM-dd \
  -Xelement-wrapper \
    -remove-wrapper-class=true \
  -Xremove-getter \
  schema.xsd
```

### ☕ **Lombok Integration Example**

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

**🎯 What this does:**

- Adds `@Data` (and optional extra annotations) to generated classes
- Removes XJC-generated getters/setters (Lombok regenerates them)
- With `-builder`, also adds `@Builder`, `@NoArgsConstructor`, and `@AllArgsConstructor`
- Subclasses automatically get `@EqualsAndHashCode(callSuper = true)` when `@Data` is present

The older multi-plugin recipe (`-Xannotate` + `-Xremove-getter` + `-Xremove-setter`) still works if you need finer control.

---

## 🛠️ Building from Source

```bash
git clone https://github.com/rawvoid/jaxb-plugins.git
cd jaxb-plugins
mvn clean install
```

**Requirements:**

- Java 21+
- Maven 3.6+

---

## 🤝 Contributing

Got an awesome plugin idea? Found a bug? Want to make things even better? We'd love your help! 🎉

1. **Fork** this repo
2. **Create** your feature branch (`git checkout -b feature/amazing-plugin`)
3. **Commit** your changes (`git commit -m 'Add mind-blowing plugin'`)
4. **Push** to the branch (`git push origin feature/amazing-plugin`)
5. **Open** a Pull Request

---

## 📜 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

## 🌟 Star History

[![Star History Chart](https://api.star-history.com/svg?repos=rawvoid/jaxb-plugins&type=Date)](https://star-history.com/#rawvoid/jaxb-plugins&Date)

---

## 💬 Got Questions?

- 🐛 Report issues on [GitHub Issues](https://github.com/rawvoid/jaxb-plugins/issues)
- 💬 Join the discussion in [Discussions](https://github.com/rawvoid/jaxb-plugins/discussions)

---

## 🙏 Acknowledgments

- Built with ❤️ using [JAXB Reference Implementation](https://eclipse-ee4j.github.io/jaxb-ri/)
- Inspired by the amazing [JAXB Annox](https://github.com/highsource/jaxb-annox) project
- Thanks to all the contributors who make this project awesome!

---

<div align="center">

**⭐ If this project made your JAXB life easier, give it a star! ⭐**

Made with ☕ and 🎵 by [Rawvoid](https://github.com/rawvoid)

</div>
