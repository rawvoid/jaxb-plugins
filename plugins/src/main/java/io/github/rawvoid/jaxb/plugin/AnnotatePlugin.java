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
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.option.AbstractPlugin;
import io.github.rawvoid.jaxb.plugin.option.Option;
import io.github.rawvoid.jaxb.plugin.xjc.AnnotationUtils;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AnnotatePlugin is a JAXB plugin that allows you to add custom annotations to generated Java artifacts.
 *
 * @author Rawvoid
 */
@Option(name = "Xannotate", description = "Add custom annotations to generated Java artifacts")
public class AnnotatePlugin extends AbstractPlugin {

    @Option(name = "add-to-class", description = "Add annotations to generated classes")
    List<AddConfig> addToClassConfigs;

    @Option(name = "add-to-field", description = "Add annotations to generated fields")
    List<AddConfig> addToFieldConfigs;

    @Option(name = "add-to-method", description = "Add annotations to generated methods")
    List<AddConfig> addToMethodConfigs;

    @Option(name = "add-to-package", description = "Add annotations to generated packages")
    List<AddConfig> addToPackageConfigs;

    @Option(name = "remove-from-class", description = "Remove annotations from generated classes")
    List<RemoveConfig> removeFromClassConfigs;

    @Option(name = "remove-from-field", description = "Remove annotations from generated fields")
    List<RemoveConfig> removeFromFieldConfigs;

    @Option(name = "remove-from-method", description = "Remove annotations from generated methods")
    List<RemoveConfig> removeFromMethodConfigs;

    @Option(name = "remove-from-package", description = "Remove annotations from generated packages")
    List<RemoveConfig> removeFromPackageConfigs;

    public AnnotatePlugin() {
        registerTextParser(XAnnotation.class, AnnotationUtils.xAnnotationTextParser());
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        outline.getClasses().forEach(classOutline -> {
            var jDefinedClass = classOutline.implClass;
            var className = jDefinedClass.fullName();

            if (addToClassConfigs != null && !addToClassConfigs.isEmpty()) {
                addAnnotation(jDefinedClass, className, addToClassConfigs);
            }

            if (addToFieldConfigs != null && !addToFieldConfigs.isEmpty()) {
                jDefinedClass.fields().values().forEach(field ->
                    addAnnotation(field, className + "." + field.name(), addToFieldConfigs));
            }

            if (addToMethodConfigs != null && !addToMethodConfigs.isEmpty()) {
                jDefinedClass.methods().forEach(method ->
                    addAnnotation(method, className + "." + method.name(), addToMethodConfigs));
            }

            if (removeFromClassConfigs != null && !removeFromClassConfigs.isEmpty()) {
                removeAnnotation(jDefinedClass, className, removeFromClassConfigs);
            }

            if (removeFromFieldConfigs != null && !removeFromFieldConfigs.isEmpty()) {
                jDefinedClass.fields().values().forEach(field ->
                    removeAnnotation(field, className + "." + field.name(), removeFromFieldConfigs));
            }

            if (removeFromMethodConfigs != null && !removeFromMethodConfigs.isEmpty()) {
                jDefinedClass.methods().forEach(method ->
                    removeAnnotation(method, className + "." + method.name(), removeFromMethodConfigs));
            }
        });

        if (addToPackageConfigs != null && !addToPackageConfigs.isEmpty()) {
            outline.getAllPackageContexts().forEach(packageContext -> {
                var jPackage = packageContext._package();
                var packageName = jPackage.name();
                addAnnotation(jPackage, packageName, addToPackageConfigs);
            });
        }

        if (removeFromPackageConfigs != null && !removeFromPackageConfigs.isEmpty()) {
            outline.getAllPackageContexts().forEach(packageContext -> {
                var jPackage = packageContext._package();
                var packageName = jPackage.name();
                removeAnnotation(jPackage, packageName, removeFromPackageConfigs);
            });
        }

        return true;
    }

    /**
     * Adds annotations to the specified target if the target name matches any of the targets in the configuration.
     *
     * @param target     the target to add annotations to
     * @param targetName the name of the target
     * @param configs    the list of configuration objects
     */
    public void addAnnotation(JAnnotatable target, String targetName, List<AddConfig> configs) {
        var matchedConfigs = configs.stream()
            .filter(config -> config.targets == null || config.targets.isEmpty() || config.targets.stream()
                .anyMatch(targetPattern -> targetPattern.matcher(targetName).matches()))
            .toList();
        matchedConfigs.stream()
            .flatMap(config -> config.xAnnotations.stream())
            .forEach(xAnnotation -> AnnotationUtils.applyXAnnotation(target, xAnnotation));
    }

    /**
     * Removes annotations from the specified target if the target name matches any of the targets in the configuration.
     *
     * @param target     the target to remove annotations from
     * @param targetName the name of the target
     * @param configs    the list of configuration objects
     */
    public void removeAnnotation(JAnnotatable target, String targetName, List<RemoveConfig> configs) {
        var matchedConfigs = configs.stream()
            .filter(config -> config.targets == null || config.targets.isEmpty() || config.targets.stream()
                .anyMatch(targetPattern -> targetPattern.matcher(targetName).matches()))
            .toList();

        matchedConfigs.forEach(config -> config.annotations.forEach(annoClass ->
            AnnotationUtils.removeAnnotations(target, annoClass)));
    }

    /**
     * Configuration class for adding annotations.
     */
    public static class AddConfig {

        @Option(name = "anno", required = true, placeholder = "annotation", description = "Annotation to add")
        List<XAnnotation<?>> xAnnotations;

        @Option(name = "target", description = "Regex to match the fully-qualified target name")
        List<Pattern> targets;

    }

    /**
     * Configuration class for removing annotations.
     */
    public static class RemoveConfig {

        @Option(name = "anno", required = true, placeholder = "annotation", description = "Annotation class name to remove")
        List<Class<? extends Annotation>> annotations;

        @Option(name = "target", description = "Regex to match the fully-qualified target name")
        List<Pattern> targets;

    }
}
