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
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.utils.OutlineUtils;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.List;
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
 *   <li>When {@code -builder} is enabled, standard {@code @Builder} is used (not {@code @SuperBuilder}).
 *       Abstract classes are skipped. {@code @AllArgsConstructor} is omitted for types with no fields
 *       (it would collide with {@code @NoArgsConstructor}). Hierarchies that need {@code @SuperBuilder}
 *       should pass it via {@code -anno}.</li>
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
    private static final String LOMBOK_NO_ARGS_CONSTRUCTOR = "lombok.NoArgsConstructor";
    private static final String LOMBOK_ALL_ARGS_CONSTRUCTOR = "lombok.AllArgsConstructor";

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
        description = "Add @Builder with @NoArgsConstructor/@AllArgsConstructor for JAXB (default: false)")
    Boolean builder;

    private final AnnotatePlugin annotatePlugin = new AnnotatePlugin();

    public LombokPlugin() {
        registerTextParser(XAnnotation.class, (optionName, text) ->
            XAnnotationParser.INSTANCE.parse(text.toString()));
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            var className = implClass.fullName();
            if (!matches(className)) {
                continue;
            }

            var resolved = resolveAnnotations(implClass);
            applyAnnotations(implClass, className, resolved);

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
     * Builds the final annotation list: user or default {@code @Data}, optional builder trio,
     * and optional {@code @EqualsAndHashCode(callSuper = true)} for subclasses.
     */
    List<XAnnotation<?>> resolveAnnotations(JDefinedClass implClass) {
        var resolved = new ArrayList<XAnnotation<?>>();
        if (annotations == null || annotations.isEmpty()) {
            resolved.add(parseAnnotation("@lombok.Data"));
        } else {
            resolved.addAll(annotations);
        }

        if (Boolean.TRUE.equals(builder) && !implClass.isAbstract()) {
            // Abstract classes cannot use @Builder. Empty types would get identical
            // no-arg constructors from both @NoArgsConstructor and @AllArgsConstructor.
            addIfAbsent(resolved, LOMBOK_BUILDER, "@lombok.Builder");
            addIfAbsent(resolved, LOMBOK_NO_ARGS_CONSTRUCTOR, "@lombok.NoArgsConstructor");
            if (!implClass.fields().isEmpty()) {
                addIfAbsent(resolved, LOMBOK_ALL_ARGS_CONSTRUCTOR, "@lombok.AllArgsConstructor");
            }
        }

        if (hasData(resolved) && hasNonObjectSuperclass(implClass)
            && !containsAnnotation(resolved, LOMBOK_EQUALS_AND_HASH_CODE)) {
            resolved.add(parseAnnotation("@lombok.EqualsAndHashCode(callSuper = true)"));
        }
        return resolved;
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
