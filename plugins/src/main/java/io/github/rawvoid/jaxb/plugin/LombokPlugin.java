/*
 * Copyright 2026 Rawvoid(https://github.com/rawvoid)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.rawvoid.jaxb.plugin;

import com.sun.codemodel.*;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import io.github.rawvoid.jaxb.plugin.xjc.AnnotationUtils;
import io.github.rawvoid.jaxb.plugin.lombok.LombokSingulars;
import io.github.rawvoid.jaxb.plugin.xjc.OutlineUtils;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Adds Lombok annotations to generated JAXB classes and optionally removes XJC-generated getters/setters.
 *
 * <p>Zero-config usage ({@code -Xlombok}) annotates every generated class with {@code @lombok.Data}
 * and removes getters and setters so Lombok owns them.</p>
 *
 * <p><b>Intentional limitations:</b></p>
 * <ul>
 *   <li>Does not generate bytecode; consumers must provide Lombok and annotation processing at compile time.</li>
 *   <li>By default all XJC getters are removed, including collection getters that lazy-init a live
 *       {@link List}. Lombok then returns the field as-is (may be {@code null}). Use
 *       {@code -keep-list-getter} to retain those XJC list getters while still stripping scalar getters.</li>
 *   <li>Builder modes (mutually exclusive):
 *       <ul>
 *         <li>{@code -builder}: smart mix — standalone concrete types get
 *             {@code @Builder(toBuilder = true)}; types in an inheritance chain (non-{@code Object}
 *             super, including episode/external parents, or having generated subclasses) get
 *             {@code @SuperBuilder(toBuilder = true)}.</li>
 *         <li>{@code -super-builder}: every matched class gets
 *             {@code @SuperBuilder(toBuilder = true)} (including abstract types). No inheritance
 *             heuristic.</li>
 *         <li>Either mode also adds NoArgs/AllArgs constructors as appropriate, and annotates
 *             {@link Collection}/{@link Map} fields with {@code @Singular(ignoreNullCollections = true)}
 *             (singular names via Lombok {@code Singulars.autoSingularize}; explicit field name when
 *             auto fails).</li>
 *         <li>{@code @SuperBuilder} remains under {@code lombok.experimental}.</li>
 *       </ul>
 *   </li>
 *   <li>{@code @EqualsAndHashCode(callSuper = true)} is auto-added only when the class has a non-{@code Object}
 *       superclass and the resolved annotation set includes {@code @Data}. If the user already supplies
 *       {@code @EqualsAndHashCode}, it is left unchanged.</li>
 *   <li>Lombok annotation classes must be visible to the XJC process (same as {@code -Xannotate} with Lombok).</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xlombok", description = "Add Lombok annotations to generated classes and optionally remove getters/setters")
public class LombokPlugin extends OptionPlugin {

    /**
     * Fully-qualified names of Lombok annotations (distinct from CLI option fields like {@code builder}).
     */
    private static final String LOMBOK_DATA = "lombok.Data";
    private static final String LOMBOK_EQUALS_AND_HASH_CODE = "lombok.EqualsAndHashCode";
    private static final String LOMBOK_BUILDER = "lombok.Builder";
    private static final String LOMBOK_SUPER_BUILDER = "lombok.experimental.SuperBuilder";
    private static final String LOMBOK_SINGULAR = "lombok.Singular";
    private static final String LOMBOK_NO_ARGS_CONSTRUCTOR = "lombok.NoArgsConstructor";
    private static final String LOMBOK_ALL_ARGS_CONSTRUCTOR = "lombok.AllArgsConstructor";

    @Option(name = "anno", description = "Lombok annotation to add (repeatable). Defaults to @lombok.Data when omitted")
    List<XAnnotation<?>> annotations;

    @Option(name = "class-name", description = "Regex to match fully-qualified class names")
    List<Pattern> classNames;

    @Option(name = "remove-getter", defaultValue = "true",
        description = "Remove generated getter methods (default: true)")
    Boolean removeGetter;

    @Option(name = "keep-list-getter", defaultValue = "false",
        description = "When removing getters, keep XJC getters for List/collection properties (lazy-init live list)")
    Boolean keepListGetter;

    @Option(name = "remove-setter", defaultValue = "true",
        description = "Remove generated setter methods (default: true)")
    Boolean removeSetter;

    @Option(name = "builder", defaultValue = "false",
        description = "Smart builders: @Builder or @SuperBuilder by inheritance; @Singular on collections (exclusive with -super-builder)")
    Boolean builder;

    @Option(name = "super-builder", defaultValue = "false",
        description = "Add @SuperBuilder(toBuilder=true) on every matched class; @Singular on collections (exclusive with -builder)")
    Boolean superBuilder;

    public LombokPlugin() {
        registerTextParser(XAnnotation.class, AnnotationUtils.xAnnotationTextParser());
    }

    /**
     * Full names of generated classes that should receive {@code @SuperBuilder}.
     * <ul>
     *   <li>Any type whose superclass is not {@code Object} (this-round, episode, or external)</li>
     *   <li>Any type that is the generated superclass of another type in this outline
     *       (so abstract bases in a hierarchy get SuperBuilder too)</li>
     * </ul>
     */
    static Set<String> collectSuperBuilderClassNames(Outline outline) {
        var generated = new LinkedHashMap<String, JDefinedClass>();
        for (var classOutline : outline.getClasses()) {
            generated.put(classOutline.implClass.fullName(), classOutline.implClass);
        }

        var result = new LinkedHashSet<String>();
        for (var implClass : generated.values()) {
            // Subclass of anything but Object → SuperBuilder (covers external / episode parents).
            if (hasNonObjectSuperclass(implClass)) {
                result.add(implClass.fullName());
            }
            // This-round parent of a generated child → SuperBuilder on the parent as well.
            var superClass = implClass._extends();
            if (superClass instanceof JDefinedClass parentDef
                && generated.containsKey(parentDef.fullName())) {
                result.add(parentDef.fullName());
            }
        }
        return result;
    }

    /**
     * JAXB needs a no-arg ctor; AllArgs only when there are fields (else it duplicates NoArgs).
     * Abstract SuperBuilder bases still get NoArgs so subclasses can chain.
     */
    private static void addBuilderConstructors(List<XAnnotation<?>> resolved, JDefinedClass implClass) {
        addIfAbsent(resolved, LOMBOK_NO_ARGS_CONSTRUCTOR, "@lombok.NoArgsConstructor");
        if (!implClass.isAbstract() && !implClass.fields().isEmpty()) {
            addIfAbsent(resolved, LOMBOK_ALL_ARGS_CONSTRUCTOR, "@lombok.AllArgsConstructor");
        }
    }

    /**
     * {@code Collection} or {@code Map} (including subtypes such as List/Set/SortedMap).
     * Simpler than a fixed FQCN allow-list; matches typical XJC field types.
     */
    static boolean isSingularCollectionField(JDefinedClass implClass, JFieldVar field) {
        if (!(field.type().erasure() instanceof JClass erasure)) {
            return false;
        }
        var cm = implClass.owner();
        return cm.ref(Collection.class).isAssignableFrom(erasure)
            || cm.ref(Map.class).isAssignableFrom(erasure);
    }

    private static void addIfAbsent(List<XAnnotation<?>> resolved, String fqcn, String source) {
        if (!containsAnnotation(resolved, fqcn)) {
            resolved.add(AnnotationUtils.parseXAnnotation(source));
        }
    }

    private static boolean hasData(List<XAnnotation<?>> resolved) {
        return containsAnnotation(resolved, LOMBOK_DATA);
    }

    private static boolean containsAnnotation(List<XAnnotation<?>> resolved, String fqcn) {
        return resolved.stream().anyMatch(a -> fqcn.equals(a.getAnnotationClass().getName()));
    }

    /**
     * True when the class extends something other than {@code java.lang.Object}.
     * Parent may be a same-round {@link JDefinedClass} or any external/episode {@code JClass}.
     */
    private static boolean hasNonObjectSuperclass(JDefinedClass implClass) {
        var superClass = implClass._extends();
        return superClass != null && !Object.class.getName().equals(superClass.fullName());
    }

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        if (Boolean.TRUE.equals(builder) && Boolean.TRUE.equals(superBuilder)) {
            throw new BadCommandLineException(
                "-builder and -super-builder are mutually exclusive; enable only one");
        }
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var buildersEnabled = Boolean.TRUE.equals(builder) || Boolean.TRUE.equals(superBuilder);
        // Only -builder needs inheritance detection; -super-builder annotates every class.
        var superBuilderNames = Boolean.TRUE.equals(builder)
            ? collectSuperBuilderClassNames(outline)
            : Set.<String>of();

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            var className = implClass.fullName();
            if (!matches(className)) {
                continue;
            }

            var resolved = resolveAnnotations(implClass, superBuilderNames);
            applyAnnotations(implClass, resolved);

            if (buildersEnabled) {
                annotateSingularOnCollectionFields(implClass);
            }

            OutlineUtils.removePropertyAccessors(
                classOutline,
                Boolean.TRUE.equals(removeGetter),
                Boolean.TRUE.equals(removeSetter),
                Boolean.TRUE.equals(keepListGetter));
        }
        return true;
    }

    private boolean matches(String className) {
        if (classNames == null || classNames.isEmpty()) {
            return true;
        }
        return classNames.stream().anyMatch(pattern -> pattern.matcher(className).matches());
    }

    /**
     * Whether this plugin configuration will apply {@code @lombok.Data} to {@code className}.
     * <p>
     * True when the class matches {@code -class-name} (or all classes when unset) and either
     * {@code -anno} is omitted (default {@code @Data}) or an explicit {@code -anno} includes
     * {@code @lombok.Data}. Used by other plugins (e.g. common-interface) without depending on
     * {@link #run} order.
     * </p>
     */
    public boolean appliesDataTo(String className) {
        if (className == null || !matches(className)) {
            return false;
        }
        if (annotations == null || annotations.isEmpty()) {
            return true;
        }
        return annotations.stream()
            .anyMatch(a -> LOMBOK_DATA.equals(a.getAnnotationClass().getName()));
    }

    /**
     * Whether any active {@link LombokPlugin} will apply {@code @Data} to {@code className}.
     */
    public static boolean anyActiveAppliesData(Options options, String className) {
        if (options == null || className == null) {
            return false;
        }
        for (var plugin : options.activePlugins) {
            if (plugin instanceof LombokPlugin lombokPlugin && lombokPlugin.appliesDataTo(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the final class-level annotation list: user or default {@code @Data}, optional builder
     * trio ({@code toBuilder = true}), and optional {@code @EqualsAndHashCode(callSuper = true)}.
     *
     * @param superBuilderNames used only for {@code -builder}: full names that need SuperBuilder
     *                          (see {@link #collectSuperBuilderClassNames(Outline)})
     */
    List<XAnnotation<?>> resolveAnnotations(JDefinedClass implClass, Set<String> superBuilderNames) {
        var resolved = new ArrayList<XAnnotation<?>>();
        if (annotations == null || annotations.isEmpty()) {
            resolved.add(AnnotationUtils.parseXAnnotation("@lombok.Data"));
        } else {
            resolved.addAll(annotations);
        }

        if (Boolean.TRUE.equals(superBuilder)) {
            // Force SuperBuilder on every matched class (including abstract).
            addIfAbsent(resolved, LOMBOK_SUPER_BUILDER,
                "@lombok.experimental.SuperBuilder(toBuilder = true)");
            addBuilderConstructors(resolved, implClass);
        } else if (Boolean.TRUE.equals(builder)) {
            if (superBuilderNames.contains(implClass.fullName())) {
                // Inheritance participant (subclass of non-Object and/or generated base).
                addIfAbsent(resolved, LOMBOK_SUPER_BUILDER,
                    "@lombok.experimental.SuperBuilder(toBuilder = true)");
                addBuilderConstructors(resolved, implClass);
            } else if (!implClass.isAbstract()) {
                // Standalone concrete type: official @Builder.
                addIfAbsent(resolved, LOMBOK_BUILDER, "@lombok.Builder(toBuilder = true)");
                addBuilderConstructors(resolved, implClass);
            }
        }

        if (hasData(resolved) && hasNonObjectSuperclass(implClass)
            && !containsAnnotation(resolved, LOMBOK_EQUALS_AND_HASH_CODE)) {
            resolved.add(AnnotationUtils.parseXAnnotation("@lombok.EqualsAndHashCode(callSuper = true)"));
        }
        return resolved;
    }

    /**
     * Annotates {@link Collection}/{@link Map} (and subtype) fields with
     * {@code @Singular(ignoreNullCollections = true)}.
     * <p>
     * Naming: {@link LombokSingulars#autoSingularize(String)}; if {@code null}, use field name
     * as explicit {@code value}.
     * </p>
     */
    private void annotateSingularOnCollectionFields(JDefinedClass implClass) {
        for (var field : implClass.fields().values()) {
            if ((field.mods().getValue() & JMod.STATIC) != 0) {
                continue;
            }
            if (!isSingularCollectionField(implClass, field)) {
                continue;
            }
            if (AnnotationUtils.hasAnnotation(field, LOMBOK_SINGULAR)) {
                continue;
            }

            // Auto path: omit value. If autoSingularize is null, value = field name (explicit singular).
            var use = field.annotate(implClass.owner().ref(LOMBOK_SINGULAR));
            use.param("ignoreNullCollections", true);
            if (LombokSingulars.autoSingularize(field.name()) == null) {
                use.param("value", field.name());
            }
        }
    }

    private void applyAnnotations(JDefinedClass implClass, List<XAnnotation<?>> resolved) {
        resolved.forEach(annotation -> AnnotationUtils.applyXAnnotation(implClass, annotation));
    }
}
