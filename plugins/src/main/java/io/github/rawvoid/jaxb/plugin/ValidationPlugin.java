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

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.validation.FieldAnnotator;
import io.github.rawvoid.jaxb.plugin.validation.ValidationApi;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Adds Bean Validation (JSR-380) constraint annotations on generated JAXB fields from XSD
 * multiplicity and simple-type facets.
 *
 * <p><b>Supported mappings:</b></p>
 * <ul>
 *   <li>Element {@code minOccurs}/{@code maxOccurs} and {@code nillable} → {@code @NotNull},
 *       collection {@code @Size}</li>
 *   <li>Attribute {@code use="required"} and attribute type facets</li>
 *   <li>Simple content ({@code @XmlValue}) facets</li>
 *   <li>{@code length}/{@code minLength}/{@code maxLength} → {@code @Size} (string fields only)</li>
 *   <li>{@code pattern} → {@code @Pattern}</li>
 *   <li>Numeric bounds → {@code @Min}/{@code @Max} or {@code @DecimalMin}/{@code @DecimalMax}</li>
 *   <li>{@code totalDigits}/{@code fractionDigits} → {@code @Digits} (user-declared only)</li>
 *   <li>Complex and {@code List} of complex → {@code @Valid}</li>
 * </ul>
 *
 * <p><b>Intentional limitations:</b></p>
 * <ul>
 *   <li>No mapping for {@code enumeration}, {@code whiteSpace}, or {@code fixed}.</li>
 *   <li>Collection <em>item</em> length/pattern is not expressed (collection {@code @Size} is
 *       multiplicity only).</li>
 *   <li>Annotations are applied to fields only (JAXB default {@code FIELD} access).</li>
 *   <li>Facets are taken from the user restriction chain only; built-in XML Schema type facets
 *       (e.g. {@code xs:integer} {@code fractionDigits}) are ignored.</li>
 * </ul>
 *
 * @author Rawvoid
 */
@Option(name = "Xvalidation", description = "Add Bean Validation annotations (JSR-380) based on XSD schema constraints")
public class ValidationPlugin extends AbstractPlugin {

    @Option(name = "api", defaultValue = "jakarta",
        description = "Validation API package mode: 'jakarta' (default) or 'javax'")
    String api;

    @Option(name = "class-name", description = "Regex to match fully-qualified class names (repeatable)")
    List<Pattern> classNames;

    @Option(name = "field-name", description = "Regex to match field names (repeatable)")
    List<Pattern> fieldNames;

    @Option(name = "disable-valid", defaultValue = "false",
        description = "Disable automatic @Valid annotation on complex or collection properties")
    Boolean disableValid;

    private ValidationApi validationApi;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        try {
            validationApi = ValidationApi.parse(api);
        } catch (IllegalArgumentException ex) {
            throw new BadCommandLineException(ex.getMessage(), ex);
        }
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        var apiMode = validationApi != null ? validationApi : ValidationApi.parse(api);
        var annotator = new FieldAnnotator(outline, apiMode, Boolean.TRUE.equals(disableValid));

        for (var classOutline : outline.getClasses()) {
            if (!matchesClassName(classOutline.implClass.fullName())) {
                continue;
            }
            for (var fieldOutline : classOutline.getDeclaredFields()) {
                var prop = fieldOutline.getPropertyInfo();
                var fieldName = prop.getName(false);
                if (!matchesFieldName(fieldName)) {
                    continue;
                }
                var fieldVar = classOutline.implClass.fields().get(fieldName);
                if (fieldVar == null) {
                    continue;
                }
                annotator.annotate(prop, fieldVar);
            }
        }
        return true;
    }

    private boolean matchesClassName(String className) {
        if (classNames == null || classNames.isEmpty()) {
            return true;
        }
        return classNames.stream().anyMatch(p -> p.matcher(className).matches());
    }

    private boolean matchesFieldName(String fieldName) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return true;
        }
        return fieldNames.stream().anyMatch(p -> p.matcher(fieldName).matches());
    }
}
