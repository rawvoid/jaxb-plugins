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
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.option.OptionPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import io.github.rawvoid.jaxb.plugin.xjc.AnnotationUtils;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Adds common Jackson annotations to generated JAXB classes.
 *
 * <p>Zero-config usage ({@code -Xjackson}) annotates every generated class with
 * {@code @JsonInclude(NON_NULL)} and {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * when those annotations are not already present.</p>
 *
 * <p>Jackson types are referenced only by FQCN so this plugin can be loaded by XJC SPI
 * without {@code jackson-annotations} on the classpath. That dependency is required only
 * when {@code -Xjackson} is enabled (and when compiling generated sources that use the
 * annotations).</p>
 *
 * <p><b>Intentional limitations (MVP):</b></p>
 * <ul>
 *   <li>Class-level annotations only; no field {@code @JsonProperty} from XML names.</li>
 *   <li>No {@code @JsonFormat}, {@code @JsonPropertyOrder}, {@code @JsonRootName}, or type info.</li>
 *   <li>Does not configure {@code ObjectMapper}; consumers must provide
 *       {@code jackson-annotations} at generation and compile time when this plugin is used.</li>
 *   <li>Built-in {@code @JsonInclude} / {@code @JsonIgnoreProperties} are skipped when already
 *       present (does not replace). User {@code -anno} values use normal non-repeatable replace.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xjackson", description = "Add Jackson annotations to generated classes (default: JsonInclude NON_NULL + ignoreUnknown)")
public class JacksonPlugin extends OptionPlugin {

    private static final String INCLUDE_NONE = "none";
    private static final String JSON_INCLUDE = "com.fasterxml.jackson.annotation.JsonInclude";
    private static final String JSON_INCLUDE_INCLUDE = "com.fasterxml.jackson.annotation.JsonInclude.Include";
    private static final String JSON_IGNORE_PROPERTIES = "com.fasterxml.jackson.annotation.JsonIgnoreProperties";

    /**
     * Known {@code JsonInclude.Include} names (Jackson 2.x). Kept as strings so this class
     * does not hard-link jackson-annotations at SPI load time.
     */
    private static final Set<String> KNOWN_INCLUDES = Set.of(
        "ALWAYS",
        "NON_NULL",
        "NON_ABSENT",
        "NON_EMPTY",
        "NON_DEFAULT",
        "CUSTOM",
        "USE_DEFAULTS"
    );

    @Option(name = "include", defaultValue = "NON_NULL",
        description = "JsonInclude.Include value, or 'none' to skip @JsonInclude (default: NON_NULL)")
    String include;

    @Option(name = "ignore-unknown", defaultValue = "true",
        description = "Add @JsonIgnoreProperties(ignoreUnknown = true) when true (default: true)")
    Boolean ignoreUnknown;

    @Option(name = "class-name", description = "Regex to match fully-qualified class names")
    List<Pattern> classNames;

    @Option(name = "anno", description = "Extra Jackson (or other) annotation to add on matching classes (repeatable)")
    List<XAnnotation<?>> annotations;

    public JacksonPlugin() {
        registerTextParser(XAnnotation.class, AnnotationUtils.xAnnotationTextParser());
    }

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        requireJacksonAnnotations();
        if (include == null || include.isBlank()) {
            include = "NON_NULL";
        }
        if (!INCLUDE_NONE.equalsIgnoreCase(include.trim()) && !isKnownInclude(include)) {
            throw new BadCommandLineException(
                "Invalid -include value '%s'; expected a JsonInclude.Include name or 'none'".formatted(include));
        }
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var includeName = resolveIncludeName();
        var addIgnoreUnknown = !Boolean.FALSE.equals(ignoreUnknown);

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            if (!matches(implClass.fullName())) {
                continue;
            }
            applyBuiltIns(implClass, includeName, addIgnoreUnknown);
            applyExtraAnnotations(implClass);
        }
        return true;
    }

    private void applyBuiltIns(JDefinedClass implClass, String includeName, boolean addIgnoreUnknown) {
        var cm = implClass.owner();
        if (includeName != null && !AnnotationUtils.hasAnnotation(implClass, JSON_INCLUDE)) {
            implClass.annotate(cm.ref(JSON_INCLUDE))
                .param("value", cm.ref(JSON_INCLUDE_INCLUDE).staticRef(includeName));
        }
        if (addIgnoreUnknown && !AnnotationUtils.hasAnnotation(implClass, JSON_IGNORE_PROPERTIES)) {
            implClass.annotate(cm.ref(JSON_IGNORE_PROPERTIES)).param("ignoreUnknown", true);
        }
    }

    private void applyExtraAnnotations(JDefinedClass implClass) {
        if (annotations == null || annotations.isEmpty()) {
            return;
        }
        annotations.forEach(annotation -> AnnotationUtils.applyXAnnotation(implClass, annotation));
    }

    private boolean matches(String className) {
        if (classNames == null || classNames.isEmpty()) {
            return true;
        }
        return classNames.stream().anyMatch(pattern -> pattern.matcher(className).matches());
    }

    /**
     * @return resolved include enum name, or {@code null} when {@code -include=none}
     */
    private String resolveIncludeName() {
        if (include == null || INCLUDE_NONE.equalsIgnoreCase(include.trim())) {
            return null;
        }
        return include.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isKnownInclude(String raw) {
        return KNOWN_INCLUDES.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static void requireJacksonAnnotations() throws BadCommandLineException {
        if (isPresent(JSON_INCLUDE) && isPresent(JSON_IGNORE_PROPERTIES)) {
            return;
        }
        throw new BadCommandLineException(
            "Jackson annotations not found on the XJC classpath. "
                + "Add com.fasterxml.jackson.core:jackson-annotations when using -Xjackson.");
    }

    private static boolean isPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, JacksonPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }
}
