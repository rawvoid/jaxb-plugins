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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.sun.codemodel.JDefinedClass;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.utils.AnnotationUtils;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Adds common Jackson annotations to generated JAXB classes.
 *
 * <p>Zero-config usage ({@code -Xjackson}) annotates every generated class with
 * {@code @JsonInclude(NON_NULL)} and {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * when those annotations are not already present.</p>
 *
 * <p><b>Intentional limitations (MVP):</b></p>
 * <ul>
 *   <li>Class-level annotations only; no field {@code @JsonProperty} from XML names.</li>
 *   <li>No {@code @JsonFormat}, {@code @JsonPropertyOrder}, {@code @JsonRootName}, or type info.</li>
 *   <li>Does not configure {@code ObjectMapper}; consumers must provide
 *       {@code jackson-annotations} at generation and compile time.</li>
 *   <li>Built-in {@code @JsonInclude} / {@code @JsonIgnoreProperties} are skipped when already
 *       present (does not replace). User {@code -anno} values use normal non-repeatable replace.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xjackson", description = "Add Jackson annotations to generated classes (default: JsonInclude NON_NULL + ignoreUnknown)")
public class JacksonPlugin extends AbstractPlugin {

    private static final String INCLUDE_NONE = "none";

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
        if (include == null || include.isBlank()) {
            include = "NON_NULL";
        }
        if (!INCLUDE_NONE.equalsIgnoreCase(include.trim())) {
            try {
                parseInclude(include);
            } catch (IllegalArgumentException ex) {
                throw new BadCommandLineException(
                    "Invalid -include value '%s'; expected a JsonInclude.Include name or 'none'".formatted(include),
                    ex);
            }
        }
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var includeValue = resolveInclude();
        var addIgnoreUnknown = !Boolean.FALSE.equals(ignoreUnknown);

        for (var classOutline : outline.getClasses()) {
            var implClass = classOutline.implClass;
            if (!matches(implClass.fullName())) {
                continue;
            }
            applyBuiltIns(implClass, includeValue, addIgnoreUnknown);
            applyExtraAnnotations(implClass);
        }
        return true;
    }

    private void applyBuiltIns(JDefinedClass implClass, JsonInclude.Include includeValue, boolean addIgnoreUnknown) {
        if (includeValue != null && !AnnotationUtils.hasAnnotation(implClass, JsonInclude.class)) {
            implClass.annotate(JsonInclude.class).param("value", includeValue);
        }
        if (addIgnoreUnknown && !AnnotationUtils.hasAnnotation(implClass, JsonIgnoreProperties.class)) {
            implClass.annotate(JsonIgnoreProperties.class).param("ignoreUnknown", true);
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
     * @return resolved include, or {@code null} when {@code -include=none}
     */
    private JsonInclude.Include resolveInclude() {
        if (include == null || INCLUDE_NONE.equalsIgnoreCase(include.trim())) {
            return null;
        }
        return parseInclude(include);
    }

    private static JsonInclude.Include parseInclude(String raw) {
        return JsonInclude.Include.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
