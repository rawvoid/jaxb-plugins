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

import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JPackage;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

/**
 * JAXB plugin that automatically annotates all generated packages and classes
 * with {@code @jakarta.annotation.Generated}.
 * <p>
 * Configuration options:
 * <ul>
 *   <li>{@code -value}: The generator name. Defaults to the plugin option name ({@code Xgenerated-anno}).</li>
 *   <li>{@code -comments}: Additional comments, such as repository URL. Defaults to the project URL.</li>
 *   <li>{@code -date}: Whether to include the generation date. Defaults to {@code false}.</li>
 * </ul>
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xgenerated-anno", description = "Automatically add jakarta.annotation.Generated annotations to generated classes and packages")
public class GeneratedAnnoPlugin extends AbstractPlugin {

    private static final String GENERATED_ANNOTATION_FQCN = "jakarta.annotation.Generated";
    private static final String DEFAULT_PROJECT_URL = "https://github.com/rawvoid/jaxb-plugins";

    @Option(name = "value", description = "The value attribute of @Generated annotation. Defaults to the plugin option name")
    private String value;

    @Option(name = "comments", description = "The comments attribute of @Generated annotation. Defaults to the project URL")
    private String comments;

    @Option(name = "date", defaultValue = "false", description = "Whether to include a date in @Generated annotation")
    private Boolean date;

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
        var codeModel = outline.getCodeModel();
        var generatedClass = codeModel.ref(GENERATED_ANNOTATION_FQCN);

        var valueToUse = (value != null && !value.isEmpty()) ? value : getOptionName();
        var commentsToUse = (comments != null && !comments.isEmpty()) ? comments : DEFAULT_PROJECT_URL;
        String dateToUse = null;

        if (Boolean.TRUE.equals(date)) {
            dateToUse = java.time.LocalDate.now().toString();
        }

        for (var packageContext : outline.getAllPackageContexts()) {
            var jPackage = packageContext._package();
            // Annotate package-info.java
            addGeneratedAnnotation(jPackage, generatedClass, valueToUse, commentsToUse, dateToUse);

            // Annotate all top-level classes and recursively their nested classes
            var classesIt = jPackage.classes();
            while (classesIt.hasNext()) {
                var clazz = classesIt.next();
                annotateClass(clazz, generatedClass, valueToUse, commentsToUse, dateToUse);
            }
        }

        return true;
    }

    private void annotateClass(JDefinedClass clazz, JClass annotationClass, String value, String comments, String date) {
        addGeneratedAnnotation(clazz, annotationClass, value, comments, date);
        var nestedIt = clazz.classes();
        while (nestedIt.hasNext()) {
            annotateClass(nestedIt.next(), annotationClass, value, comments, date);
        }
    }

    private void addGeneratedAnnotation(JAnnotatable target, JClass annotationClass, String value, String comments, String date) {
        var alreadyAnnotated = target.annotations().stream()
            .anyMatch(a -> a.getAnnotationClass().fullName().equals(GENERATED_ANNOTATION_FQCN));
        if (!alreadyAnnotated) {
            var annotationUse = target.annotate(annotationClass);
            annotationUse.param("value", value);
            if (comments != null && !comments.isEmpty()) {
                annotationUse.param("comments", comments);
            }
            if (date != null && !date.isEmpty()) {
                annotationUse.param("date", date);
            }
        }
    }
}
