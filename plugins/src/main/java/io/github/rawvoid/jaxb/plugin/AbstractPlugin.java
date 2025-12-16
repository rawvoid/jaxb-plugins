package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.regex.Pattern;

/**
 * @author Rawvoid
 */
public abstract class AbstractPlugin extends Plugin {

    private final Map<Class<?>, TextParser<?>> textParsersByOptionType = new HashMap<>();
    private final Map<String, TextParser<?>> textParsersByOptionName = new HashMap<>();

    private boolean optionParsed = false;

    public AbstractPlugin() {
        initDefaultTextParsers();
    }

    @Override
    public String getUsage() {
        var joiner = new StringJoiner("\n");
        var usage = new StringBuilder();
        usage.append("  ").append(getOptionName());
        var description = getOptionDescription();
        if (description != null && !description.isBlank()) {
            usage.append("        :  ").append(getOptionDescription());
        }
        joiner.add(usage);

        var options = getOptionFields(getClass());
        fillUsage(options, joiner, 1);
        return joiner.toString();
    }

    public void fillUsage(List<Field> fields, StringJoiner joiner, int deep) {
        var indent = "  ".repeat(deep);
        var optionNameMaxLength = fields.stream()
            .mapToInt(field -> field.getName().length())
            .max()
            .orElse(0);

        fields.forEach(field -> {
            var fieldType = field.getType();
            if (Map.class.isAssignableFrom(fieldType)) {
                var className = field.getDeclaringClass().getName();
                var fieldFullName = className + "." + field.getName();
                throw new IllegalStateException("Map type is not supported for option field: " + fieldFullName);
            }
            String usage = getUsage(field.getAnnotation(Option.class), indent, optionNameMaxLength);
            joiner.add(usage);

            if (fieldType.getClassLoader() != null) {
                fillUsage(Arrays.asList(fieldType.getDeclaredFields()), joiner, deep + 1);
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                var elementType = getElementType(field);
                if (elementType.getClassLoader() != null) {
                    fillUsage(Arrays.asList(elementType.getDeclaredFields()), joiner, deep + 1);
                }
            }
        });
    }

    private String getUsage(Option option, String indent, int maxLength) {
        var usage = new StringBuilder();
        usage.append(indent).append(option.name());
        var description = option.description();
        if (description != null && !description.isBlank()) {
            usage.repeat(' ', maxLength - option.name().length());
            usage.append("    :  ").append(description);
        }
        return usage.toString();
    }

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        if (optionParsed) return 0;
        var option = getClass().getAnnotation(Option.class);
        if (option == null) {
            throw new BadCommandLineException("Plugin must be annotated with @Option");
        }
        try {
            var count = parseArgument(this, args, i);
            if (count > 0) optionParsed = true;
            return count;
        } catch (Exception e) {
            throw new BadCommandLineException("Error parsing plugin option %s: %s".formatted(option.name(), e.getMessage()), e);
        }
    }

    public int parseArgument(Object object, String[] args, int i) throws Exception {
        var clazz = object.getClass();
        var optionFields = getOptionFields(clazz);
        int count = 0, j = i;
        for (; j < args.length; j++) {
            var arg = args[j].trim();
            boolean optionMatched = false;
            for (var optionField : optionFields) {
                var fieldType = optionField.getType();
                var option = optionField.getAnnotation(Option.class);
                var optionFlag = option.prefix() + option.name();
                var optionDelimiter = option.delimiter();
                if (arg.trim().equals(optionFlag)) {
                    optionMatched = true;
                    if (fieldType.equals(boolean.class) || fieldType.equals(Boolean.class)) {
                        setFieldValue(object, optionField, true, "true");
                        j++;
                    } else if (Collection.class.isAssignableFrom(fieldType)) {
                        var elementType = getElementType(optionField);
                        Collection<Object> collection = createCollection(fieldType);
                        setFieldValue(object, optionField, collection, "[]");
                        j++;
                        TextParser<?> parser = getParser(option, elementType);
                        if (parser == null) {
                            if (elementType.getClassLoader() != null) {
                                Object elementValue = elementType.getConstructor().newInstance();
                                collection.add(elementValue);
                                var x = parseArgument(elementValue, args, j);
                                j += x;
                            } else {
                                throw new BadCommandLineException("Text parser not found for type: %s".formatted(elementType.getName()));
                            }
                        }
                    } else {
                        throw new BadCommandLineException("Option %s must have a value".formatted(optionFlag));
                    }
                } else {
                    var pattern = Pattern.compile("^" + optionFlag + "/s*" + optionDelimiter + "(.*)");
                    var matcher = pattern.matcher(arg);
                    if (matcher.matches()) {
                        optionMatched = true;
                        j++;
                        var textValue = matcher.group(1);
                        TextParser<?> parser = getParser(option, fieldType);
                        if (parser == null) {
                            if (fieldType.getClassLoader() != null) {
                                Object value = fieldType.getConstructor().newInstance();
                                var x = parseArgument(value, args, j);
                                if (x > 0) {
                                    j += x;
                                    setFieldValue(object, optionField, value, textValue);
                                }
                            } else if (Collection.class.isAssignableFrom(fieldType)) {
                                var elementType = getElementType(optionField);
                                parser = getParser(option, elementType);
                                if (parser == null) {
                                    throw new BadCommandLineException("Text parser not found for type: %s".formatted(elementType.getName()));
                                }
                                Collection<Object> collection = createCollection(fieldType);
                                setFieldValue(object, optionField, collection, "[]");
                                while (true) {
                                    var elementValue = elementType.getConstructor().newInstance();
                                    var x = parseArgument(elementValue, args, j);
                                    if (x > 0) {
                                        collection.add(elementValue);
                                        j += x;
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
                    }
                }
            }
            if (optionMatched) {
                count += j - i;
            } else {
                break;
            }
        }
        return count;
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
                return (Collection<Object>) collectionType.getConstructor().newInstance();
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

    public <T> void registerTextParser(Class<T> clazz, TextParser<T> parser) {
        textParsersByOptionType.put(clazz, parser);
    }

    public <T> void registerTextParser(String optionName, TextParser<T> parser) {
        textParsersByOptionName.put(optionName, parser);
    }

    public abstract String getOptionDescription();

    /**
     * Initializes the default text parsers for common types.
     */
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
        registerTextParser(char.class, (optionName, text) -> text.toString().trim().charAt(0));
        registerTextParser(Character.class, (optionName, text) -> text.toString().trim().charAt(0));
        registerTextParser(long.class, (optionName, text) -> Long.parseLong(text.toString().trim()));
        registerTextParser(Long.class, (optionName, text) -> Long.parseLong(text.toString().trim()));
        registerTextParser(Class.class, (optionName, text) -> Class.forName(text.toString().trim()));
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

    public Class<?> getElementType(Field field) {
        Class<?> fieldType = field.getType();
        if (Collection.class.isAssignableFrom(fieldType)) {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType pt) {
                Type[] actualTypeArgs = pt.getActualTypeArguments();
                if (actualTypeArgs.length == 1) {
                    Type actualType = actualTypeArgs[0];
                    if (actualType instanceof Class<?>) {
                        return (Class<?>) actualType;
                    }
                }
            }
            throw new IllegalStateException("Can't get element type of " + field.getName());
        }
        throw new IllegalArgumentException("Unsupported type for field: " + field);
    }
}
