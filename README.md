# jaxb-plugins

XJC plugins that customize JAXB code generation for **Jakarta EE** (JAXB 3 / XJC 4).

The artifact is discovered by XJC via SPI (`META-INF/services/com.sun.tools.xjc.Plugin`). Each plugin is opt-in through a `-X…` command-line option.

| | |
|---|---|
| Group ID | `io.github.rawvoid` |
| Artifact ID | `jaxb-plugins` |
| Java | 21+ |
| License | [Apache License 2.0](LICENSE) |
| Repository | https://github.com/rawvoid/jaxb-plugins |

## Documentation

Full per-plugin reference (options, lifecycle, limits, examples):

**[wiki/](wiki/README.md)**

## Requirements

- **Java 21+**
- XJC / JAXB tooling compatible with GlassFish JAXB XJC 4.x (Jakarta namespace)
- Optional runtime/annotation libraries on the **XJC plugin classpath** when enabling related plugins:
  - Lombok — `-Xlombok`
  - `jackson-annotations` — `-Xjackson`
  - Bean Validation API (`jakarta.validation` preferred) — `-Xvalidation`

## Maven dependency

Add `jaxb-plugins` as a **plugin dependency** of your XJC Maven plugin (not only as a project compile dependency), then pass plugin arguments to XJC.

Example with [`org.jvnet.jaxb:jaxb-maven-plugin`](https://github.com/highsource/jaxb-tools):

```xml
<plugin>
  <groupId>org.jvnet.jaxb</groupId>
  <artifactId>jaxb-maven-plugin</artifactId>
  <version><!-- your version --></version>
  <configuration>
    <args>
      <arg>-Xlombok</arg>
      <arg>-Xjava-time</arg>
      <!-- more -X… options -->
    </args>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>io.github.rawvoid</groupId>
      <artifactId>jaxb-plugins</artifactId>
      <version><!-- release version --></version>
    </dependency>
    <!-- when using -Xlombok / -Xjackson / -Xvalidation, add those APIs here too -->
  </dependencies>
</plugin>
```

Other XJC integrations (CXF `cxf-xjc-plugin`, CLI, Gradle, etc.) work the same way: put the jar on the XJC plugin classpath and pass `-X…` args.

## Plugins

| Option | Role |
|--------|------|
| `-Xremove-getter` | Remove generated property getters |
| `-Xremove-setter` | Remove generated property setters |
| `-Xlombok` | Lombok annotations; optional getter/setter removal and builders |
| `-Xconvert-name` | Custom `NameConverter` rules during binding |
| `-Xrename-class` | Rename class/enum/element types in the model |
| `-Xrename-multi-element-prop` | Rename multi-element properties (`items`, `items2`, …) |
| `-Xpromote-nested-class` | Lift nested beans/enums toward package scope |
| `-Xelement-wrapper` | Flatten pure collection shells with `@XmlElementWrapper` |
| `-Xflatten-multi-element-prop` | Split multi-element properties into single fields |
| `-Xdedupe-class` | Merge structurally redundant beans |
| `-Xremove-unused-class` | Remove unreferenced classes and enums |
| `-Xannotate` | Add/remove custom annotations |
| `-Xgenerated` | Add `@jakarta.annotation.Generated` |
| `-Xjackson` | Class-level Jackson defaults |
| `-Xvalidation` | Bean Validation constraints from XSD |
| `-Xjava-time` | Map XSD date/time types to `java.time` + adapters |
| `-Xnamespace` | Namespace → package mappings and `@XmlSchema` prefixes |
| `-Xinheritance` | Inject `implements` / `extends` / `Serializable` |
| `-Xcommon-interface` | Generate shared interfaces from common properties |

Details, sub-options, and inter-plugin notes: **[wiki/README.md](wiki/README.md)**.

## Build from source

```bash
mvn clean install
```

Run tests for the plugins module only:

```bash
mvn -pl plugins test
```

## Project layout

```text
jaxb-plugins/
├── plugins/          # main artifact (plugin sources, SPI, tests)
├── wiki/             # user documentation for each plugin
├── pom.xml           # parent POM
└── LICENSE
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
