/*
 * Copyright 2025 Rawvoid(https://github.com/rawvoid)
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
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.lang.annotation.Repeatable;
import java.util.Arrays;
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
    protected List<Config> classConfigs;

    @Option(name = "add-to-field", description = "Add annotations to generated fields")
    protected List<Config> fieldConfigs;

    @Option(name = "add-to-method", description = "Add annotations to generated methods")
    protected List<Config> methodConfigs;

    public AnnotatePlugin() {
        registerTextParser(XAnnotation.class, (optionName, text) ->
            XAnnotationParser.INSTANCE.parse(text.toString()));
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        outline.getClasses().forEach(classOutline -> {
            var jDefinedClass = classOutline.implClass;
            var className = jDefinedClass.fullName();

            if (classConfigs != null && !classConfigs.isEmpty()) {
                addAnnotation(jDefinedClass, className, classConfigs);
            }

            if (fieldConfigs != null && !fieldConfigs.isEmpty()) {
                jDefinedClass.fields().values().forEach(field ->
                    addAnnotation(field, className + "." + field.name(), fieldConfigs));
            }

            if (methodConfigs != null && !methodConfigs.isEmpty()) {
                jDefinedClass.methods().forEach(method ->
                    addAnnotation(method, className + "." + method.name(), methodConfigs));
            }
        });

        return true;
    }

    public void addAnnotation(JAnnotatable target, String targetName, List<Config> configs) {
        var matchedConfigs = configs.stream()
            .filter(config -> config.regex == null || config.regex.matcher(targetName).matches())
            .toList();
        matchedConfigs.forEach(config -> {
            var xAnnotation = config.annotation;
            var annotationClass = xAnnotation.getAnnotationClass();
            var existingSameAnnotations = target.annotations().stream()
                .filter(a -> a.getAnnotationClass().fullName().equals(annotationClass.getName()))
                .toList();

            if (!existingSameAnnotations.isEmpty()) {
                if (annotationClass.getAnnotation(Repeatable.class) == null) {
                    existingSameAnnotations.forEach(target::removeAnnotation);
                }
            }
            var annotationUse = target.annotate(annotationClass);
            xAnnotation.getFieldsList().forEach(field ->
                fillAnnotationParam(annotationUse, field.getName(), field.getValue()));
        });
    }

    protected void fillAnnotationParam(JAnnotationUse annotationUse, String paramName, Object paramValue) {
        switch (paramValue) {
            case String strValue -> annotationUse.param(paramName, strValue);
            case Integer intValue -> annotationUse.param(paramName, intValue);
            case Boolean boolValue -> annotationUse.param(paramName, boolValue);
            case Character charValue -> annotationUse.param(paramName, charValue);
            case Byte byteValue -> annotationUse.param(paramName, byteValue);
            case Short shortValue -> annotationUse.param(paramName, shortValue);
            case Long longValue -> annotationUse.param(paramName, longValue);
            case Float floatValue -> annotationUse.param(paramName, floatValue);
            case Double doubleValue -> annotationUse.param(paramName, doubleValue);
            case Class<?> clazz -> annotationUse.param(paramName, clazz);
            case Enum<?> enumValue -> annotationUse.param(paramName, enumValue);
            case JEnumConstant enumConstant -> annotationUse.param(paramName, enumConstant);
            case JExpression expression -> annotationUse.param(paramName, expression);
            case JType type -> annotationUse.param(paramName, type);
            default -> {
                Iterable<?> iterable;
                if (paramValue instanceof Iterable<?>) {
                    iterable = (Iterable<?>) paramValue;
                } else if (paramValue.getClass().isArray()) {
                    iterable = Arrays.asList((Object[]) paramValue);
                } else {
                    throw new IllegalStateException("Unexpected value: " + paramValue);
                }
                var array = annotationUse.paramArray(paramName);
                iterable.forEach(item -> fillArrayParam(array, item));
            }
        }
    }

    public void fillArrayParam(JAnnotationArrayMember array, Object value) {
        switch (value) {
            case String strValue -> array.param(strValue);
            case Integer intValue -> array.param(intValue);
            case Boolean boolValue -> array.param(boolValue);
            case Character charValue -> array.param(charValue);
            case Byte byteValue -> array.param(byteValue);
            case Short shortValue -> array.param(shortValue);
            case Long longValue -> array.param(longValue);
            case Float floatValue -> array.param(floatValue);
            case Double doubleValue -> array.param(doubleValue);
            case Class<?> clazz -> array.param(clazz);
            case Enum<?> enumValue -> array.param(enumValue);
            case JEnumConstant enumConstant -> array.param(enumConstant);
            case JExpression expression -> array.param(expression);
            case JType type -> array.param(type);
            default -> throw new IllegalStateException("Unexpected value: " + value);
        }
    }

    public static class Config {

        @Option(name = "anno", required = true, placeholder = "annotation", description = "The annotation to be added")
        XAnnotation<?> annotation;

        @Option(name = "regex", description = "The regex pattern to match the target")
        Pattern regex;

    }
}
