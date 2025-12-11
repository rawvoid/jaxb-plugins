package io.github.rawvoid.jaxb.plugin;

import com.sun.codemodel.*;
import com.sun.tools.xjc.BadCommandLineException;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.outline.Outline;
import org.jvnet.jaxb.annox.model.XAnnotation;
import org.jvnet.jaxb.annox.parser.XAnnotationParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * AnnotatePlugin is a JAXB plugin that allows you to add custom annotations to generated Java artifacts.
 *
 * @author Rawvoid
 */
public class AnnotatePlugin extends Plugin {

    public static final String OPTION_NAME = "Xannotate";
    public static final String CLASS_OPTION_NAME = "%s-class".formatted(OPTION_NAME);
    public static final String FIELD_OPTION_NAME = "%s-field".formatted(OPTION_NAME);
    public static final String METHOD_OPTION_NAME = "%s-method".formatted(OPTION_NAME);

    protected List<Config> classConfigs = new ArrayList<>();
    protected List<Config> fieldConfigs = new ArrayList<>();
    protected List<Config> methodConfigs = new ArrayList<>();

    @Override
    public String getOptionName() {
        return OPTION_NAME;
    }

    @Override
    public String getUsage() {
        return """
            -%s: Enables the Annotation XJC Plugin. This plugin adds custom annotations to generated Java artifacts.
            
            ## Configuration Options
            These options provide precise control over where annotations are added and to what extent they match.
            
            * **-%s=<annotation>[=regx:pattern]**   -> Add annotation to generated **classes**.
                e.g. -%s=@lombok.Data
                e.g. -%s=@MyAnnotation=regx:com.example.*
            
            * **-%s=<annotation>[=regx:pattern]**   -> Add annotation to generated **fields**.
                e.g. -%s=@lombok.Getter=regx:org.example.Person.*
            
            * **-%s=<annotation>[=regx:pattern]     -> Add annotation to generated **methods**.
                e.g. -%s=@lombok.Generated
            
            """.formatted(
            OPTION_NAME,
            CLASS_OPTION_NAME, CLASS_OPTION_NAME, CLASS_OPTION_NAME,
            FIELD_OPTION_NAME, FIELD_OPTION_NAME,
            METHOD_OPTION_NAME, METHOD_OPTION_NAME
        );
    }

    @Override
    public int parseArgument(Options opt, String[] args, int i) throws BadCommandLineException, IOException {
        var arg = args[i];
        if (arg.startsWith("-%s".formatted(CLASS_OPTION_NAME))) {
            parseClassAnnotation(arg);
        } else if (arg.startsWith("-%s".formatted(FIELD_OPTION_NAME))) {
            parseFieldAnnotation(arg);
        } else if (arg.startsWith("-%s".formatted(METHOD_OPTION_NAME))) {
            parseMethodAnnotation(arg);
        } else {
            return 0;
        }
        return 1;
    }

    public void parseClassAnnotation(String annotationArg) throws BadCommandLineException {
        var config = parseAnnotationConfig(annotationArg, CLASS_OPTION_NAME);
        classConfigs.add(config);
    }

    public void parseFieldAnnotation(String annotationArg) throws BadCommandLineException {
        var config = parseAnnotationConfig(annotationArg, FIELD_OPTION_NAME);
        fieldConfigs.add(config);
    }

    public void parseMethodAnnotation(String annotationArg) throws BadCommandLineException {
        var config = parseAnnotationConfig(annotationArg, METHOD_OPTION_NAME);
        methodConfigs.add(config);
    }

    public Config parseAnnotationConfig(String arg, String optionName) throws BadCommandLineException {
        try {
            var idx = arg.indexOf(optionName);
            Pattern pattern = null;
            XAnnotation<?> xAnnotation;

            var line = arg.substring(idx + optionName.length() + 1);
            var regxIdx = line.indexOf("=regx:");
            if (regxIdx > -1) {
                var regx = line.substring(regxIdx + "=regx:".length());
                var annotation = line.substring(line.indexOf('@'), regxIdx);
                pattern = Pattern.compile(regx);
                xAnnotation = XAnnotationParser.INSTANCE.parse(annotation);
            } else {
                xAnnotation = XAnnotationParser.INSTANCE.parse(line.substring(line.indexOf('@')));
            }
            return new Config(pattern, xAnnotation);
        } catch (Exception e) {
            throw new BadCommandLineException("Invalid config: %s. Please check the format: -%s=<annotation>[=regx:pattern]".formatted(arg, optionName), e);
        }
    }

    @Override
    public void onActivated(Options opts) throws BadCommandLineException {
        super.onActivated(opts);
    }

    @Override
    public void postProcessModel(Model model, ErrorHandler errorHandler) {
        super.postProcessModel(model, errorHandler);
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
        outline.getClasses().forEach(classOutline -> {
            var jDefinedClass = classOutline.implClass;

            var matchedClassConfigs = getMatchedClassConfigs(jDefinedClass);
            matchedClassConfigs.forEach(config -> {
                var xAnnotation = config.annotation;
                var annotationUse = jDefinedClass.annotate(xAnnotation.getAnnotationClass());
                xAnnotation.getFieldsList().forEach(field ->
                    fillAnnotationParam(annotationUse, field.getName(), field.getValue()));
            });

            jDefinedClass.fields().values().forEach(field -> {
                var matchedFieldConfigs = getMatchedFieldConfigs(jDefinedClass, field);
                for (var config : matchedFieldConfigs) {
                    var xAnnotation = config.annotation;
                    var annotationUse = field.annotate(xAnnotation.getAnnotationClass());
                    xAnnotation.getFieldsList().forEach(fieldParam ->
                        fillAnnotationParam(annotationUse, fieldParam.getName(), fieldParam.getValue()));
                }
            });

            jDefinedClass.methods().forEach(method -> {
                var matchedMethodConfigs = getMatchedMethodConfigs(jDefinedClass, method);
                for (var config : matchedMethodConfigs) {
                    var xAnnotation = config.annotation;
                    var annotationUse = method.annotate(xAnnotation.getAnnotationClass());
                    xAnnotation.getFieldsList().forEach(field ->
                        fillAnnotationParam(annotationUse, field.getName(), field.getValue()));
                }
            });
        });

        return true;
    }

    public List<Config> getMatchedClassConfigs(JDefinedClass jDefinedClass) {
        var className = jDefinedClass.fullName();
        return classConfigs.stream()
            .filter(config -> config.pattern == null || config.pattern.matcher(className).matches())
            .toList();
    }

    public List<Config> getMatchedMethodConfigs(JDefinedClass jDefinedClass, JMethod jMethod) {
        var methodName = new StringJoiner(".")
            .add(jDefinedClass.fullName())
            .add(jMethod.name())
            .toString();
        return methodConfigs.stream()
            .filter(config -> config.pattern == null || config.pattern.matcher(methodName).matches())
            .toList();
    }

    public List<Config> getMatchedFieldConfigs(JDefinedClass jDefinedClass, JFieldVar jFieldVar) {
        var fieldName = new StringJoiner(".")
            .add(jDefinedClass.fullName())
            .add(jFieldVar.name())
            .toString();
        return fieldConfigs.stream()
            .filter(config -> config.pattern == null || config.pattern.matcher(fieldName).matches())
            .toList();
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
                iterable.forEach(item -> fullArrayParam(array, item));
            }
        }
    }

    public void fullArrayParam(JAnnotationArrayMember array, Object value) {
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

    public record Config(Pattern pattern, XAnnotation<?> annotation) {
    }
}