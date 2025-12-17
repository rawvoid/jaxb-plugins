package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * @author Rawvoid
 */
public abstract class AbstractPlugin extends Plugin {

    private final Map<Class<?>, TextParser<?>> textParsersByOptionType = new HashMap<>();
    private final Map<String, TextParser<?>> textParsersByOptionName = new HashMap<>();

    private boolean argumentParsed = false;

    public AbstractPlugin() {
        initDefaultTextParsers();
    }

    @Override
    public String getOptionName() {
        Option option = getClass().getAnnotation(Option.class);
        if (option == null) {
            throw new IllegalStateException("Plugin must be annotated with @Option: " + getClass().getName());
        }
        return option.prefix() + option.name();
    }

    @Override
    public String getUsage() {
        LinkedHashMap<String, List<String>> usages = new LinkedHashMap<>();
        usages.put(getOptionName(), getClass().getAnnotation(Option.class).description().lines().toList());
        collectOptionUsages(getClass(), " ".repeat(4), usages);

        var maxLength = usages.keySet().stream()
            .mapToInt(String::length)
            .max()
            .orElse(0);
        StringJoiner usage = new StringJoiner("\n");
        var prefix = "  ";
        var delimiter = "        :  ";
        usages.forEach((optionHead, descriptions) -> {
            var padding = maxLength - optionHead.length();
            var it = descriptions.iterator();
            var description = it.next();
            usage.add(prefix + optionHead + " ".repeat(padding) + delimiter + description);

            int r = prefix.length() + optionHead.length() + padding + delimiter.length();
            while (it.hasNext()) {
                usage.add(" ".repeat(r) + it.next());
            }
        });
        return usage.toString();
    }

    private void collectOptionUsages(Class<?> clazz, String indent, LinkedHashMap<String, List<String>> usages) {
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
            var optionHead = new StringBuilder(indent).append(option.prefix()).append(option.name());
            if (!isCollection || (getOptionFields(getCollectionElementType(optionField)).isEmpty())) {
                optionHead.append(delimiter).append('<').append(placeholder).append('>');
            }
            usages.put(optionHead.toString(), formatUsageDescription(option, optionField));

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

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        if (argumentParsed) return 0;
        var option = getClass().getAnnotation(Option.class);
        if (option == null) {
            throw new BadCommandLineException("Plugin must be annotated with @Option");
        }
        try {
            var arg = args[i].trim();
            if (arg.equals(getOptionName())) {
                var count = parseArgument(this, args, i + 1);
                argumentParsed = true;
                return count;
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
                var optionFlag = option.prefix() + option.name();
                if (arg.trim().equals(optionFlag)) {
                    matchedOptionField = optionField;
                    if (fieldType.equals(boolean.class) || fieldType.equals(Boolean.class)) {
                        setFieldValue(object, optionField, true, "true");
                    } else if (Collection.class.isAssignableFrom(fieldType)) {
                        var elementType = getCollectionElementType(optionField);
                        Collection<Object> collection = createCollection(fieldType);
                        setFieldValue(object, optionField, collection, "[]");

                        while (true) {
                            var elementValue = newInstance(elementType);
                            var x = parseArgument(elementValue, args, j + 1);
                            if (x > 0) {
                                applyDefaultValueAndValidate(elementValue);
                                collection.add(elementValue);
                                j += x;
                                if (j + 1 < args.length && args[j + 1].trim().equals(optionFlag)) {
                                    j++;
                                } else {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    } else if (fieldType.getClassLoader() != null) {
                        var value = newInstance(fieldType);
                        var x = parseArgument(value, args, j + 1);
                        if (x > 0) {
                            j += x;
                            applyDefaultValueAndValidate(value);
                            setFieldValue(object, optionField, value, "");
                        }
                    } else {
                        throw new BadCommandLineException("Option %s must have a value".formatted(optionFlag));
                    }
                    break;
                } else {
                    var delimiter = option.delimiter();
                    var pattern = Pattern.compile("^" + Pattern.quote(optionFlag) + "\\s*" + Pattern.quote(delimiter) + "(.*)");
                    var matcher = pattern.matcher(arg);
                    if (matcher.matches()) {
                        matchedOptionField = optionField;
                        var textValue = matcher.group(1);
                        TextParser<?> parser = getParser(option, fieldType);
                        if (parser == null) {
                            if (Collection.class.isAssignableFrom(fieldType)) {
                                var elementType = getCollectionElementType(optionField);
                                parser = getParser(option, elementType);
                                if (parser == null) {
                                    throw new BadCommandLineException("Text parser not found for type: %s".formatted(elementType.getName()));
                                }
                                Collection<Object> collection = createCollection(fieldType);
                                setFieldValue(object, optionField, collection, "[]");
                                var value = parser.parse(option.name(), textValue);
                                collection.add(value);
                                for (var x = j + 1; x < args.length; x++) {
                                    arg = args[x];
                                    matcher = pattern.matcher(arg);
                                    if (matcher.matches()) {
                                        j++;
                                        textValue = matcher.group(1);
                                        value = parser.parse(option.name(), textValue);
                                        collection.add(value);
                                    } else {
                                        break;
                                    }
                                }
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
        applyDefaultValueAndValidate(object);
        return count;
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

            if (Collection.class.isAssignableFrom(fieldType)) {
                if (required && (value == null || ((Collection<?>) value).isEmpty())) {
                    throw new BadCommandLineException("Option %s is required in type: %s"
                        .formatted(option.prefix() + option.name(), type.getName()));
                }
            } else if (value == null) {
                var defaultValueText = option.defaultValue();
                if (!defaultValueText.isEmpty()) {
                    TextParser<?> parser = getParser(option, optionField.getType());
                    if (parser == null) {
                        throw new BadCommandLineException("Text parser not found for option: %s in type: %s"
                            .formatted(option.prefix() + option.name(), type.getName()));
                    }
                    var defaultValue = parser.parse(option.name(), defaultValueText);
                    applyDefaultValueAndValidate(defaultValue);
                    setFieldValue(object, optionField, defaultValue, defaultValueText);
                } else if (required) {
                    throw new BadCommandLineException("Option %s is required in type: %s"
                        .formatted(option.prefix() + option.name(), type.getName()));
                }
            }
        }
    }

    private TextParser<?> getParser(Option option, Class<?> fieldType) {
        return textParsersByOptionName.getOrDefault(option.name(), textParsersByOptionType.get(fieldType));
    }

    private Collection<Object> createCollection(Class<?> collectionType) {
        try {
            if (collectionType.isInterface()) {
                if (collectionType.equals(List.class)) {
                    return new ArrayList<>();
                } else if (collectionType.equals(Set.class)) {
                    return new HashSet<>();
                } else if (collectionType.equals(Queue.class)) {
                    return new ArrayDeque<>();
                } else {
                    throw new IllegalArgumentException("Unsupported collection type: " + collectionType.getName());
                }
            } else if (Modifier.isAbstract(collectionType.getModifiers())) {
                throw new IllegalArgumentException("Unsupported abstract collection type: " + collectionType.getName());
            } else {
                @SuppressWarnings("unchecked")
                var collection = (Collection<Object>) newInstance(collectionType);
                return collection;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error creating collection of type " + collectionType.getName(), e);
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

    private <T> T newInstance(Class<T> clazz) {
        try {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Error creating instance of " + clazz.getName(), e);
        }
    }

    public <T> void registerTextParser(Class<T> clazz, TextParser<T> parser) {
        textParsersByOptionType.put(clazz, parser);
    }

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
        Class<?> fieldType = field.getType();
        if (!Collection.class.isAssignableFrom(fieldType)) {
            throw new IllegalArgumentException("Field '%s' is not a Collection type.".formatted(field.getName()));
        }
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return Object.class;
        }
        Type[] actualTypeArgs = parameterizedType.getActualTypeArguments();
        if (actualTypeArgs.length == 0) {
            return Object.class;
        }

        Type actualType = actualTypeArgs[0];
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
        registerTextParser(Object.class, (optionName, text) -> text.toString().trim());
    }
}
