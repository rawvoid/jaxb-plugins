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

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import io.github.rawvoid.jaxb.plugin.option.AbstractPlugin;
import io.github.rawvoid.jaxb.plugin.option.Compact;
import io.github.rawvoid.jaxb.plugin.option.Option;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * XJC plugin for customizing naming conversion logic during JAXB code generation.
 * <p>
 * This plugin allows users to precisely control the names of generated classes, variables,
 * properties, methods, and packages by configuring literal input matches or regular expressions
 * on the converted name. It implements custom logic by replacing XJC's default {@link NameConverter}.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xconvert-name", description = "Customize naming conversion rules for generated code")
public class ConvertNamePlugin extends AbstractPlugin {

    /**
     * Custom {@link NameConverter} implementation class.
     * If provided, the plugin will use this class directly, ignoring other conversion configurations.
     */
    @Option(name = "name-converter", description = "Specify the fully qualified name of a custom NameConverter implementation")
    Class<? extends NameConverter> nameConverterClass;

    /**
     * List of class name conversion configurations.
     */
    @Option(name = "class-name", description = "Configure class name conversion rules")
    List<NameMappingConfig> classNameMappings;

    /**
     * List of variable name conversion configurations.
     */
    @Option(name = "variable-name", description = "Configure variable name conversion rules")
    List<NameMappingConfig> variableNameMappings;

    /**
     * List of interface name conversion configurations.
     */
    @Option(name = "interface-name", description = "Configure interface name conversion rules")
    List<NameMappingConfig> interfaceNameMappings;

    /**
     * List of property name conversion configurations.
     */
    @Option(name = "property-name", description = "Configure property name (including Getter/Setter method names) conversion rules")
    List<NameMappingConfig> propertyNameMappings;

    /**
     * List of constant name conversion configurations.
     */
    @Option(name = "constant-name", description = "Configure constant name conversion rules")
    List<NameMappingConfig> constantNameMappings;

    /**
     * List of package name conversion configurations.
     */
    @Option(name = "package-name", description = "Configure package name conversion rules")
    List<NameMappingConfig> packageNameMappings;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        validateMappings("class-name", classNameMappings);
        validateMappings("variable-name", variableNameMappings);
        validateMappings("interface-name", interfaceNameMappings);
        validateMappings("property-name", propertyNameMappings);
        validateMappings("constant-name", constantNameMappings);
        validateMappings("package-name", packageNameMappings);

        NameConverter nameConverter = new NameConverter.Standard() {
            @Override
            public String toClassName(String s) {
                return convertName(s, super.toClassName(s), classNameMappings);
            }

            @Override
            public String toVariableName(String s) {
                return convertName(s, super.toVariableName(s), variableNameMappings);
            }

            @Override
            public String toInterfaceName(String token) {
                return convertName(token, super.toInterfaceName(token), interfaceNameMappings);
            }

            @Override
            public String toPropertyName(String s) {
                return convertName(s, super.toPropertyName(s), propertyNameMappings);
            }

            @Override
            public String toConstantName(String token) {
                return convertName(token, super.toConstantName(token), constantNameMappings);
            }

            @Override
            public String toPackageName(String nsUri) {
                return convertName(nsUri, super.toPackageName(nsUri), packageNameMappings);
            }

            public String convertName(String token, String internalName, List<NameMappingConfig> mappings) {
                if (mappings == null || mappings.isEmpty()) {
                    return internalName;
                }

                for (var config : mappings) {
                    if (config.input != null) {
                        if (Objects.equals(config.input, token)) {
                            return config.to;
                        }
                    } else if (config.name != null && config.name.matcher(internalName).matches()) {
                        return internalName.replaceAll(config.name.pattern(), config.to);
                    }
                }

                return internalName;
            }
        };

        if (nameConverterClass != null) {
            nameConverter = nameConverterClass.getDeclaredConstructor().newInstance();
        }

        opt.setNameConverter(nameConverter, this);
    }

    private static void validateMappings(String optionName, List<NameMappingConfig> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        for (var mapping : mappings) {
            var hasInput = mapping.input != null;
            var hasName = mapping.name != null;
            if (hasInput == hasName) {
                throw new IllegalArgumentException(
                    "-%s mapping requires exactly one of -input or -name (not both, not neither)"
                        .formatted(optionName)
                );
            }
        }
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        return true;
    }

    /**
     * Naming mapping rule configuration.
     * <p>
     * Each rule must set exactly one of {@link #input} or {@link #name}:
     * </p>
     * <ul>
     *   <li>{@code -input} — exact match on the original NameConverter input; replaces with {@link #to} as a whole</li>
     *   <li>{@code -name} — regex match on the name after standard conversion; replaces via {@code replaceAll}</li>
     * </ul>
     * <p>
     * Compact CLI (see {@link Compact}): {@code -class-name=Person->CustomPerson},
     * {@code -class-name=/(.*)_ID/->$1Id}, {@code -package-name=nsUri->java.package}.
     * </p>
     */
    // More specific regex template first so "/…/->…" is not parsed as input="/…/".
    @Compact(formats = {"/{name}/->{to}", "{input}->{to}"})
    public static class NameMappingConfig {

        /**
         * Exact match against the original NameConverter input
         * (XML local name, enum value, or namespace URI).
         * When matched, the result is {@link #to} as a whole (no regex replace).
         */
        @Option(name = "input", description = "Exact original NameConverter input to match (XML name or namespace URI)")
        String input;

        /**
         * Regular expression matching the name after standard conversion.
         * When matched, replaced with {@link #to} via {@link String#replaceAll}.
         */
        @Option(name = "name", description = "Regex matching the name after standard conversion")
        Pattern name;

        /**
         * Target name after conversion. May contain {@code $n} groups when {@link #name} is used.
         */
        @Option(name = "to", required = true, description = "Target name (may contain $n groups when -name is used)")
        String to;

    }

}
