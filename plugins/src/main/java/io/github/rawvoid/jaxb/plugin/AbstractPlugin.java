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
import com.sun.tools.xjc.Plugin;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *   <li>Nested-object lists: group marker once, next item on a repeated child field;
 *       list options may interleave at the plugin root</li>
 *   <li>{@link Compact} single-line encoding for nested mapping types ({@code -option=from->to})</li>
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

    private static final Pattern COMPACT_PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_-]*)}");

    private final Map<Class<?>, TextParser<?>> textParsersByOptionType = new HashMap<>();
    private final Map<String, TextParser<?>> textParsersByOptionName = new HashMap<>();

    /**
     * Constructs a new AbstractPlugin and initializes default text parsers.
     *
     * <p>Automatically registers text parsers for all primitive types and common types,
     * and for nested types annotated with {@link Compact}.</p>
     */
    public AbstractPlugin() {
        registerDefaultTextParsers();
        registerCompactParsersFrom(getClass());
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
            throw new IllegalStateException("Plugin class '%s' must be annotated with @Option".formatted(getClass().getName()));
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
        usages.add(new AbstractMap.SimpleEntry<>(getFullOptionName(option), option.description().lines().toList()));
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
            var optionUsage = entry.getKey();
            var descriptions = entry.getValue();
            var padding = maxLength - optionUsage.length();
            var it = descriptions.iterator();
            var description = it.next();
            usage.add(prefix + optionUsage + " ".repeat(padding) + delimiter + description);

            var r = prefix.length() + optionUsage.length() + padding + delimiter.length();
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
            var option = optionField.getAnnotation(Option.class);
            var usage = formatUsage(optionField, fieldType, option);
            usages.add(new AbstractMap.SimpleEntry<>(indent + usage, formatUsageDescription(option, optionField)));

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

    private String formatUsage(Field optionField, Class<?> fieldType, Option option) {
        var isCollection = Collection.class.isAssignableFrom(fieldType);
        var delimiter = option.delimiter();
        var optionCmd = new StringBuilder().append(option.prefix()).append(option.name());

        if (isCollection) {
            var elementType = getCollectionElementType(optionField);
            var compact = resolveCompact(optionField, elementType);
            if (compact != null) {
                optionCmd.append(delimiter).append('<').append(compactFormatsUsage(compact)).append('>');
                return optionCmd.toString();
            }
            if (getOptionFields(elementType).isEmpty()) {
                var placeholder = option.placeholder();
                if (placeholder.isEmpty()) {
                    placeholder = typePlaceholder(elementType);
                }
                placeholder = placeholder == null ? "value" : placeholder;
                optionCmd.append(delimiter).append('<').append(placeholder).append('>');
            }
            return optionCmd.toString();
        }

        var placeholder = option.placeholder();
        if (placeholder.isEmpty()) {
            placeholder = typePlaceholder(fieldType);
        }
        placeholder = placeholder == null ? "value" : placeholder;
        var compact = resolveCompact(optionField, fieldType);
        if (compact != null) {
            optionCmd.append(delimiter).append('<').append(compactFormatsUsage(compact)).append('>');
        } else {
            optionCmd.append(delimiter).append('<').append(placeholder).append('>');
        }
        return optionCmd.toString();
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
            var elementType = getCollectionElementType(field);
            if (resolveCompact(field, elementType) != null) {
                parts.add("[compact]");
            }
            if (isNestedOptionType(elementType) && !getOptionFields(elementType).isEmpty()) {
                // Nested-object lists: one group marker covers consecutive items; a repeated
                // child field starts the next item. Unused optional fields on the current item
                // still accept values — restate the group marker to separate different shapes.
                parts.add("[group once; same child field starts next item; restate group to separate shapes]");
            }
        } else if (resolveCompact(field, field.getType()) != null) {
            parts.add("[compact]");
        }
        return parts.toString().lines().toList();
    }

    private String getFullOptionName(Option option) {
        return option.prefix() + option.name();
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
        var fullOptionName = getFullOptionName(option);
        try {
            var arg = args[i].trim();
            if (arg.equals(fullOptionName)) {
                // Retain collection options on the plugin root so list blocks may interleave
                // with other options and reappear later (e.g. -package-name … -class-name … -package-name).
                var count = parseArgument(this, args, i + 1, true) + 1;
                applyDefaultValueAndValidate(this);
                postParseArgument(opt, count);
                return count;
            }
        } catch (Exception e) {
            throw new BadCommandLineException("Failed to configure plugin '%s' due to: %s"
                .formatted(fullOptionName, e.getMessage()), e);
        }
        return 0;
    }

    /**
     * Callback hook invoked after the plugin's command line arguments have been fully parsed and validated.
     *
     * <p>This method is called by the framework after:
     * <ul>
     *   <li>All command line arguments matching {@link Option} fields have been parsed and injected.</li>
     *   <li>Default values have been applied to unconfigured fields.</li>
     *   <li>Required field constraints have been checked.</li>
     * </ul>
     * </p>
     *
     * <p>This hook is ideal for performing cross-field validations (e.g., checking if two options are mutually
     * exclusive) or initializing internal state that depends on multiple parsed values.</p>
     *
     * @param opt          the XJC global options object, providing access to the overall compilation context.
     * @param consumedArgs the total number of arguments consumed by this plugin (including the main plugin activation option).
     * @throws Exception if any post-parsing logic or supplementary validation fails. The thrown exception
     *                   will be caught and wrapped into a {@code BadCommandLineException} by the framework.
     */
    protected void postParseArgument(Options opt, int consumedArgs) throws Exception {
        // No-op by default
    }

    /**
     * Parses {@code @Option} fields on {@code object} starting at {@code args[i]}.
     * <p>
     * Non-collection fields may appear at most once per object: after a field is set it is
     * removed from the match set, so a later occurrence of the same option ends this object
     * and (for nested list elements) lets the caller open the next list item.
     * </p>
     * <p>
     * When {@code retainCollectionOptions} is {@code true} (plugin root only), collection
     * options stay matchable and append on reappearance, so list blocks may be interleaved
     * with other options (e.g. {@code -package-name … -class-name … -package-name}).
     * Nested objects always drop collection options after the first block so that a repeated
     * child option (e.g. a second {@code -annotation=…} after {@code -regex}) starts the next
     * list element instead of appending to the current one.
     * </p>
     */
    private int parseArgument(Object object, String[] args, int i, boolean retainCollectionOptions) throws Exception {
        var remaining = getOptionFields(object.getClass());
        var j = i;
        while (j < args.length) {
            var match = matchOption(remaining, args[j]);
            if (match == null) {
                break;
            }

            var field = match.field();
            var option = match.option();
            var fieldType = field.getType();
            var textValue = match.textValue();

            if (isBooleanType(fieldType) && textValue == null) {
                setFieldValue(object, field, true);
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                j = parseCollectionOption(object, field, option, textValue, args, j);
                if (!retainCollectionOptions) {
                    remaining.remove(field);
                }
                continue;
            } else if (textValue != null) {
                var parser = getParser(option, fieldType);
                if (parser == null) {
                    throw newExceptionForNoParser(option, fieldType);
                }
                setFieldValue(object, field, parser.parse(option.name(), textValue));
            } else if (isNestedOptionType(fieldType)) {
                var value = newInstance(fieldType);
                var consumed = parseArgument(value, args, j + 1, false);
                if (consumed > 0) {
                    j += consumed;
                    setFieldValue(object, field, value);
                }
            } else {
                throw new BadCommandLineException(
                    "Option '%s' requires a value but none was provided. Use format: %s"
                        .formatted(getFullOptionName(option), formatUsage(field, fieldType, option)));
            }

            remaining.remove(field);
            j++;
        }
        return j - i;
    }

    /**
     * Parses one contribution to a collection option at {@code args[index]}.
     *
     * @return exclusive end index (first arg after the consumed block)
     */
    private int parseCollectionOption(Object object, Field optionField, Option option,
                                      String textValue, String[] args, int index) throws Exception {
        // Nested element types (including @Compact DTOs): one element per -name=value.
        // Whole-collection TextParsers (e.g. int-list2=1,2,3) apply only to scalar element lists,
        // so field-level @Compact (also by option name) is not treated as a whole-list parser.
        if (textValue != null) {
            var elementType = getCollectionElementType(optionField);
            if (!isNestedOptionType(elementType)) {
                var wholeCollectionParser = textParsersByOptionName.get(option.name());
                if (wholeCollectionParser == null) {
                    wholeCollectionParser = textParsersByOptionType.get(optionField.getType());
                }
                if (wholeCollectionParser != null) {
                    appendParsedCollection(object, optionField, wholeCollectionParser.parse(option.name(), textValue));
                    return index + 1;
                }
            }
        }
        return parseCollectionArgument(object, optionField, option, textValue, args, index);
    }

    /**
     * Parses repeated {@code -name=value} elements or a nested-object group block.
     *
     * @return exclusive end index (first arg after the consumed block)
     */
    private int parseCollectionArgument(Object object, Field optionField, Option option,
                                        String textValue, String[] args, int index) throws Exception {
        var elementType = getCollectionElementType(optionField);
        var collection = getOrCreateCollection(object, optionField);

        if (textValue != null) {
            return parseRepeatedEqualsValues(collection, option, elementType, textValue, args, index);
        }
        if (isNestedOptionType(elementType)) {
            return parseNestedObjectCollection(collection, elementType, getFullOptionName(option), args, index);
        }
        throw new BadCommandLineException(
            "Option '%s' requires a value but none was provided. Use format: %s"
                .formatted(getFullOptionName(option), formatUsage(optionField, optionField.getType(), option)));
    }

    /**
     * Parses consecutive {@code -option=value} args into collection elements (scalars or
     * {@link Compact} / nested DTOs via the element {@link TextParser}).
     *
     * @return exclusive end index after the last consumed {@code -option=value}
     */
    private int parseRepeatedEqualsValues(Collection<Object> collection, Option option, Class<?> elementType,
                                          String firstText, String[] args, int index) throws Exception {
        var parser = getParser(option, elementType);
        if (parser == null) {
            throw newExceptionForNoParser(option, elementType);
        }
        var fullOptionName = getFullOptionName(option);
        var delimiter = option.delimiter();
        var pattern = Pattern.compile(
            "^" + Pattern.quote(fullOptionName) + "\\s*" + Pattern.quote(delimiter) + "(.*)");

        collection.add(parser.parse(option.name(), firstText));
        var last = index;
        while (last + 1 < args.length) {
            var matcher = pattern.matcher(args[last + 1]);
            if (!matcher.matches()) {
                break;
            }
            last++;
            collection.add(parser.parse(option.name(), matcher.group(1)));
        }
        return last + 1;
    }

    /**
     * @return exclusive end index after the group marker and all parsed elements
     */
    private int parseNestedObjectCollection(Collection<Object> collection, Class<?> elementType,
                                            String groupName, String[] args, int groupIndex) throws Exception {
        // args[groupIndex] is the group marker that triggered this call.
        // lastConsumed advances over each element; findNextElementStart skips optional
        // repeated group markers and accepts either the first item or scheme-C continuations.
        var elementFields = getOptionFields(elementType);
        var lastConsumed = groupIndex;
        while (true) {
            var contentStart = findNextElementStart(args, lastConsumed, groupName, elementFields);
            if (contentStart < 0) {
                break;
            }
            var element = newInstance(elementType);
            // Nested elements never retain collection options: a repeated child option
            // after other fields closes the current element (same-field → next item).
            var consumed = parseArgument(element, args, contentStart, false);
            if (consumed <= 0) {
                break;
            }
            collection.add(element);
            lastConsumed = contentStart + consumed - 1;
        }
        return lastConsumed + 1;
    }

    /**
     * Finds the start index of the next nested-list element's fields.
     *
     * @param afterIndex    last arg index belonging to the previous element, or the opening group marker
     * @param elementFields {@code @Option} fields of the element type (caller-cached)
     * @return content start index, or {@code -1} if there is no further element
     */
    private int findNextElementStart(String[] args, int afterIndex, String groupName, List<Field> elementFields) {
        var i = afterIndex + 1;
        while (i < args.length && groupName.equals(args[i].trim())) {
            i++;
        }
        if (i >= args.length) {
            return -1;
        }
        if (matchOption(elementFields, args[i]) != null) {
            return i;
        }
        return -1;
    }

    private OptionMatch matchOption(List<Field> fields, String rawArg) {
        var arg = rawArg.trim();
        for (var field : fields) {
            var option = field.getAnnotation(Option.class);
            var fullOptionName = getFullOptionName(option);
            if (arg.equals(fullOptionName)) {
                return new OptionMatch(field, option, null);
            }
            var pattern = Pattern.compile(
                "^" + Pattern.quote(fullOptionName) + "\\s*" + Pattern.quote(option.delimiter()) + "(.*)");
            var matcher = pattern.matcher(arg);
            if (matcher.matches()) {
                return new OptionMatch(field, option, matcher.group(1));
            }
        }
        return null;
    }

    private static boolean isBooleanType(Class<?> type) {
        return type.equals(boolean.class) || type.equals(Boolean.class);
    }

    /**
     * True for user/plugin nested config types (has a non-bootstrap class loader).
     */
    private static boolean isNestedOptionType(Class<?> type) {
        return type.getClassLoader() != null;
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> getOrCreateCollection(Object object, Field optionField) throws Exception {
        optionField.setAccessible(true);
        var existing = (Collection<Object>) optionField.get(object);
        if (existing != null) {
            return existing;
        }
        var collection = newCollectionInstance(optionField.getType());
        setFieldValue(object, optionField, collection);
        return collection;
    }

    /**
     * Applies a whole-collection TextParser result. If the field already holds a collection
     * (e.g. after an interleaved earlier appearance of the same option), appends into it so
     * behavior matches element-wise scalar lists. Always stores a mutable collection instance
     * for the field type, because parsers often return immutable lists ({@code List.of}, {@code Stream.toList}).
     */
    @SuppressWarnings("unchecked")
    private void appendParsedCollection(Object object, Field optionField, Object parsed) throws Exception {
        if (!(parsed instanceof Collection<?> parsedCollection)) {
            throw new BadCommandLineException(
                "Text parser for option '%s' must return a Collection, but got %s"
                    .formatted(getFullOptionName(optionField.getAnnotation(Option.class)),
                        parsed == null ? "null" : parsed.getClass().getName()));
        }
        optionField.setAccessible(true);
        var existing = (Collection<Object>) optionField.get(object);
        var target = newCollectionInstance(optionField.getType());
        if (existing != null) {
            target.addAll(existing);
        }
        target.addAll((Collection<Object>) parsedCollection);
        setFieldValue(object, optionField, target);
    }

    private record OptionMatch(Field field, Option option, String textValue) {
    }

    private void applyDefaultValueAndValidate(Object object) throws Exception {
        var type = object.getClass();
        if (type.getClassLoader() == null) return;
        var optionFields = getOptionFields(type);
        for (var optionField : optionFields) {
            optionField.setAccessible(true);
            var fieldType = optionField.getType();
            var value = optionField.get(object);
            var option = optionField.getAnnotation(Option.class);
            var required = option.required();
            var defaultValueText = option.defaultValue();

            if (Collection.class.isAssignableFrom(fieldType)) {
                @SuppressWarnings("unchecked")
                var collection = (Collection<Object>) value;
                if (collection == null) {
                    collection = newCollectionInstance(fieldType);
                    setFieldValue(object, optionField, collection);
                }
                if (collection.isEmpty()) {
                    if (!defaultValueText.isEmpty()) {
                        var elementType = getCollectionElementType(optionField);
                        var parser = getParser(option, elementType);
                        if (parser == null) {
                            throw newExceptionForNoParser(option, elementType);
                        }
                        var defaultValue = parser.parse(option.name(), defaultValueText);
                        applyDefaultValueAndValidate(defaultValue);
                        collection.add(defaultValue);
                    } else if (required) {
                        throw newExceptionForNoValue(optionField);
                    }
                } else {
                    for (var item : collection) {
                        applyDefaultValueAndValidate(item);
                    }
                }
            } else if (value == null) {
                if (!defaultValueText.isEmpty()) {
                    var parser = getParser(option, optionField.getType());
                    if (parser == null) {
                        throw newExceptionForNoParser(option, fieldType);
                    }
                    var defaultValue = parser.parse(option.name(), defaultValueText);
                    applyDefaultValueAndValidate(defaultValue);
                    setFieldValue(object, optionField, defaultValue);
                } else if (required) {
                    throw newExceptionForNoValue(optionField);
                }
            }
        }
    }

    private BadCommandLineException newExceptionForNoParser(Option option, Class<?> fieldType) {
        return new BadCommandLineException("No text parser registered for field type '%s' or plugin option '%s'"
            .formatted(fieldType.getName(), getFullOptionName(option)));
    }

    private BadCommandLineException newExceptionForNoValue(Field field) {
        return new BadCommandLineException("Required field '%s' cannot be null in class %s"
            .formatted(field.getName(), field.getDeclaringClass().getName()));
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
            throw new IllegalArgumentException("Class '%s' does not have a no-arg constructor".formatted(clazz.getName()), e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("Cannot instantiate abstract class %s".formatted(clazz.getName()), e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access no-arg constructor of class %s".formatted(clazz.getName()), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Constructor of class '%s' threw an exception during instantiation".formatted(clazz.getName()), e);
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

    private void setFieldValue(Object object, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(object, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot set value '%s' to field '%s' in class '%s'"
                .formatted(value.toString(), field.getName(), object.getClass().getName()), e);
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
            throw new IllegalArgumentException("Nested arrays are not supported for field '%s'".formatted(field.getName()));
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

    private void registerDefaultTextParsers() {
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

    /**
     * Walks {@code @Option} nested types and list fields; registers {@link Compact} text parsers.
     * Type-level parsers are registered by element type; field-level {@code @Compact} by option name
     * (takes precedence via {@link #getParser}). Does not override an already-registered type parser.
     */
    private void registerCompactParsersFrom(Class<?> type) {
        if (!isNestedOptionType(type)) {
            return;
        }
        var typeCompact = type.getAnnotation(Compact.class);
        if (typeCompact != null && !textParsersByOptionType.containsKey(type)) {
            registerCompactParserByType(type, typeCompact);
        }
        for (var field : getOptionFields(type)) {
            var fieldType = field.getType();
            if (Collection.class.isAssignableFrom(fieldType)) {
                var elementType = getCollectionElementType(field);
                registerCompactParsersFrom(elementType);
                var fieldCompact = field.getAnnotation(Compact.class);
                if (fieldCompact != null) {
                    var option = field.getAnnotation(Option.class);
                    registerTextParser(option.name(), createCompactParser(elementType, fieldCompact));
                }
            } else if (isNestedOptionType(fieldType)) {
                registerCompactParsersFrom(fieldType);
                var fieldCompact = field.getAnnotation(Compact.class);
                if (fieldCompact != null) {
                    var option = field.getAnnotation(Option.class);
                    registerTextParser(option.name(), createCompactParser(fieldType, fieldCompact));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void registerCompactParserByType(Class<?> type, Compact compact) {
        var typed = (Class<Object>) type;
        registerTextParser(typed, createCompactParser(typed, compact));
    }

    private Compact resolveCompact(Field optionField, Class<?> nestedType) {
        var onField = optionField.getAnnotation(Compact.class);
        if (onField != null) {
            return onField;
        }
        return nestedType.getAnnotation(Compact.class);
    }

    private static String compactFormatsUsage(Compact compact) {
        return String.join("|", compact.formats());
    }

    private <T> TextParser<T> createCompactParser(Class<T> type, Compact compact) {
        var formats = compact.formats();
        if (formats.length == 0) {
            throw new IllegalStateException("@Compact on %s requires a non-empty formats array".formatted(type.getName()));
        }
        var compiledList = Arrays.stream(formats)
            .map(format -> compileCompactFormat(type, format))
            .toList();
        return (optionName, text) -> parseCompact(type, compiledList, optionName, text.toString());
    }

    private CompiledCompact compileCompactFormat(Class<?> type, String format) {
        if (format == null || format.isEmpty()) {
            throw new IllegalStateException("@Compact on %s contains an empty format entry".formatted(type.getName()));
        }
        var fieldsByOptionName = new HashMap<String, Field>();
        for (var field : getOptionFields(type)) {
            fieldsByOptionName.put(field.getAnnotation(Option.class).name(), field);
        }

        var optionNames = new ArrayList<String>();
        var literals = new ArrayList<String>();
        var matcher = COMPACT_PLACEHOLDER.matcher(format);
        var lastEnd = 0;
        while (matcher.find()) {
            literals.add(format.substring(lastEnd, matcher.start()));
            var optionName = matcher.group(1);
            if (!fieldsByOptionName.containsKey(optionName)) {
                throw new IllegalStateException(
                    "@Compact format on %s references unknown option '{%s}'".formatted(type.getName(), optionName));
            }
            if (!optionNames.isEmpty() && literals.getLast().isEmpty()) {
                throw new IllegalStateException(
                    "@Compact format on %s has consecutive placeholders without a separator near '{%s}'"
                        .formatted(type.getName(), optionName));
            }
            optionNames.add(optionName);
            lastEnd = matcher.end();
        }
        literals.add(format.substring(lastEnd));
        if (optionNames.isEmpty()) {
            throw new IllegalStateException(
                "@Compact format on %s must contain at least one {optionName} placeholder".formatted(type.getName()));
        }
        return new CompiledCompact(format, List.copyOf(optionNames), List.copyOf(literals), Map.copyOf(fieldsByOptionName));
    }

    private <T> T parseCompact(Class<T> type, List<CompiledCompact> templates, String optionName, String text)
        throws Exception {
        for (var compiled : templates) {
            var values = trySplitCompactValues(compiled, text);
            if (values == null) {
                continue;
            }
            var instance = newInstance(type);
            for (var i = 0; i < compiled.optionNames().size(); i++) {
                var nestedOptionName = compiled.optionNames().get(i);
                var field = compiled.fieldsByOptionName().get(nestedOptionName);
                var option = field.getAnnotation(Option.class);
                var parser = getParser(option, field.getType());
                if (parser == null) {
                    throw newExceptionForNoParser(option, field.getType());
                }
                setFieldValue(instance, field, parser.parse(option.name(), values.get(i)));
            }
            return instance;
        }
        var expected = templates.stream().map(CompiledCompact::format).collect(Collectors.joining("' | '"));
        throw new BadCommandLineException(
            "Invalid compact value for option '-%s': expected format '%s', got '%s'"
                .formatted(optionName, expected, text));
    }

    /**
     * @return placeholder values if {@code text} matches the template; {@code null} otherwise
     */
    private static List<String> trySplitCompactValues(CompiledCompact compiled, String text) {
        // Whole-arg trim so leading/trailing spaces around the compact value are ignored.
        text = text.strip();
        var literals = compiled.literals();
        var optionNames = compiled.optionNames();
        var prefix = literals.getFirst();
        if (!text.startsWith(prefix)) {
            return null;
        }
        var cursor = prefix.length();
        var values = new ArrayList<String>(optionNames.size());
        for (var i = 0; i < optionNames.size(); i++) {
            var nextLiteral = literals.get(i + 1);
            String value;
            if (nextLiteral.isEmpty()) {
                if (i != optionNames.size() - 1) {
                    return null;
                }
                value = text.substring(cursor);
                cursor = text.length();
            } else {
                var sepAt = text.indexOf(nextLiteral, cursor);
                if (sepAt < 0) {
                    return null;
                }
                value = text.substring(cursor, sepAt);
                cursor = sepAt + nextLiteral.length();
            }
            // Whitespace around separators attaches to placeholders; strip so
            // "a -> b : c" matches the same as "a->b:c".
            value = value.strip();
            // Empty middle placeholders are not a match (keeps more-specific templates from
            // stealing values). Trailing empty is allowed so optional last fields work, e.g.
            // "{ns}->{package}:{prefix}" with "uri->com.example:" → package set, prefix blank.
            if (value.isEmpty() && i != optionNames.size() - 1) {
                return null;
            }
            values.add(value);
        }
        if (cursor != text.length()) {
            return null;
        }
        return values;
    }

    private record CompiledCompact(
        String format,
        List<String> optionNames,
        List<String> literals,
        Map<String, Field> fieldsByOptionName
    ) {
    }
}
