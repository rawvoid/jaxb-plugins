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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A JAXB XJC plugin that enables support for Java 8's JSR-310 date/time API (java.time package).
 * This plugin automatically maps XSD time-related types (e.g., xs:dateTime, xs:date, xs:time) to
 * corresponding JSR-310 types during code generation. It also generates or uses custom XmlAdapter
 * instances for serialization/deserialization, with configurable formats via DateTimeFormatter patterns.
 * <p>
 * Key features:
 * <ul>
 *   <li>Custom type mappings for XSD types or specific fields</li>
 *   <li>Automatic XmlAdapter generation based on format patterns</li>
 *   <li>Supports field-specific overrides without external binding files (.xjb)</li>
 * </ul>
 * <p>
 * For detailed configuration, refer to the plugin options below.
 *
 * @author Rawvoid
 */
@Option(name = "Xjsr310", description = "Enable JSR-310 date/time API support in generated JAXB classes")
public class JSR310Plugin extends AbstractPlugin {

    @Option(name = "adapter-package", defaultValue = "io.github.rawvoid.jaxb.adapter", description = "Package for generated XmlAdapters")
    String adapterPackage;

    @Option(name = "config", description = "Global configuration for type mappings and adapters")
    List<Config> configs;

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
        var typeMapper = jsr310TypeMapper();
        outline.getClasses().forEach(classOutline -> {
            var fieldOutlines = classOutline.getDeclaredFields();
            var jDefinedClass = classOutline.implClass;
            var className = jDefinedClass.fullName();
            for (var fieldOutline : fieldOutlines) {
                var propertyInfo = fieldOutline.getPropertyInfo();
                var schemaType = getSchemaType(propertyInfo);

                Class<?> targetType = null;
                var config = findConfig(className, propertyInfo, schemaType);
                if (config != null) {
                    targetType = config.targetClass;
                }
                if (targetType == null) {
                    targetType = typeMapper.get(schemaType);
                }
                if (targetType == null) continue;

                applyMapping(jDefinedClass, propertyInfo, targetType, config);
            }
        });
        return true;
    }

    public Config findConfig(String beanClassName, CPropertyInfo propertyInfo, QName schemaType) {
        var fieldFullName = beanClassName + "." + propertyInfo.getName(false);
        return configs.stream()
            .filter(config -> {
                var type = config.xmlDatatype;
                var patterns = config.regexPatterns;
                return (patterns == null || patterns.stream().anyMatch(p -> p.matcher(fieldFullName).matches()))
                    && (type == null || type.isBlank() || (schemaType != null && type.equals(schemaType.getLocalPart())));
            })
            .findFirst()
            .orElse(null);
    }

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

    public void applyMapping(JDefinedClass beanClass, CPropertyInfo propertyInfo, Class<?> targetType, Config config) {
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
        if (config != null && config.xmlAdapterClass != null) {
            field.annotate(XmlJavaTypeAdapter.class).param("value", config.xmlAdapterClass);
        } else {
            var adapterClass = generateAdapterClass(beanClass.owner(), targetType,
                propertyInfo.getSchemaType(), config == null ? null : config.pattern);
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

    JClass generateAdapterClass(JCodeModel codeModel, Class<?> targetClass, QName schemaType, String pattern) {
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
        adapterClassName = adapterPackage + "." + adapterClassName;

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

            if (Duration.class.equals(targetClass) || Instant.class.equals(targetClass) || Period.class.equals(targetClass)) {
                implementDurationAdapter(adapterClass, unmarshal, marshal, str, target);
            } else if (LocalDate.class.equals(targetClass) || LocalDateTime.class.equals(targetClass)
                || OffsetDateTime.class.equals(targetClass) || ZonedDateTime.class.equals(targetClass)
                || LocalTime.class.equals(targetClass) || YearMonth.class.equals(targetClass)
                || Year.class.equals(targetClass) || MonthDay.class.equals(targetClass) || OffsetTime.class.equals(targetClass)) {
                implementDateTimeAdapter(adapterClass, unmarshal, marshal, str, target, targetClass, pattern);
            } else if (Month.class.equals(targetClass)) {
                implementGMonthAdapter(adapterClass, unmarshal, marshal, str, target);
            } else if (DayOfWeek.class.equals(targetClass)) {
                implementDayOfWeekAdapter(adapterClass, unmarshal, marshal, str, target);
            } else if (Integer.class.equals(targetClass) && "gDay".equals(schemaType.getLocalPart())) {
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
     * LocalDate,LocalDateTime,OffsetDateTime,ZonedDateTime,LocalTime,YearMonth,Year,MonthDay
     */
    private void implementDateTimeAdapter(JDefinedClass adapterClass, JMethod unmarshal, JMethod marshal,
                                          JVar str, JVar target, Class<?> targetClass, String pattern) {
        JFieldVar formatter = null;
        if (pattern != null && !pattern.isBlank()) {
            ;
            formatter = adapterClass.field(JMod.PRIVATE | JMod.STATIC | JMod.FINAL,
                DateTimeFormatter.class, "formatter");
            var initInvoke = adapterClass.owner().ref(DateTimeFormatter.class)
                .staticInvoke("ofPattern")
                .arg(pattern);
            formatter.init(initInvoke);
        }

        var invoke = adapterClass.owner().ref(targetClass)
            .staticInvoke("parse");

        if (formatter == null) {
            invoke.arg(str);
            marshal.body()._return(target.invoke("toString"));
        } else {
            invoke.arg(str).arg(formatter);
            marshal.body()._return(target.invoke("format").arg(formatter));
        }
        unmarshal.body()._return(invoke);
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


    public Map<QName, Class<?>> jsr310TypeMapper() {
        // https://www.w3.org/TR/xmlschema-2/#built-in-datatypes
        Map<QName, Class<?>> mapper = new HashMap<>();
        var namespaceURI = XMLConstants.W3C_XML_SCHEMA_NS_URI;
        mapper.put(new QName(namespaceURI, "duration"), Duration.class); // PnYnMnDTnHnMnS → Duration
        mapper.put(new QName(namespaceURI, "dateTime"), LocalDateTime.class); // YYYY-MM-DDThh:mm:ss → LocalDateTime (no timezone)
        mapper.put(new QName(namespaceURI, "time"), LocalTime.class); // hh:mm:ss → LocalTime
        mapper.put(new QName(namespaceURI, "date"), LocalDate.class); // YYYY-MM-DD → LocalDate
        mapper.put(new QName(namespaceURI, "gYearMonth"), YearMonth.class); // YYYY-MM → YearMonth
        mapper.put(new QName(namespaceURI, "gYear"), Year.class); // YYYY → Year
        mapper.put(new QName(namespaceURI, "gMonthDay"), MonthDay.class); // --MM-DD → MonthDay
        mapper.put(new QName(namespaceURI, "gDay"), Integer.class); // ---DD → Integer (day only); plugin auto-generates XmlAdapter for "---DD" format
        mapper.put(new QName(namespaceURI, "gMonth"), Month.class); // --MM → Month (best available match)
        return mapper;
    }

    public static class Config {

        @Option(name = "xml-datatype", description = "XML datatype to map (e.g., dateTime, date)")
        String xmlDatatype;

        @Option(name = "target-class", description = "JSR-310 class for matched fields (e.g., java.time.LocalDate)")
        Class<?> targetClass;

        @Option(name = "pattern", placeholder = "pattern", description = "DateTimeFormatter pattern for auto-generated XmlAdapter (e.g., yyyy-MM-dd)")
        String pattern;

        @Option(name = "xml-adapter", description = "Custom XmlAdapter class for serialization/deserialization")
        Class<? extends XmlAdapter<?, String>> xmlAdapterClass;

        @Option(name = "regex", description = "Regex patterns to match field full names")
        List<Pattern> regexPatterns;

    }
}
