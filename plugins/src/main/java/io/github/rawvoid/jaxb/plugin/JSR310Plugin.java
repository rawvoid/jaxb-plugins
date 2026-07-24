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

import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CValuePropertyInfo;
import com.sun.tools.xjc.outline.Outline;
import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.zone.ZoneRules;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *
 * JAXB plugin that enables JSR-310 date/time API support in generated JAXB classes.
 * <p>
 * This plugin allows users to use the JSR-310 date/time API (java.time package) in their JAXB classes.
 * It modifies the generated JAXB classes to use the appropriate adapter classes for marshalling and unmarshalling.
 * </p>
 *
 * @author Rawvoid
 */
@Option(name = "Xjsr310", description = "Enable JSR-310 date/time API support in generated JAXB classes")
public class JSR310Plugin extends AbstractPlugin {

    @Option(name = "adapter-package",
        description = "Package name for auto-generated XmlAdapter classes (default: derived from common package of generated classes)")
    String adapterPackage;

    @Option(name = "mapping", description = "Define a type mapping rule (XSD type → Java class + adapter)")
    List<TypeMappingConfig> mappings;

    private static boolean isGDay(QName schemaType) {
        return schemaType != null && "gDay".equals(schemaType.getLocalPart());
    }

    static String findCommonPackage(List<String> packages) {
        var first = packages.getFirst().split("\\.");
        var commonLength = first.length;

        for (var pkg : packages.subList(1, packages.size())) {
            var parts = pkg.split("\\.");
            commonLength = Math.min(commonLength, parts.length);
            for (int i = 0; i < commonLength; i++) {
                if (!first[i].equals(parts[i])) {
                    commonLength = i;
                    break;
                }
            }
        }

        return String.join(".", java.util.Arrays.copyOf(first, commonLength));
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        var defaultXsdTypeMapping = xsdBuiltInTypeToJavaMapping();
        var targetAdapterPackage = resolveAdapterPackage(outline);

        outline.getClasses().forEach(classOutline -> {
            var fieldOutlines = classOutline.getDeclaredFields();
            var jDefinedClass = classOutline.implClass;
            var className = jDefinedClass.fullName();
            for (var fieldOutline : fieldOutlines) {
                var propertyInfo = fieldOutline.getPropertyInfo();
                var schemaType = getSchemaType(propertyInfo);

                Class<?> targetType = null;
                var mapping = findTypeMappingConfig(className, propertyInfo, schemaType);
                if (mapping != null) {
                    targetType = mapping.targetClass;
                }
                if (targetType == null) {
                    targetType = defaultXsdTypeMapping.get(schemaType);
                }
                if (targetType == null) continue;

                applyMapping(jDefinedClass, propertyInfo, targetType, mapping, targetAdapterPackage);
            }
        });
        return true;
    }

    /**
     * Finds the type mapping configuration that matches the given bean class name, property info, and schema type.
     *
     * @param beanClassName the name of the bean class
     * @param propertyInfo  the property info of the property to map
     * @param schemaType    the schema type of the property
     * @return the type mapping configuration that matches the given criteria, or null if no match is found
     */
    public TypeMappingConfig findTypeMappingConfig(String beanClassName, CPropertyInfo propertyInfo, QName schemaType) {
        var fieldFullName = beanClassName + "." + propertyInfo.getName(false);
        return mappings.stream()
            .filter(config -> {
                var type = config.xsdType;
                var patterns = config.regexPatterns;
                return (patterns == null || patterns.isEmpty() || patterns.stream().anyMatch(p -> p.matcher(fieldFullName).matches()))
                    && (type == null || type.isBlank() || (schemaType != null && type.equals(schemaType.getLocalPart())));
            })
            .findFirst()
            .orElse(null);
    }

    /**
     * Determines the schema type of the given property info.
     *
     * @param propertyInfo the property info to determine the schema type for
     * @return the schema type of the property, or null if the property info is null or the schema type cannot be determined
     */
    public QName getSchemaType(CPropertyInfo propertyInfo) {
        if (propertyInfo == null) return null;
        return switch (propertyInfo) {
            case CElementPropertyInfo elementPropertyInfo -> {
                var types = elementPropertyInfo.getTypes();
                yield types.size() == 1 ? types.getFirst().getTypeName() : null;
            }
            case CAttributePropertyInfo attributePropertyInfo -> attributePropertyInfo.getSchemaType();
            case CValuePropertyInfo valuePropertyInfo -> valuePropertyInfo.getSchemaType();
            default -> null;
        };
    }

    /**
     * Applies the specified type mapping to the given property in the bean class.
     *
     * @param beanClass    the bean class to apply the mapping to
     * @param propertyInfo the property info of the property to apply the mapping to
     * @param targetType   the target Java class to map the property to
     * @param mapping      the type mapping configuration to apply
     */
    public void applyMapping(JDefinedClass beanClass, CPropertyInfo propertyInfo, Class<?> targetType, TypeMappingConfig mapping, String targetAdapterPackage) {
        // Handle fields
        var fieldName = propertyInfo.getName(false);
        var field = beanClass.fields().get(fieldName);
        if (field == null) return;
        var fieldType = field.type();
        var newType = beanClass.owner().ref(targetType);
        if (fieldType.isArray()) {
            newType = newType.array();
        } else if (propertyInfo.isCollection() && fieldType instanceof JClass jClass) {
            newType = jClass.erasure().narrow(newType);
        }
        field.type(newType);

        field.annotations().stream()
            .filter(anno -> anno.getAnnotationClass().fullName().equals(XmlJavaTypeAdapter.class.getName()))
            .forEach(field::removeAnnotation);
        if (mapping != null && mapping.adapterClass != null) {
            field.annotate(XmlJavaTypeAdapter.class).param("value", mapping.adapterClass);
        } else {
            var adapterClass = generateAdapterClass(beanClass.owner(), targetType,
                propertyInfo.getSchemaType(), mapping == null ? null : mapping.pattern, targetAdapterPackage);
            field.annotate(XmlJavaTypeAdapter.class).param("value", adapterClass);
        }

        // Handle getters and setters methods
        var publicName = propertyInfo.getName(true);
        var setterName = "set" + publicName;
        var getterPattern = Pattern.compile("^(get|is)" + Pattern.quote(publicName) + "$");
        var methods = beanClass.methods();
        for (var method : methods) {
            var name = method.name();
            var params = method.params();
            if (name.equals(setterName) && params.size() == 1) {
                var param = params.getFirst();
                param.type(newType);
            } else if (getterPattern.matcher(name).matches() && params.isEmpty()) {
                method.type(newType);
            }
        }
    }

    JClass generateAdapterClass(JCodeModel codeModel, Class<?> targetClass, QName schemaType, String pattern, String targetAdapterPackage) {
        var adapterClassName = targetClass.getSimpleName() + "XmlAdapter";
        if (Integer.class.equals(targetClass) && "gDay".equals(schemaType.getLocalPart())) {
            adapterClassName = "IntegerXmlAdapter_gDay";
        } else if (pattern != null) {
            var patternPart = pattern.chars()
                .map(c -> Character.isJavaIdentifierPart(c) ? c : '_')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
            var hashCode = Integer.toString(pattern.hashCode(), Character.MAX_RADIX).replace('-', '$');
            adapterClassName += "_" + patternPart + "_" + hashCode;
        }
        adapterClassName = targetAdapterPackage + "." + adapterClassName;

        try {
            var adapterClass = codeModel._class(adapterClassName);
            adapterClass._extends(codeModel.ref(XmlAdapter.class).narrow(String.class).narrow(targetClass));

            var unmarshal = adapterClass.method(JMod.PUBLIC, targetClass, "unmarshal");
            var str = unmarshal.param(String.class, "v");
            earlyReturnIfNull(unmarshal, JOp.cor(str.eq(JExpr._null()), str.invoke("isBlank")));
            unmarshal.annotate(Override.class);

            var marshal = adapterClass.method(JMod.PUBLIC, String.class, "marshal");
            var target = marshal.param(targetClass, "v");
            earlyReturnIfNull(marshal, target.eq(JExpr._null()));
            marshal.annotate(Override.class);

            var generator = adapterGeneratorMapping().get(targetClass);
            if (generator != null) {
                generator.generate(adapterClass, unmarshal, marshal, str, target, targetClass, schemaType, pattern);
            } else if (Integer.class.equals(targetClass) && isGDay(schemaType)) {
                implementGDayAdapter(adapterClass, unmarshal, marshal, str, target);
            } else {
                throw new IllegalArgumentException("%s does not support class: %s"
                    .formatted(getClass().getSimpleName(), targetClass.getName()));
            }

            return adapterClass;
        } catch (JClassAlreadyExistsException e) {
            return codeModel._getClass(adapterClassName);
        }
    }

    private void implementDurationAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                          JVar str, JVar target) {
        var invoke = adapterClass.owner().ref(Duration.class)
            .staticInvoke("parse")
            .arg(str);
        unmarshal.body()._return(invoke);

        marshal.body()._return(target.invoke("toString"));
    }

    /**
     * Generates an adapter for date/time types: LocalDate, LocalDateTime, OffsetDateTime,
     * ZonedDateTime, LocalTime, OffsetTime, YearMonth, Year, MonthDay.
     * <p>
     * For {@link OffsetDateTime} and {@link OffsetTime}, uses {@link TemporalAccessor} to
     * parse the input string with timezone tolerance — falling back to the system default
     * offset when the XML value does not include a timezone suffix.
     * For {@link LocalDate}, uses {@link DateTimeFormatter#ISO_DATE} to tolerate optional
     * timezone suffixes in {@code xs:date} values.
     * </p>
     */
    private void implementDateTimeAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                          JVar str, JVar target, Class<?> targetClass, String pattern) {
        var cm = adapterClass.owner();

        // Custom pattern takes priority — use explicit formatter for both parse and format.
        if (pattern != null && !pattern.isBlank()) {
            var formatter = adapterClass.field(JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
                DateTimeFormatter.class, "formatter");
            formatter.init(cm.ref(DateTimeFormatter.class).staticInvoke("ofPattern").arg(pattern));

            unmarshal.body()._return(
                cm.ref(targetClass).staticInvoke("parse").arg(str).arg(formatter));
            marshal.body()._return(target.invoke("format").arg(formatter));
            return;
        }

        // Offset types need TemporalAccessor-based timezone fallback.
        if (OffsetDateTime.class.equals(targetClass)) {
            implementOffsetDateTimeAdapter(adapterClass, unmarshal, marshal, str, target);
        } else if (OffsetTime.class.equals(targetClass)) {
            implementOffsetTimeAdapter(adapterClass, unmarshal, marshal, str, target);
        } else if (LocalDate.class.equals(targetClass)) {
            // xs:date allows optional timezone suffix; ISO_DATE handles both gracefully.
            unmarshal.body()._return(
                cm.ref(LocalDate.class).staticInvoke("parse")
                    .arg(str)
                    .arg(cm.ref(DateTimeFormatter.class).staticRef("ISO_DATE")));
            marshal.body()._return(target.invoke("toString"));
        } else {
            // All other types (LocalDateTime, ZonedDateTime, LocalTime, YearMonth, Year, MonthDay)
            unmarshal.body()._return(cm.ref(targetClass).staticInvoke("parse").arg(str));
            marshal.body()._return(target.invoke("toString"));
        }
    }

    /**
     * Generates unmarshal/marshal for {@link OffsetDateTime} with timezone fallback:
     * <pre>{@code
     * private static final ZoneRules ZONE_RULES = ZoneId.systemDefault().getRules();
     * ...
     * TemporalAccessor temporal = DateTimeFormatter.ISO_DATE_TIME.parse(v);
     * if (temporal.isSupported(ChronoField.OFFSET_SECONDS)) {
     *     return OffsetDateTime.from(temporal);
     * }
     * return LocalDateTime.from(temporal).atOffset(ZONE_RULES.getOffset(Instant.now()));
     * }</pre>
     */
    private void implementOffsetDateTimeAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                                JVar str, JVar target) {
        var cm = adapterClass.owner();

        var zoneRules = adapterClass.field(
            JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
            ZoneRules.class, "ZONE_RULES");
        zoneRules.init(
            cm.ref(ZoneId.class).staticInvoke("systemDefault").invoke("getRules"));

        var body = unmarshal.body();
        var temporal = body.decl(cm.ref(TemporalAccessor.class), "temporal",
            cm.ref(DateTimeFormatter.class).staticRef("ISO_DATE_TIME").invoke("parse").arg(str));

        var ifCond = body._if(temporal.invoke("isSupported")
            .arg(cm.ref(ChronoField.class).staticRef("OFFSET_SECONDS")));
        ifCond._then()._return(
            cm.ref(OffsetDateTime.class).staticInvoke("from").arg(temporal));

        // Fallback: parse as LocalDateTime, attach system default offset dynamically via ZONE_RULES.
        var currentOffset = zoneRules.invoke("getOffset").arg(cm.ref(Instant.class).staticInvoke("now"));
        body._return(
            cm.ref(LocalDateTime.class).staticInvoke("from").arg(temporal)
                .invoke("atOffset")
                .arg(currentOffset));

        marshal.body()._return(target.invoke("toString"));
    }

    /**
     * Generates unmarshal/marshal for {@link OffsetTime} with timezone fallback:
     * <pre>{@code
     * private static final ZoneRules ZONE_RULES = ZoneId.systemDefault().getRules();
     * ...
     * TemporalAccessor temporal = DateTimeFormatter.ISO_TIME.parse(v);
     * if (temporal.isSupported(ChronoField.OFFSET_SECONDS)) {
     *     return OffsetTime.from(temporal);
     * }
     * return LocalTime.from(temporal).atOffset(ZONE_RULES.getOffset(Instant.now()));
     * }</pre>
     */
    private void implementOffsetTimeAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                            JVar str, JVar target) {
        var cm = adapterClass.owner();

        var zoneRules = adapterClass.field(
            JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
            ZoneRules.class, "ZONE_RULES");
        zoneRules.init(
            cm.ref(ZoneId.class).staticInvoke("systemDefault").invoke("getRules"));

        var body = unmarshal.body();
        var temporal = body.decl(cm.ref(TemporalAccessor.class), "temporal",
            cm.ref(DateTimeFormatter.class).staticRef("ISO_TIME").invoke("parse").arg(str));

        var ifCond = body._if(temporal.invoke("isSupported")
            .arg(cm.ref(ChronoField.class).staticRef("OFFSET_SECONDS")));
        ifCond._then()._return(
            cm.ref(OffsetTime.class).staticInvoke("from").arg(temporal));

        // Fallback: parse as LocalTime, attach system default offset dynamically via ZONE_RULES.
        var currentOffset = zoneRules.invoke("getOffset").arg(cm.ref(Instant.class).staticInvoke("now"));
        body._return(
            cm.ref(LocalTime.class).staticInvoke("from").arg(temporal)
                .invoke("atOffset")
                .arg(currentOffset));

        marshal.body()._return(target.invoke("toString"));
    }

    private void implementGDayAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                      JVar str, JVar target) {
        var cm = adapterClass.owner();
        var ifConf = unmarshal.body()._if(JOp.cand(
            str.invoke("length").gt(JExpr.lit(3)),
            str.invoke("startsWith").arg("---")));

        var thenBlock = ifConf._then();
        var day = thenBlock.decl(cm.ref(String.class), "day",
            str.invoke("substring").arg(JExpr.lit(3)));
        var parseIntInvoke = cm.ref(Integer.class).staticInvoke("parseInt")
            .arg(day);
        thenBlock._return(parseIntInvoke);

        var elseBlock = ifConf._else();
        parseIntInvoke = cm.ref(Integer.class).staticInvoke("parseInt")
            .arg(str);
        elseBlock._return(parseIntInvoke);

        var formatInvoke = cm.ref(String.class).staticInvoke("format")
            .arg(JExpr.lit("---%03d")).arg(target);
        marshal.body()._return(formatInvoke);
    }

    private void implementGMonthAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                        JVar str, JVar target) {
        var cm = adapterClass.owner();
        var ifCond = unmarshal.body()._if(JOp.cand(
            str.invoke("length").gt(JExpr.lit(2)),
            str.invoke("startsWith").arg("--")));

        var thenBlock = ifCond._then();
        var month = thenBlock.decl(cm.ref(String.class), "month",
            str.invoke("substring").arg(JExpr.lit(2)));
        var parseIntInvoke = cm.ref(Integer.class).staticInvoke("parseInt").arg(month);
        var ofInvoke = cm.ref(Month.class).staticInvoke("of").arg(parseIntInvoke);
        thenBlock._return(ofInvoke);

        var elseBlock = ifCond._else();
        parseIntInvoke = cm.ref(Integer.class).staticInvoke("parseInt").arg(str);
        ofInvoke = cm.ref(Month.class).staticInvoke("of").arg(parseIntInvoke);
        elseBlock._return(ofInvoke);

        var formatInvoke = cm.ref(String.class).staticInvoke("format")
            .arg(JExpr.lit("--%02d")).arg(target.invoke("getValue"));
        marshal.body()._return(formatInvoke);
    }

    private void implementDayOfWeekAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                           JVar str, JVar target) {
        var cm = adapterClass.owner();
        // DayOfWeek.of(1);
        var ofInvoke = cm.ref(DayOfWeek.class).staticInvoke("of").arg(str);
        unmarshal.body()._return(ofInvoke);

        // DayOfWeek.of(1).getValue();
        marshal.body()._return(target.invoke("getValue"));
    }

    private void earlyReturnIfNull(JMethod method, JExpression expr) {
        method.body()
            ._if(expr)
            ._then()
            ._return(JExpr._null());
    }

    /**
     * Returns a map that maps target Java classes to their corresponding XmlAdapter code generators.
     *
     * @return a map of Java classes to adapter generators
     */
    public Map<Class<?>, AdapterGenerator> adapterGeneratorMapping() {
        Map<Class<?>, AdapterGenerator> mapping = new HashMap<>();

        AdapterGenerator durationGen = (adapterClass, unmarshal, marshal, str, target, targetClass, schemaType, pattern) ->
            implementDurationAdapter(adapterClass, unmarshal, marshal, str, target);

        AdapterGenerator dateTimeGen = (adapterClass, unmarshal, marshal, str, target, targetClass, schemaType, pattern) ->
            implementDateTimeAdapter(adapterClass, unmarshal, marshal, str, target, targetClass, pattern);

        AdapterGenerator monthGen = (adapterClass, unmarshal, marshal, str, target, targetClass, schemaType, pattern) ->
            implementGMonthAdapter(adapterClass, unmarshal, marshal, str, target);

        AdapterGenerator dayOfWeekGen = (adapterClass, unmarshal, marshal, str, target, targetClass, schemaType, pattern) ->
            implementDayOfWeekAdapter(adapterClass, unmarshal, marshal, str, target);

        mapping.put(Duration.class, durationGen);
        mapping.put(Instant.class, durationGen);
        mapping.put(Period.class, durationGen);

        mapping.put(LocalDate.class, dateTimeGen);
        mapping.put(LocalDateTime.class, dateTimeGen);
        mapping.put(OffsetDateTime.class, dateTimeGen);
        mapping.put(ZonedDateTime.class, dateTimeGen);
        mapping.put(LocalTime.class, dateTimeGen);
        mapping.put(OffsetTime.class, dateTimeGen);
        mapping.put(YearMonth.class, dateTimeGen);
        mapping.put(Year.class, dateTimeGen);
        mapping.put(MonthDay.class, dateTimeGen);

        mapping.put(Month.class, monthGen);

        mapping.put(DayOfWeek.class, dayOfWeekGen);

        return mapping;
    }

    /**
     * Returns a map that maps XSD built-in datatypes to their corresponding Java classes.
     *
     * @return a map that maps XSD built-in datatypes to their corresponding Java classes
     */
    public Map<QName, Class<?>> xsdBuiltInTypeToJavaMapping() {
        // Reference: https://www.w3.org/TR/xmlschema-2/#built-in-datatypes
        Map<QName, Class<?>> mapping = new HashMap<>();
        var namespaceURI = XMLConstants.W3C_XML_SCHEMA_NS_URI;
        mapping.put(new QName(namespaceURI, "duration"), Duration.class); // PnYnMnDTnHnMnS → Duration
        mapping.put(new QName(namespaceURI, "dateTime"), OffsetDateTime.class); // YYYY-MM-DDThh:mm:ss[Z] → OffsetDateTime (timezone-tolerant)
        mapping.put(new QName(namespaceURI, "time"), OffsetTime.class); // hh:mm:ss[Z] → OffsetTime (timezone-tolerant)
        mapping.put(new QName(namespaceURI, "date"), LocalDate.class); // YYYY-MM-DD[Z] → LocalDate (timezone stripped via ISO_DATE)
        mapping.put(new QName(namespaceURI, "gYearMonth"), YearMonth.class); // YYYY-MM → YearMonth
        mapping.put(new QName(namespaceURI, "gYear"), Year.class); // YYYY → Year
        mapping.put(new QName(namespaceURI, "gMonthDay"), MonthDay.class); // --MM-DD → MonthDay
        mapping.put(new QName(namespaceURI, "gDay"), Integer.class); // ---DD → Integer (day only); plugin auto-generates XmlAdapter for "---DD" format
        mapping.put(new QName(namespaceURI, "gMonth"), Month.class); // --MM → Month (best available match)
        return mapping;
    }

    /**
     * Resolves the target package for generated adapter classes.
     * Uses explicit {@link #adapterPackage} if configured; otherwise derives the longest
     * common package prefix from all generated classes in the outline.
     */
    String resolveAdapterPackage(Outline outline) {
        if (adapterPackage != null && !adapterPackage.isBlank()) {
            return adapterPackage;
        }

        var packages = outline.getClasses().stream()
            .map(c -> c.implClass._package().name())
            .filter(p -> p != null && !p.isBlank())
            .distinct()
            .toList();

        if (packages.isEmpty()) {
            return "adapter";
        }

        var commonPackage = findCommonPackage(packages);
        return commonPackage.isEmpty() ? packages.getFirst() + ".adapter" : commonPackage + ".adapter";
    }

    @FunctionalInterface
    public interface AdapterGenerator {
        void generate(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                      JVar str, JVar target, Class<?> targetClass, QName schemaType, String pattern);
    }

    /**
     * Configuration class for mapping XSD built-in datatypes to Java classes.
     */
    public static class TypeMappingConfig {

        @Option(name = "xsd-type", description = "XSD built-in datatype name to map (e.g., dateTime, date, gDay)")
        String xsdType;

        @Option(name = "target-class", description = "Target Java class to use (typically from java.time, e.g., java.time.LocalDateTime)")
        Class<?> targetClass;

        @Option(name = "pattern", placeholder = "pattern", description = "DateTimeFormatter pattern for auto-generated XmlAdapter (e.g., yyyy-MM-dd)")
        String pattern;

        @Option(name = "adapter", description = "Custom XmlAdapter class to use instead of auto-generated one")
        Class<? extends XmlAdapter<?, String>> adapterClass;

        @Option(name = "regex", description = "Regular expression to match field names (fully qualified). Can be specified multiple times.")
        List<Pattern> regexPatterns;

    }
}
