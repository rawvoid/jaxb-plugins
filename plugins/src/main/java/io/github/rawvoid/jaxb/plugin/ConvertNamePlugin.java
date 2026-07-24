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
 * properties, methods, and packages by configuring specific tokens or regular expressions.
 * It implements custom logic by replacing XJC's default {@link NameConverter}.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xconvert-name", description = "Enable name conversion plugin")
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
    List<NameMapping> classNameMappings;

    /**
     * List of variable name conversion configurations.
     */
    @Option(name = "variable-name", description = "Configure variable name conversion rules")
    List<NameMapping> variableNameMappings;

    /**
     * List of interface name conversion configurations.
     */
    @Option(name = "interface-name", description = "Configure interface name conversion rules")
    List<NameMapping> interfaceNameMappings;

    /**
     * List of property name conversion configurations.
     */
    @Option(name = "property-name", description = "Configure property name (including Getter/Setter method names) conversion rules")
    List<NameMapping> propertyNameMappings;

    /**
     * List of constant name conversion configurations.
     */
    @Option(name = "constant-name", description = "Configure constant name conversion rules")
    List<NameMapping> constantNameMappings;

    /**
     * List of package name conversion configurations.
     */
    @Option(name = "package-name", description = "Configure package name conversion rules")
    List<NameMapping> packageNameMappings;

    @Override
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
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

            public String convertName(String token, String internalName, List<NameMapping> mappings) {
                if (mappings == null || mappings.isEmpty()) return internalName;

                for (var config : mappings) {
                    if (Objects.equals(config.token, token) || Objects.equals(config.token, internalName)) {
                        return config.name;
                    } else if (config.regex != null && config.regex.matcher(internalName).matches()) {
                        return internalName.replaceAll(config.regex.pattern(), config.name);
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

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        return true;
    }

    /**
     * Naming mapping rule configuration.
     * <p>
     * Compact CLI (see {@link Compact}): {@code -class-name=Person->CustomPerson},
     * {@code -class-name=/(.*)_ID/->$1Id}, {@code -package-name=nsUri->java.package}.
     * </p>
     */
    // More specific regex template first so "/…/->…" is not parsed as token="/…/".
    @Compact(formats = {"/{regex}/->{name}", "{token}->{name}"})
    public static class NameMapping {

        /**
         * The original identifier to be converted (e.g., name in XML Schema or namespace URI of a package).
         */
        @Option(name = "token", description = "The original identifier to match")
        String token;

        /**
         * Regular expression used to match the internal name.
         * If matched, it will be replaced with {@link #name}.
         */
        @Option(name = "regex", description = "Regular expression used to match the internal name")
        Pattern regex;

        /**
         * The target name after conversion.
         */
        @Option(name = "name", required = true, description = "The target mapping name")
        String name;

    }

}
