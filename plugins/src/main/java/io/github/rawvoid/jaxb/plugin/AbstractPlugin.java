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
    public String getOptionName() {
        Option option = getClass().getAnnotation(Option.class);
        if (option == null) {
            throw new IllegalStateException("Plugin must be annotated with @Option: " + getClass().getName());
        }
        return option.prefix() + option.name();
    }

    @Override
    public String getUsage() {
        LinkedHashMap<String, String> usages = new LinkedHashMap<>();
        usages.put(getOptionName(), getClass().getAnnotation(Option.class).description());
        collectOptionUsages(getClass(), " ".repeat(4), usages);

        var maxLength = usages.keySet().stream()
            .mapToInt(String::length)
            .max()
            .orElse(0);
        StringJoiner joiner = new StringJoiner("\n");
        usages.forEach((optionHead, description) -> {
            var padding = maxLength - optionHead.length();
            var usage = "  " + optionHead + " ".repeat(padding) + "        :  " + description;
            joiner.add(usage);
        });
        return joiner.toString();
    }

    public void collectOptionUsages(Class<?> clazz, String indent, LinkedHashMap<String, String> usages) {
        var optionFields = getOptionFields(clazz);
        for (var optionField : optionFields) {
            var fieldType = optionField.getType();
            var option = optionField.getAnnotation(Option.class);
            var optionHead = indent + option.prefix() + option.name();
            usages.put(optionHead, option.description());

            if (fieldType.getClassLoader() != null) {
                collectOptionUsages(fieldType, indent.repeat(2), usages);
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                var elementType = getElementType(optionField);
                if (elementType.getClassLoader() != null) {
                    collectOptionUsages(elementType, indent.repeat(2), usages);
                }
            }
        }
    }

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        if (optionParsed) return 0;
        var option = getClass().getAnnotation(Option.class);
        if (option == null) {
            throw new BadCommandLineException("Plugin must be annotated with @Option");
        }
        try {
            var arg = args[i].trim();
            if (arg.equals(getOptionName())) {
                var count = parseArgument(this, args, i + 1);
                optionParsed = true;
                return count;
            }
        } catch (Exception e) {
            throw new BadCommandLineException("Error parsing plugin option %s: %s".formatted(option.name(), e.getMessage()), e);
        }
        return 0;
    }

    public int parseArgument(Object object, String[] args, int i) throws Exception {
        var clazz = object.getClass();
        var optionFields = getOptionFields(clazz);

        int count = 0, j = i;
        for (; j < args.length; j++) {
            var arg = args[j].trim();
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
                        var elementType = getElementType(optionField);
                        Collection<Object> collection = createCollection(fieldType);
                        setFieldValue(object, optionField, collection, "[]");

                        while (true) {
                            var elementValue = newInstance(elementType);
                            var x = parseArgument(elementValue, args, j + 1);
                            if (x > 0) {
                                collection.add(elementValue);
                                j += x;
                            } else {
                                break;
                            }
                        }
                    } else if (fieldType.getClassLoader() != null) {
                        var value = newInstance(fieldType);
                        var x = parseArgument(value, args, j + 1);
                        if (x > 0) {
                            j += x;
                            setFieldValue(object, optionField, value, "");
                        }
                    } else {
                        throw new BadCommandLineException("Option %s must have a value".formatted(optionFlag));
                    }
                    break;
                } else {
                    var delimiter = option.delimiter();
                    var pattern = Pattern.compile("^" + optionFlag + "\\s*" + delimiter + "(.*)");
                    var matcher = pattern.matcher(arg);
                    if (matcher.matches()) {
                        matchedOptionField = optionField;
                        var textValue = matcher.group(1);
                        TextParser<?> parser = getParser(option, fieldType);
                        if (parser == null) {
                            if (Collection.class.isAssignableFrom(fieldType)) {
                                var elementType = getElementType(optionField);
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
