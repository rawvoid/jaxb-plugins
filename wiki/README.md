# jaxb-plugins Wiki

User documentation for **jaxb-plugins**: a set of XJC plugins that customize JAXB code generation for Jakarta EE.

Each page describes one SPI-registered plugin, derived from the current plugin source under `plugins/src/main/java/io/github/rawvoid/jaxb/plugin/`.

## Enabling plugins

1. Put the `jaxb-plugins` artifact on the **XJC plugin classpath** (Maven: `jaxb2-maven-plugin` / `cxf-xjc-plugin` plugin dependency, or equivalent).
2. Pass the plugin switch and sub-options as XJC arguments, for example `-Xlombok` or `-Xrename-class -strip-type-suffix`.
3. Some plugins need extra libraries on that same classpath when enabled (e.g. Jackson annotations for `-Xjackson`, Bean Validation API for `-Xvalidation`, Lombok annotation types for `-Xlombok`).

Plugins are discovered via SPI (`META-INF/services/com.sun.tools.xjc.Plugin`). Enabling is always opt-in through the `-X…` option name.

## Plugin index

### Accessors / Lombok

| Plugin | Option | Doc |
|--------|--------|-----|
| RemoveGetterPlugin | `-Xremove-getter` | [remove-getter](remove-getter.md) |
| RemoveSetterPlugin | `-Xremove-setter` | [remove-setter](remove-setter.md) |
| LombokPlugin | `-Xlombok` | [lombok](lombok.md) |

### Naming

| Plugin | Option | Doc |
|--------|--------|-----|
| ConvertNamePlugin | `-Xconvert-name` | [convert-name](convert-name.md) |
| RenameClassPlugin | `-Xrename-class` | [rename-class](rename-class.md) |
| RenameMultiElementPropPlugin | `-Xrename-multi-element-prop` | [rename-multi-element-prop](rename-multi-element-prop.md) |
| PromoteNestedClassPlugin | `-Xpromote-nested-class` | [promote-nested-class](promote-nested-class.md) |

### Model reshape

| Plugin | Option | Doc |
|--------|--------|-----|
| ElementWrapperPlugin | `-Xelement-wrapper` | [element-wrapper](element-wrapper.md) |
| FlattenMultiElementPropPlugin | `-Xflatten-multi-element-prop` | [flatten-multi-element-prop](flatten-multi-element-prop.md) |
| DedupeClassPlugin | `-Xdedupe-class` | [dedupe-class](dedupe-class.md) |
| RemoveUnusedClassPlugin | `-Xremove-unused-class` | [remove-unused-class](remove-unused-class.md) |

### Annotations / mapping

| Plugin | Option | Doc |
|--------|--------|-----|
| AnnotatePlugin | `-Xannotate` | [annotate](annotate.md) |
| GeneratedAnnoPlugin | `-Xgenerated-anno` | [generated-anno](generated-anno.md) |
| JacksonPlugin | `-Xjackson` | [jackson](jackson.md) |
| ValidationPlugin | `-Xvalidation` | [validation](validation.md) |
| JavaTimePlugin | `-Xjava-time` | [java-time](java-time.md) |

### Namespace / inheritance

| Plugin | Option | Doc |
|--------|--------|-----|
| NamespacePlugin | `-Xnamespace` | [namespace](namespace.md) |
| InheritancePlugin | `-Xinheritance` | [inheritance](inheritance.md) |
| CommonInterfacePlugin | `-Xcommon-interface` | [common-interface](common-interface.md) |

## Inter-plugin notes (from source)

- **`-Xflatten-multi-element-prop` and `-Xrename-multi-element-prop` are mutually exclusive** — one splits multi-element properties; the other only renames them.
- Prefer running **`-Xelement-wrapper` after** other plugins that mutate the C* model (dedupe, promote, rename, flatten-multi) so flatten records refer to final owners.
- **`-Xdedupe-class -preserve-wrapper-shells`**: default is **auto** — on when `-Xelement-wrapper` is also active, so pure collection shells are not merged into non-shell hosts before wrapper flattening.
- When both **`-Xrename-class`** and **`-Xpromote-nested-class`** are active: rename-before-promote lets named global types claim short names first; promote-first can leave dual names (`Foo` + `FooType`) in some cases.
- **`-Xcommon-interface`** detects Lombok `@Data` from annotations already on the class or from an active `-Xlombok` configuration (independent of plugin `run` order).
