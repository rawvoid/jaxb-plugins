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

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Abstract base class for JAXB XJC plugins that provides annotation-based command line option parsing functionality.
 *
 * <p>This class extends XJC's Plugin class and implements a complete option parsing framework that allows plugin
 * developers to define command line parameters by annotating fields with {@link Option} annotation. Key features include:</p>
 *
 * <ul>
 *   <li>Automatic parsing of command line arguments and mapping to fields annotated with @Option</li>
 *   <li>Support for various data types including primitives, wrapper types, strings, and regular expressions</li>
 *   <li>Support for collection types (List, Set, Queue) with repeatable options</li>
 *   <li>Support for nested objects and complex object structures</li>
 *   <li>Advanced features such as default values, required validation, and custom delimiters</li>
 *   <li>Automatic generation of formatted usage documentation</li>
 *   <li>Extensible with custom text parsers</li>
 * </ul>
 *
 * <p>Usage example:</p>
 * <pre>
 * {@code
 * @Option(name = "myPlugin", description = "My custom plugin")
 * public class MyPlugin extends AbstractPlugin {
 *     @Option(name = "output", description = "Output directory")
 *     private String outputDir;
 *
 *     @Option(name = "verbose", description = "Enable verbose mode")
 *     private boolean verbose;
 * }
 * }
 * </pre>
 *
 * @author Rawvoid
 */
public abstract class AbstractPlugin extends Plugin {

    private final Map<Class<?>, TextParser<?>> textParsersByOptionType = new HashMap<>();
    private final Map<String, TextParser<?>> textParsersByOptionName = new HashMap<>();

    /**
     * Constructs a new AbstractPlugin and initializes default text parsers.
     *
     * <p>Automatically registers text parsers for all primitive types and common types.</p>
     */
    public AbstractPlugin() {
        initDefaultTextParsers();
    }

    /**
     * Returns the plugin's option name.
     *
     * <p>Reads the option name from the class-level @Option annotation. Throws an exception if the
     * plugin class is not annotated with @Option.</p>
     *
     * @return the plugin option name
     * @throws IllegalStateException if the plugin class is not annotated with @Option
     */
    @Override
    public String getOptionName() {
        var option = getClass().getAnnotation(Option.class);
        if (option == null) {
            throw new IllegalStateException("Plugin must be annotated with @Option: " + getClass().getName());
        }
        return option.name();
    }

    /**
     * Generates the plugin's usage documentation.
     *
     * <p>Automatically scans all fields annotated with @Option and generates formatted usage documentation including:</p>
     * <ul>
     *   <li>Option name and placeholder</li>
     *   <li>Option description</li>
     *   <li>Whether the option is required</li>
     *   <li>Default value if any</li>
     *   <li>Whether the option is repeatable</li>
     * </ul>
     *
     * @return the formatted usage documentation string
     */
    @Override
    public String getUsage() {
        List<Map.Entry<String, List<String>>> usages = new ArrayList<>();
        var option = getClass().getAnnotation(Option.class);
        usages.add(new AbstractMap.SimpleEntry<>(option.prefix() + option.name(), option.description().lines().toList()));
        collectOptionUsages(getClass(), " ".repeat(4), usages);

        var maxLength = usages.stream()
            .map(Map.Entry::getKey)
            .mapToInt(String::length)
            .max()
            .orElse(0);
        var usage = new StringJoiner("\n");
        var prefix = "  ";
        var delimiter = "        :  ";
        usages.forEach(entry -> {
            var optionCmd = entry.getKey();
            var descriptions = entry.getValue();
            var padding = maxLength - optionCmd.length();
            var it = descriptions.iterator();
            var description = it.next();
            usage.add(prefix + optionCmd + " ".repeat(padding) + delimiter + description);

            var r = prefix.length() + optionCmd.length() + padding + delimiter.length();
            while (it.hasNext()) {
                usage.add(" ".repeat(r) + it.next());
            }
        });
        return usage.toString();
    }

    private void collectOptionUsages(Class<?> clazz, String indent, List<Map.Entry<String, List<String>>> usages) {
        var optionFields = getOptionFields(clazz);
        for (var optionField : optionFields) {
            var fieldType = optionField.getType();
            var isCollection = Collection.class.isAssignableFrom(fieldType);
            var option = optionField.getAnnotation(Option.class);
            var delimiter = option.delimiter();
            var placeholder = option.placeholder();
            if (placeholder.isEmpty()) {
                placeholder = typePlaceholder(isCollection ? getCollectionElementType(optionField) : fieldType);
            }
            placeholder = placeholder == null ? "value" : placeholder;
            var optionCmd = new StringBuilder(indent).append(option.prefix()).append(option.name());
            if (!isCollection || (getOptionFields(getCollectionElementType(optionField)).isEmpty())) {
                optionCmd.append(delimiter).append('<').append(placeholder).append('>');
            }
            usages.add(new AbstractMap.SimpleEntry<>(optionCmd.toString(), formatUsageDescription(option, optionField)));

            if (fieldType.getClassLoader() != null) {
                collectOptionUsages(fieldType, indent.repeat(2), usages);
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                var elementType = getCollectionElementType(optionField);
                if (elementType.getClassLoader() != null) {
                    collectOptionUsages(elementType, indent.repeat(2), usages);
                }
            }
        }
    }

    private List<String> formatUsageDescription(Option option, Field field) {
        var parts = new StringJoiner(" ");
        if (!option.description().isEmpty()) {
            parts.add(option.description());
        }
        if (option.required()) {
            parts.add("[required]");
        }
        if (!option.defaultValue().isEmpty()) {
            parts.add("[default=" + option.defaultValue() + "]");
        }
        if (Collection.class.isAssignableFrom(field.getType())) {
            parts.add("[repeatable]");
        }
        return parts.toString().lines().toList();
    }

    /**
     * Parses command line arguments.
     *
     * <p>This method is called by the XJC framework to parse the plugin's command line arguments.
     * It recognizes the plugin's main option name and then delegates to internal argument parsing
     * logic to handle all sub-options.</p>
     *
     * @param opt  the XJC options object
     * @param args the command line arguments array
     * @param i    the current argument index
     * @return the number of arguments consumed
     * @throws BadCommandLineException if argument parsing fails
     * @throws IOException             if an I/O error occurs
     */
    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        var option = getClass().getAnnotation(Option.class);
        try {
            var arg = args[i].trim();
            if (arg.equals(option.prefix() + option.name())) {
                var count = parseArgument(this, args, i + 1);
                applyDefaultValueAndValidate(this);
                return count + 1;
            }
        } catch (Exception e) {
            throw new BadCommandLineException("Error parsing plugin option %s: %s".formatted(option.name(), e.getMessage()), e);
        }
        return 0;
    }

    private int parseArgument(Object object, String[] args, int i) throws Exception {
        var clazz = object.getClass();
        var optionFields = getOptionFields(clazz);

        int count = 0, j = i;
        for (; j < args.length; j++) {
            var arg = args[j];
            Field matchedOptionField = null;
            for (var optionField : optionFields) {
                var fieldType = optionField.getType();
                var option = optionField.getAnnotation(Option.class);
                var optionCmd = option.prefix() + option.name();
                if (arg.trim().equals(optionCmd)) {
                    matchedOptionField = optionField;
                    if (fieldType.equals(boolean.class) || fieldType.equals(Boolean.class)) {
                        setFieldValue(object, optionField, true, "true");
                    } else if (Collection.class.isAssignableFrom(fieldType)) {
                        j += parseCollectionArgument(object, optionField, option, null, null, args, j);
                    } else if (fieldType.getClassLoader() != null) {
                        var value = newInstance(fieldType);
                        var x = parseArgument(value, args, j + 1);
                        if (x > 0) {
                            j += x;
                            setFieldValue(object, optionField, value, "");
                        }
                    } else {
                        throw new BadCommandLineException("Option %s must have a value".formatted(optionCmd));
                    }
                    break;
                } else {
                    var delimiter = option.delimiter();
                    var optionQt = Pattern.quote(optionCmd);
                    var delimiterQt = Pattern.quote(delimiter);
                    var pattern = Pattern.compile("^" + optionQt + "\\s*" + delimiterQt + "(.*)");
                    var matcher = pattern.matcher(arg);
                    if (matcher.matches()) {
                        matchedOptionField = optionField;
                        var textValue = matcher.group(1);
                        var parser = getParser(option, fieldType);
                        if (parser == null) {
                            if (Collection.class.isAssignableFrom(fieldType)) {
                                j += parseCollectionArgument(object, optionField, option, textValue, pattern, args, j);
                            } else {
                                throw new BadCommandLineException("Text parser not found for type: %s".formatted(fieldType.getName()));
                            }
                        } else {
                            var value = parser.parse(option.name(), textValue);
                            setFieldValue(object, optionField, value, textValue);
                        }
                        break;
                    }
                }
            }
            if (matchedOptionField != null) {
                optionFields.remove(matchedOptionField);
                count = j - i + 1;
            } else {
                break;
            }
        }
        return count;
    }

    private int parseCollectionArgument(Object object, Field optionField, Option option, String textValue,
                                        Pattern regex, String[] args, int j) throws Exception {
        var elementType = getCollectionElementType(optionField);
        var fieldType = optionField.getType();
        var collection = newCollectionInstance(fieldType);
        var optionCmd = option.prefix() + option.name();

        var i = j;
        if (textValue == null) {
            while (true) {
                var elementValue = newInstance(elementType);
                var x = parseArgument(elementValue, args, j + 1);
                if (x > 0) {
                    collection.add(elementValue);
                    j += x;

                    var next = j + 1 < args.length ? args[j + 1].trim() : null;
                    if (Objects.equals(next, optionCmd)) {
                        j++;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        } else {
            var parser = getParser(option, elementType);
            if (parser == null) {
                var typeName = elementType.getName();
                var message = "Text parser not found for type: %s".formatted(typeName);
                throw new BadCommandLineException(message);
            }
            var value = parser.parse(option.name(), textValue);
            collection.add(value);
            for (var x = j + 1; x < args.length; x++) {
                var arg = args[x];
                var matcher = regex.matcher(arg);
                if (matcher.matches()) {
                    j++;
                    textValue = matcher.group(1);
                    value = parser.parse(option.name(), textValue);
                    collection.add(value);
                } else {
                    break;
                }
            }
        }
        setFieldValue(object, optionField, collection, "[]");
        return j - i;
    }

    private void applyDefaultValueAndValidate(Object object) throws Exception {
        var type = object.getClass();
        if (type.getClassLoader() == null) return;
        var optionFields = getOptionFields(type);
        BiFunction<String, String, String> noParserMsg = "Text parser not found for option '%s' in type '%s'"::formatted;
        BiFunction<String, String, String> noOptionMsg = "Option '%s' not found in type '%s'"::formatted;
        for (var optionField : optionFields) {
            optionField.setAccessible(true);
            var fieldType = optionField.getType();
            var value = optionField.get(object);
            var option = optionField.getAnnotation(Option.class);
            var required = option.required();
            var defaultValueText = option.defaultValue();

            if (Collection.class.isAssignableFrom(fieldType)) {
                if (value == null || ((Collection<?>) value).isEmpty()) {
                    if (!defaultValueText.isEmpty()) {
                        var collection = newCollectionInstance(fieldType);
                        setFieldValue(object, optionField, collection, "[]");
                        var elementType = getCollectionElementType(optionField);
                        var parser = getParser(option, elementType);
                        if (parser == null) {
                            var message = noParserMsg.apply(option.prefix() + option.name(), elementType.getName());
                            throw new BadCommandLineException(message);
                        }
                        var defaultValue = parser.parse(option.name(), defaultValueText);
                        applyDefaultValueAndValidate(defaultValue);
                        collection.add(defaultValue);
                    } else if (required) {
                        var message = noOptionMsg.apply(option.prefix() + option.name(), type.getName());
                        throw new BadCommandLineException(message);
                    }
                }
            } else if (value == null) {
                if (!defaultValueText.isEmpty()) {
                    var parser = getParser(option, optionField.getType());
                    if (parser == null) {
                        var message = noParserMsg.apply(option.prefix() + option.name(), type.getName());
                        throw new BadCommandLineException(message);
                    }
                    var defaultValue = parser.parse(option.name(), defaultValueText);
                    applyDefaultValueAndValidate(defaultValue);
                    setFieldValue(object, optionField, defaultValue, defaultValueText);
                } else if (required) {
                    var message = noOptionMsg.apply(option.prefix() + option.name(), type.getName());
                    throw new BadCommandLineException(message);
                }
            }
        }
    }

    private TextParser<?> getParser(Option option, Class<?> fieldType) {
        return textParsersByOptionName.getOrDefault(option.name(), textParsersByOptionType.get(fieldType));
    }

    private <T> T newInstance(Class<T> clazz) {
        try {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class %s does not have a no-arg constructor".formatted(clazz.getName()), e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("Cannot instantiate abstract class %s".formatted(clazz.getName()), e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access constructor of %s".formatted(clazz.getName()), e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Constructor of %s threw an exception".formatted(clazz.getName()), e);
        }
    }

    private Collection<Object> newCollectionInstance(Class<?> type) {
        if (type.equals(List.class)) {
            return new ArrayList<>();
        } else if (type.equals(Set.class)) {
            return new HashSet<>();
        } else if (type.equals(Queue.class)) {
            return new ArrayDeque<>();
        } else {
            @SuppressWarnings("unchecked")
            var collection = (Collection<Object>) newInstance(type);
            return collection;
        }
    }

    private void setFieldValue(Object object, Field field, Object value, String textValue) {
        try {
            field.setAccessible(true);
            field.set(object, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error setting field %s with value %s".formatted(field.getName(), textValue), e);
        }
    }

    /**
     * Registers a text parser for a specific type.
     *
     * <p>Allows plugins to register parsers for custom types, used to convert command line
     * text arguments into objects of the specified type.</p>
     *
     * @param <T>    the target type
     * @param clazz  the type to parse
     * @param parser the text parser implementation
     */
    public <T> void registerTextParser(Class<T> clazz, TextParser<T> parser) {
        textParsersByOptionType.put(clazz, parser);
    }

    /**
     * Registers a text parser for a specific option name.
     *
     * <p>Allows registration of a dedicated parser for a specific option name, which takes
     * precedence over type-based parsers.</p>
     *
     * @param <T>        the target type
     * @param optionName the option name
     * @param parser     the text parser implementation
     */
    public <T> void registerTextParser(String optionName, TextParser<T> parser) {
        textParsersByOptionName.put(optionName, parser);
    }

    private List<Field> getOptionFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        var targetClass = clazz;
        while (targetClass.getClassLoader() != null) {
            var localFields = targetClass.getDeclaredFields();
            fields.addAll(Arrays.stream(localFields)
                .filter(field -> field.isAnnotationPresent(Option.class))
                .toList());
            targetClass = targetClass.getSuperclass();
        }
        return fields;
    }

    private Class<?> getCollectionElementType(Field field) {
        var fieldType = field.getType();
        if (!Collection.class.isAssignableFrom(fieldType)) {
            throw new IllegalArgumentException("Field '%s' is not a Collection type.".formatted(field.getName()));
        }
        var genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return Object.class;
        }
        var actualTypeArgs = parameterizedType.getActualTypeArguments();
        if (actualTypeArgs.length == 0) {
            return Object.class;
        }

        var actualType = actualTypeArgs[0];
        if (actualType instanceof Class<?> elementType) {
            return elementType;
        } else if (actualType instanceof WildcardType wt) {
            var upperBounds = wt.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class<?> elementType) {
                return elementType;
            }
        } else if (actualType instanceof TypeVariable<?> tv) {
            var bounds = tv.getBounds();
            if (bounds.length > 0 && bounds[0] instanceof Class<?> elementType) {
                return elementType;
            }
        } else if (actualType instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> elementType) {
            return elementType;
        } else if (actualType instanceof GenericArrayType) {
            throw new IllegalArgumentException("Nested arrays are not supported. Field: '%s'".formatted(field.getName()));
        }
        return Object.class;
    }

    private String typePlaceholder(Class<?> type) {
        if (type.isPrimitive()) return type.getSimpleName().toLowerCase();
        if (type.equals(Class.class)) return "class";
        if (type.equals(Integer.class)) return "int";
        if (type.equals(Long.class)) return "long";
        if (type.equals(Double.class)) return "double";
        if (type.equals(Float.class)) return "float";
        if (type.equals(Short.class)) return "short";
        if (type.equals(Byte.class)) return "byte";
        if (type.equals(Character.class)) return "char";
        if (type.equals(Boolean.class)) return "boolean";
        if (type.equals(Pattern.class)) return "regex";
        return null;
    }

    private void initDefaultTextParsers() {
        registerTextParser(boolean.class, (optionName, text) -> Boolean.parseBoolean(text.toString().trim()));
        registerTextParser(Boolean.class, (optionName, text) -> Boolean.parseBoolean(text.toString().trim()));
        registerTextParser(int.class, (optionName, text) -> Integer.parseInt(text.toString().trim()));
        registerTextParser(Integer.class, (optionName, text) -> Integer.parseInt(text.toString().trim()));
        registerTextParser(double.class, (optionName, text) -> Double.parseDouble(text.toString().trim()));
        registerTextParser(Double.class, (optionName, text) -> Double.parseDouble(text.toString().trim()));
        registerTextParser(float.class, (optionName, text) -> Float.parseFloat(text.toString().trim()));
        registerTextParser(Float.class, (optionName, text) -> Float.parseFloat(text.toString().trim()));
        registerTextParser(short.class, (optionName, text) -> Short.parseShort(text.toString().trim()));
        registerTextParser(Short.class, (optionName, text) -> Short.parseShort(text.toString().trim()));
        registerTextParser(byte.class, (optionName, text) -> Byte.parseByte(text.toString().trim()));
        registerTextParser(Byte.class, (optionName, text) -> Byte.parseByte(text.toString().trim()));
        registerTextParser(char.class, (optionName, text) -> text.toString().charAt(0));
        registerTextParser(Character.class, (optionName, text) -> text.toString().charAt(0));
        registerTextParser(long.class, (optionName, text) -> Long.parseLong(text.toString().trim()));
        registerTextParser(Long.class, (optionName, text) -> Long.parseLong(text.toString().trim()));
        registerTextParser(Class.class, (optionName, text) -> Class.forName(text.toString().trim()));
        registerTextParser(String.class, (optionName, text) -> text.toString());
        registerTextParser(Pattern.class, (optionName, text) -> Pattern.compile(text.toString()));
        registerTextParser(Object.class, (optionName, text) -> text);
    }
}
