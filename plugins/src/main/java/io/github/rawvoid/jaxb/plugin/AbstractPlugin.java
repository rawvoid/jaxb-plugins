package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author Rawvoid
 */
public abstract class AbstractPlugin extends Plugin {

    private final Map<Class<?>, TextParser<?>> textParsers = new HashMap<>();

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
        return super.parseArgument(opt, args, i);
    }

    public <T> void registerTextParser(Class<T> clazz, TextParser<T> parser) {
        textParsers.put(clazz, parser);
    }

    public abstract String getOptionDescription();

    /**
     * Initializes the default text parsers for common types.
     */
    private void initDefaultTextParsers() {
        registerTextParser(int.class, (optionName, text) -> Integer.parseInt(text.toString()));
        registerTextParser(Integer.class, (optionName, text) -> Integer.parseInt(text.toString()));
        registerTextParser(boolean.class, (optionName, text) -> Boolean.parseBoolean(text.toString()));
        registerTextParser(Boolean.class, (optionName, text) -> Boolean.parseBoolean(text.toString()));
        registerTextParser(double.class, (optionName, text) -> Double.parseDouble(text.toString()));
        registerTextParser(Double.class, (optionName, text) -> Double.parseDouble(text.toString()));
        registerTextParser(float.class, (optionName, text) -> Float.parseFloat(text.toString()));
        registerTextParser(Float.class, (optionName, text) -> Float.parseFloat(text.toString()));
        registerTextParser(short.class, (optionName, text) -> Short.parseShort(text.toString()));
        registerTextParser(Short.class, (optionName, text) -> Short.parseShort(text.toString()));
        registerTextParser(byte.class, (optionName, text) -> Byte.parseByte(text.toString()));
        registerTextParser(Byte.class, (optionName, text) -> Byte.parseByte(text.toString()));
        registerTextParser(char.class, (optionName, text) -> text.toString().charAt(0));
        registerTextParser(Character.class, (optionName, text) -> text.toString().charAt(0));
        registerTextParser(long.class, (optionName, text) -> Long.parseLong(text.toString()));
        registerTextParser(Long.class, (optionName, text) -> Long.parseLong(text.toString()));
        registerTextParser(Class.class, (optionName, text) -> Class.forName(text.toString()));
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
