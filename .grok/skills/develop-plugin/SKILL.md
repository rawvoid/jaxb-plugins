---
name: develop-plugin
description: >
  Guide for developing JAXB/XJC plugins in this repository: AbstractPlugin, @Option,
  SPI registration, model/outline hooks, plugin.xjc / plugin.lombok helpers, and
  AbstractXJCMojoTestCase tests. Use when adding a new plugin, extending an existing
  one, wiring -X… options, or the user runs /develop-plugin. Triggers: new plugin,
  XJC plugin, AbstractPlugin, @Option, SPI Plugin, postProcessModel, jaxb plugin.
---

# Develop JAXB / XJC Plugin

Actionable workflow for adding or changing plugins under `plugins/`. Prefer existing patterns over inventing a parallel framework. Follow `AGENTS.md` (simple first, Java 21+, English Javadoc/comments). Reply to the user in their language.

## When this skill applies

- New XJC plugin or new `-X…` option
- Changing lifecycle behavior (`run`, `postProcessModel`, `postParseArgument`)
- Tests that run XJC via Maven mojo harness
- Wiring SPI / option parsing / nested CLI configs

## Layout (where things live)

| Piece | Path / package |
|-------|----------------|
| SPI plugins | `plugins/src/main/java/io/github/rawvoid/jaxb/plugin/` (`*Plugin`) |
| Option framework | `…/plugin/option/` — `AbstractPlugin`, `@Option`, `@Compact`, `TextParser` |
| XJC host helpers | `…/plugin/xjc/` — `ModelUtils`, `OutlineUtils`, `AnnotationUtils`, `ReflectUtils`, … |
| Lombok bridge | `…/plugin/lombok/` — `LombokShadow`, `LombokAccessors`, `LombokSingulars` |
| SPI | `plugins/src/main/resources/META-INF/services/com.sun.tools.xjc.Plugin` |
| Plugin tests | `plugins/src/test/java/io/github/rawvoid/jaxb/plugin/` |
| Helper tests | mirror main: `…/plugin/xjc/`, `…/plugin/lombok/` |
| Schemas | `plugins/src/test/resources/schema/` |
| Test base | `plugins/src/test/java/io/github/rawvoid/jaxb/AbstractXJCMojoTestCase.java` |

Dependency direction: `*Plugin` → `plugin.option` / `plugin.xjc` / `plugin.lombok`. Helpers must not depend on concrete plugins.

## Step 1 — Choose the extension point

Use the **earliest correct** hook. Do not rewrite CodeModel for problems that belong in the model phase.

| Need                                                                            | Hook                                            | Reference                              |
|---------------------------------------------------------------------------------|-------------------------------------------------|----------------------------------------|
| Parse CLI / cross-field validation / install converters                         | `@Option` fields + optional `postParseArgument` | `ConvertNamePlugin`, `NamespacePlugin` |
| Custom name conversion before model build                                       | Set `NameConverter` in `postParseArgument`      | `ConvertNamePlugin`                    |
| Restructure `C*` model (properties, types, remove classes) before BeanGenerator | `postProcessModel(Model, ErrorHandler)`         | `ElementWrapperPlugin`                 |
| Mutate generated CodeModel (methods, annotations, adapters, package-info)       | `run(Outline, Options, ErrorHandler)`           | Most plugins                           |
| Model rewrite **and** annotations only XJC never emits                          | Both `postProcessModel` and `run`               | `ElementWrapperPlugin`                 |

**Rule of thumb:** collection / property-tree shape → model phase; annotations, getters/setters, adapters, package-info → `run`. State intentional limits in English Javadoc (see `ElementWrapperPlugin`).

## Step 2 — Scaffold the plugin

1. Package: `io.github.rawvoid.jaxb.plugin` (imports: `plugin.option.AbstractPlugin`, `plugin.option.Option`)
2. Class name: `*Plugin extends AbstractPlugin`
3. Class-level `@Option(name = "X…", description = "…")`

- `name` has **no** leading `-` (default `prefix` is `-` → CLI is `-Xfoo`)

4. Implement `run` and/or other hooks; return `true` on success
5. Field-level `@Option` for sub-options
6. Copyright header + style matching neighboring plugins
7. Prefer `var` when the type is obvious; keep methods one abstraction level; no dead branches

### Minimal plugin (template)

```java
import io.github.rawvoid.jaxb.plugin.option.AbstractPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;

@Option(name = "Xmy-feature", description = "Short user-facing description")
public class MyFeaturePlugin extends AbstractPlugin {

  @Option(name = "regex", description = "Optional name filter")
  Pattern regex;

  @Override
  public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
    for (var classOutline : outline.getClasses()) {
      // mutate classOutline.implClass as needed
    }
    return true;
  }
}
```

Simplest real examples: `RemoveGetterPlugin`, `RemoveSetterPlugin`.

### Options (`@Option` + `AbstractPlugin`)

| Field type                                     | CLI shape                       | Notes                                                                                                       |
|------------------------------------------------|---------------------------------|-------------------------------------------------------------------------------------------------------------|
| `boolean` / `Boolean`                          | `-flag`                         | Presence sets `true`                                                                                        |
| `String`, numbers, `Class`, `Pattern`          | `-name=value`                   | Built-in text parsers                                                                                       |
| `List<T>` of scalars                           | repeat `-name=value`            | Repeatable; may interleave with other root options                                                          |
| Nested static class / `List<Nested>`           | `-group` then nested `-child=…` | Group marker once; repeated child field starts next item; nested class needs no-arg ctor + `@Option` fields |
| Nested type / list field `@Compact(formats=…)` | `-group={a}->{b}`               | Auto `TextParser`; try templates in order; field-level overrides type; structured form still works          |

Useful attributes: `required`, `defaultValue`, `description`, `placeholder`, `delimiter` (default `=`), `prefix` (default `-`).

- Custom parse types: `registerTextParser(Class, TextParser)` or by option name in the constructor (`AnnotatePlugin` + `XAnnotation`).
- After parse + defaults + required checks: override `postParseArgument(Options, int)` for side effects or multi-field validation.
- Nested / repeatable configs: `NamespacePlugin` (`List<PackageMappingConfig>` / `List<NsPrefixConfig>`), `JavaTimePlugin` (`List<TypeMappingConfig>`), `AnnotatePlugin` (add/remove groups).

### Shared helpers (reuse before reinventing)

Package `io.github.rawvoid.jaxb.plugin.xjc` (and `plugin.lombok` for Lombok naming):

| Type | Use when |
|------|----------|
| `ModelUtils` | Model graph, property parents, remove class from model |
| `OutlineUtils` | Fix refs after class removal, ObjectFactory, `JAXBDebug#createContext` |
| `AnnotationUtils` | Query/add/remove CodeModel annotations, apply annox `XAnnotation`, type refs in annotation members |
| `ReflectUtils` | Localized reflection helpers |
| `LombokAccessors` / `LombokSingulars` | Match Lombok default accessor / `@Singular` naming (`plugin.lombok`) |

Prefer public XJC / CodeModel APIs. If package-private fields force reflection, keep it localized (static `Field` constants like `PromoteNestedClassPlugin` or `plugin.xjc`).

### Design rules for generated output

- Preserve observable order (`propOrder`, field order) when rewriting models.
- Align annotations with stock XJC defaulting (`##default`, `package-info`); do not restate redundant namespace/name members.
- Document unsupported cases in Javadoc instead of half-implementing.
- No speculative extension points or defensive code for impossible states.

## Step 3 — Register SPI

Append the FQCN to:

`plugins/src/main/resources/META-INF/services/com.sun.tools.xjc.Plugin`

Without this line, XJC never loads the plugin.

## Step 4 — Tests

1. Class: `plugins/src/test/java/io/github/rawvoid/jaxb/plugin/<Name>PluginTest`
2. Extend `AbstractXJCMojoTestCase`
3. **One dedicated schema per test class** (required):

- add `plugins/src/test/resources/schema/<plugin-id>.xsd` with its own `targetNamespace`
- in `@BeforeEach`: `schemaIncludes = List.of("<plugin-id>.xsd");`
- do **not** share schemas across plugin tests; harness fails if `schemaIncludes` is empty
- put contrast types in the same file when a negative/filter case needs them

4. Pass real CLI args: `testExecute(List.of("-Xmy-feature", …), "package\\.Class", (source, clazz) -> { … })`
5. Prefer precise FQCN filters over loose `.*Person` patterns
6. Assert on compiled `Class` and/or generated source string; prefer AssertJ (`assertThat`)
7. Cover at least:

- baseline without plugin when behavior differs
- happy path with the real option string shape

8. Run focused suite:

```bash
mvn -pl plugins test -Dtest=<Name>PluginTest
```

Widen to `mvn -pl plugins test` if the change can affect shared `plugin.xjc` helpers.

Reference: `PromoteNestedClassPluginTest`, `AnnotatePluginTest`, `LombokPluginTest`.

## Step 5 — Done checklist

- [ ] Class `@Option` + field options complete
- [ ] Correct lifecycle hook (s)
- [ ] SPI entry added
- [ ] Focused test (+ schema if needed) green
- [ ] English Javadoc for non-obvious limits
- [ ] Local conventional commit when the unit of work is coherent (`feat(plugin): …`, `fix: …`)
- [ ] **Do not** `git push` / open PRs unless the user asks
- [ ] README user docs only if the user requests them

## Existing plugins (quick map)

| Class                      | Option                   | Role                                                                   |
|----------------------------|--------------------------|------------------------------------------------------------------------|
| `RemoveGetterPlugin`       | `-Xremove-getter`        | Minimal `run` method removal                                           |
| `RemoveSetterPlugin`       | `-Xremove-setter`        | Minimal `run` method removal                                           |
| `AnnotatePlugin`           | `-Xannotate`             | Nested configs + custom `TextParser`                                   |
| `JavaTimePlugin`           | `-Xjava-time`            | Modern `java.time` mapping + adapters on fields                        |
| `ConvertNamePlugin`        | `-Xconvert-name`         | `postParseArgument` + `NameConverter`                                  |
| `ElementWrapperPlugin`     | `-Xelement-wrapper`      | Dual-phase: model flatten + wrapper annotation                         |
| `PromoteNestedClassPlugin` | `-Xpromote-nested-class` | Promote nested beans/enums via `postProcessModel`                      |
| `RenameClassPlugin`        | `-Xrename-class`         | Class short-name mapping (`-mapping`)                                  |
| `NamespacePlugin`          | `-Xnamespace`            | Namespace→package bindings + `@XmlSchema` prefixes                     |
| `JacksonPlugin`            | `-Xjackson`              | Class-level Jackson defaults (`@JsonInclude`, ignoreUnknown) + `-anno` |
| `ValidationPlugin`         | `-Xvalidation`           | Bean Validation constraints (@NotNull, @Size, @Valid, etc.)            |
| `InheritancePlugin`        | `-Xinheritance`          | Interface (`implements`) and superclass (`extends`) injection          |

Framework types (`plugin.option`): `AbstractPlugin`, `Option`, `Compact`, `TextParser`.

## Workflow for the agent

1. Clarify the desired generated behavior and CLI shape with the user if ambiguous.
2. Pick lifecycle hook (s) using the table above.
3. Implement plugin + SPI.
4. Add/adjust tests and schema fixtures.
5. Run the smallest relevant Maven test.
6. Local commit when the change is a coherent unit.
7. Summarize for the user: option name, files touched, how to enable the plugin, test command.
