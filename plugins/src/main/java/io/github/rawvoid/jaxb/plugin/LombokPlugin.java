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

import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMod;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.utils.LombokSingulars;
import io.github.rawvoid.jaxb.utils.OutlineUtils;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 *   <li>When {@code -builder} is enabled:
 *       <ul>
 *         <li><strong>Standalone</strong> types (superclass is {@code Object}, no generated subclasses)
 *             get {@code @lombok.Builder(toBuilder = true)}.</li>
 *         <li>Types that <strong>extend a non-{@code Object} superclass</strong> get
 *             {@code @lombok.experimental.SuperBuilder(toBuilder = true)}. The superclass may be
 *             generated this round, from episode/classpath, or otherwise external — only types this
 *             plugin emits are annotated. Lombok still accepts SuperBuilder on a subclass when the
 *             parent has no SuperBuilder.</li>
 *         <li>Types that are <strong>extended by another generated class</strong> also get
 *             SuperBuilder (including abstract bases).</li>
 *         <li>{@code java.util.List} fields get {@code @Singular(ignoreNullCollections = true)}.
 *             Singular method names use Lombok's singularization table (same rules as APT): when
 *             auto-singularize succeeds the value is omitted; when it fails the field name is
 *             used as the explicit {@code value} so generation still compiles.</li>
 *         <li>{@code @SuperBuilder} remains under {@code lombok.experimental}.</li>
 *         <li>{@code @AllArgsConstructor} is omitted when there are no fields (would collide with
 *             {@code @NoArgsConstructor}).</li>
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
public class LombokPlugin extends AbstractPlugin {

    /** Fully-qualified names of Lombok annotations (distinct from CLI option fields like {@code builder}). */
    private static final String LOMBOK_DATA = "lombok.Data";
    private static final String LOMBOK_EQUALS_AND_HASH_CODE = "lombok.EqualsAndHashCode";
    private static final String LOMBOK_BUILDER = "lombok.Builder";
    private static final String LOMBOK_SUPER_BUILDER = "lombok.experimental.SuperBuilder";
    private static final String LOMBOK_SINGULAR = "lombok.Singular";
    private static final String LOMBOK_NO_ARGS_CONSTRUCTOR = "lombok.NoArgsConstructor";
    private static final String LOMBOK_ALL_ARGS_CONSTRUCTOR = "lombok.AllArgsConstructor";

    private static final String LIST = List.class.getName();

    @Option(name = "anno", description = "Lombok annotation to add (repeatable). Defaults to @lombok.Data when omitted")
    List<XAnnotation<?>> annotations;

    @Option(name = "regex", description = "Regex to match fully-qualified class names")
    List<Pattern> patterns;

    @Option(name = "remove-getter", defaultValue = "true",
        description = "Remove generated getter methods (default: true)")
    Boolean removeGetter;

    @Option(name = "remove-setter", defaultValue = "true",
        description = "Remove generated setter methods (default: true)")
    Boolean removeSetter;

    @Option(name = "builder", defaultValue = "false",
        description = "Add builders (toBuilder=true), SuperBuilder on inheritance, and @Singular on List fields (default: false)")
    Boolean builder;

    private final AnnotatePlugin annotatePlugin = new AnnotatePlugin();

    public LombokPlugin() {
        registerTextParser(XAnnotation.class, (optionName, text) ->
            XAnnotationParser.INSTANCE.parse(text.toString()));
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        // fullName set: stable identity across CodeModel instances.
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
            applyAnnotations(implClass, className, resolved);

            if (Boolean.TRUE.equals(builder)) {
                annotateSingularOnListFields(implClass);
            }

            OutlineUtils.removePropertyAccessors(
                classOutline,
                Boolean.TRUE.equals(removeGetter),
                Boolean.TRUE.equals(removeSetter));
        }
        return true;
    }

    private boolean matches(String className) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        return patterns.stream().anyMatch(pattern -> pattern.matcher(className).matches());
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
     * Builds the final class-level annotation list: user or default {@code @Data}, optional builder
     * trio ({@code toBuilder = true}), and optional {@code @EqualsAndHashCode(callSuper = true)}.
     *
     * @param superBuilderNames full names that need {@code @SuperBuilder} (see
     *                          {@link #collectSuperBuilderClassNames(Outline)})
     */
    List<XAnnotation<?>> resolveAnnotations(JDefinedClass implClass, Set<String> superBuilderNames) {
        var resolved = new ArrayList<XAnnotation<?>>();
        if (annotations == null || annotations.isEmpty()) {
            resolved.add(parseAnnotation("@lombok.Data"));
        } else {
            resolved.addAll(annotations);
        }

        if (Boolean.TRUE.equals(builder)) {
            if (superBuilderNames.contains(implClass.fullName())) {
                // Inheritance participant (subclass of non-Object and/or generated base).
                addIfAbsent(resolved, LOMBOK_SUPER_BUILDER,
                    "@lombok.experimental.SuperBuilder(toBuilder = true)");
                addIfAbsent(resolved, LOMBOK_NO_ARGS_CONSTRUCTOR, "@lombok.NoArgsConstructor");
                if (!implClass.isAbstract() && !implClass.fields().isEmpty()) {
                    addIfAbsent(resolved, LOMBOK_ALL_ARGS_CONSTRUCTOR, "@lombok.AllArgsConstructor");
                }
            } else if (!implClass.isAbstract()) {
                // Standalone concrete type: official @Builder.
                addIfAbsent(resolved, LOMBOK_BUILDER, "@lombok.Builder(toBuilder = true)");
                addIfAbsent(resolved, LOMBOK_NO_ARGS_CONSTRUCTOR, "@lombok.NoArgsConstructor");
                if (!implClass.fields().isEmpty()) {
                    addIfAbsent(resolved, LOMBOK_ALL_ARGS_CONSTRUCTOR, "@lombok.AllArgsConstructor");
                }
            }
        }

        if (hasData(resolved) && hasNonObjectSuperclass(implClass)
            && !containsAnnotation(resolved, LOMBOK_EQUALS_AND_HASH_CODE)) {
            resolved.add(parseAnnotation("@lombok.EqualsAndHashCode(callSuper = true)"));
        }
        return resolved;
    }

    /**
     * Annotates {@code java.util.List} fields with {@code @Singular(ignoreNullCollections = true)}.
     * <p>
     * Naming follows Lombok: if {@link LombokSingulars#autoSingularize(String)} returns a form,
     * leave {@code value} unset so APT uses the same auto path; if it returns {@code null}, set
     * {@code value} to the field name (explicit singular required by Lombok).
     * </p>
     */
    private void annotateSingularOnListFields(JDefinedClass implClass) {
        for (var field : implClass.fields().values()) {
            if ((field.mods().getValue() & JMod.STATIC) != 0) {
                continue;
            }
            if (!isListField(field)) {
                continue;
            }
            if (hasAnnotation(field, LOMBOK_SINGULAR)) {
                continue;
            }

            var singularClass = implClass.owner().ref(LOMBOK_SINGULAR);
            var auto = LombokSingulars.autoSingularize(field.name());
            if (auto != null) {
                // Auto path matches Lombok APT; only set ignoreNullCollections.
                field.annotate(singularClass).param("ignoreNullCollections", true);
            } else {
                // Cannot auto-singularize (already singular / non-English / banned) → value = field name.
                var use = field.annotate(singularClass);
                use.param("value", field.name());
                use.param("ignoreNullCollections", true);
            }
        }
    }

    private static boolean isListField(JFieldVar field) {
        return LIST.equals(field.type().erasure().fullName());
    }

    private static boolean hasAnnotation(JFieldVar field, String fqcn) {
        for (var annotation : field.annotations()) {
            if (fqcn.equals(annotation.getAnnotationClass().fullName())) {
                return true;
            }
        }
        return false;
    }

    private void applyAnnotations(JDefinedClass implClass, String className, List<XAnnotation<?>> resolved) {
        var config = new AnnotatePlugin.AddConfig();
        config.xAnnotations = resolved;
        annotatePlugin.addAnnotation(implClass, className, List.of(config));
    }

    private static void addIfAbsent(List<XAnnotation<?>> resolved, String fqcn, String source) {
        if (!containsAnnotation(resolved, fqcn)) {
            resolved.add(parseAnnotation(source));
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

    private static XAnnotation<?> parseAnnotation(String source) {
        try {
            return XAnnotationParser.INSTANCE.parse(source);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse annotation: " + source, e);
        }
    }
}
