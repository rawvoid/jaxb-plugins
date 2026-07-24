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
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * JAXB plugin that automatically annotates all generated packages and classes
 * with {@code @jakarta.annotation.Generated}.
 * <p>
 * Configuration options:
 * </p>
 * <ul>
 *   <li>{@code -value}: The generator name. Defaults to {@code "JAXB RI v[BuildID]"}.</li>
 *   <li>{@code -comments}: Additional comments. Defaults to none.</li>
 *   <li>{@code -date}: Whether to include the generation date. Defaults to {@code false}.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xgenerated-anno", description = "Add jakarta.annotation.Generated annotations to generated classes and packages")
public class GeneratedAnnoPlugin extends AbstractPlugin {

    private static final String GENERATED_ANNOTATION_FQCN = "jakarta.annotation.Generated";

    @Option(name = "value", description = "Value attribute of @Generated annotation (default: JAXB RI v[BuildID])")
    private String value;

    @Option(name = "comments", description = "Comments attribute of @Generated annotation")
    private String comments;

    @Option(name = "date", defaultValue = "false", description = "Include generation date in @Generated annotation (default: false)")
    private Boolean date;

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
        var codeModel = outline.getCodeModel();
        var generatedClass = codeModel.ref(GENERATED_ANNOTATION_FQCN);

        var valueToUse = (value != null) ? value : "JAXB RI v" + Options.getBuildID();
        var commentsToUse = comments;
        String dateToUse = null;

        if (Boolean.TRUE.equals(date)) {
            dateToUse = LocalDate.now().toString();
        }

        for (var packageContext : outline.getAllPackageContexts()) {
            var jPackage = packageContext._package();
            // Annotate package-info.java
            addGeneratedAnnotation(jPackage, generatedClass, valueToUse, commentsToUse, dateToUse);

            // Annotate all classes in this package (including nested classes) iteratively using a queue
            Queue<JDefinedClass> queue = new ArrayDeque<>();
            var classesIt = jPackage.classes();
            while (classesIt.hasNext()) {
                queue.add(classesIt.next());
            }

            while (!queue.isEmpty()) {
                var clazz = queue.poll();
                addGeneratedAnnotation(clazz, generatedClass, valueToUse, commentsToUse, dateToUse);

                var nestedIt = clazz.classes();
                while (nestedIt.hasNext()) {
                    queue.add(nestedIt.next());
                }
            }
        }

        return true;
    }

    private void addGeneratedAnnotation(JAnnotatable target, JClass annotationClass, String value, String comments, String date) {
        var alreadyAnnotated = target.annotations().stream()
            .anyMatch(a -> a.getAnnotationClass().fullName().equals(GENERATED_ANNOTATION_FQCN));
        if (!alreadyAnnotated) {
            var annotationUse = target.annotate(annotationClass);
            annotationUse.param("value", value);
            if (date != null && !date.isEmpty()) {
                annotationUse.param("date", date);
            }
            if (comments != null) {
                annotationUse.param("comments", comments);
            }
        }
    }
}

