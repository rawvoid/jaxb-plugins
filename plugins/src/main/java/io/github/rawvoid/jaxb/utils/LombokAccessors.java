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

package io.github.rawvoid.jaxb.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Resolves Lombok default getter/setter names for a field (no {@code @Accessors}, no project
 * {@code lombok.config}).
 * <p>
 * Prefers Lombok's own capitalization via reflective access to
 * {@code lombok.core.handlers.HandlerUtil} (SCL shadow jar), mirroring
 * {@link LombokSingulars}. Naming rules match {@code HandlerUtil.toGetterName}/{@code toSetterName}
 * under default configuration: {@code boolean} uses {@code is}/{@code set}; other types use
 * {@code get}/{@code set}.
 * </p>
 * <p>
 * Does <em>not</em> read user {@code lombok.config} (fluent, prefix, etc.); XJC cannot see the
 * consumer compile module's config. If shadow bootstrap fails, falls back to a BASIC-style
 * capitalization equivalent.
 * </p>
 *
 * @author Rawvoid
 */
public final class LombokAccessors {

    private static final Logger log = LoggerFactory.getLogger(LombokAccessors.class);
    private static volatile boolean failureLogged;

    /**
     * {@code HandlerUtil.buildAccessorName(String, String, CapitalizationStrategy)} when resolved.
     */
    private static final Method BUILD_ACCESSOR_NAME = resolveBuildAccessorName();
    private static final Object DEFAULT_CAPITALIZATION = resolveDefaultCapitalization();

    private LombokAccessors() {
    }

    /**
     * Default Lombok getter name for {@code fieldName}.
     *
     * @param isBoolean {@code true} only for primitive {@code boolean} (not {@link Boolean})
     */
    public static String toGetterName(String fieldName, boolean isBoolean) {
        return toAccessorName(fieldName, isBoolean, "is", "get");
    }

    /**
     * Default Lombok setter name for {@code fieldName}.
     *
     * @param isBoolean {@code true} only for primitive {@code boolean} (not {@link Boolean})
     */
    public static String toSetterName(String fieldName, boolean isBoolean) {
        return toAccessorName(fieldName, isBoolean, "set", "set");
    }

    /**
     * Mirrors {@code HandlerUtil.toAccessorName} with {@code accessors == null}, no fluent, no prefix.
     */
    private static String toAccessorName(
        String fieldName,
        boolean isBoolean,
        String booleanPrefix,
        String normalPrefix
    ) {
        if (fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        // Field named isRunning (boolean): isRunning / setRunning (HandlerUtil special case).
        if (isBoolean && fieldName.startsWith("is") && fieldName.length() > 2
            && !Character.isLowerCase(fieldName.charAt(2))) {
            return booleanPrefix + fieldName.substring(2);
        }
        return buildAccessorName(isBoolean ? booleanPrefix : normalPrefix, fieldName);
    }

    private static String buildAccessorName(String prefix, String suffix) {
        if (suffix.isEmpty()) {
            return prefix;
        }
        if (prefix.isEmpty()) {
            return suffix;
        }
        if (BUILD_ACCESSOR_NAME != null && DEFAULT_CAPITALIZATION != null) {
            try {
                return (String) BUILD_ACCESSOR_NAME.invoke(null, prefix, suffix, DEFAULT_CAPITALIZATION);
            } catch (InvocationTargetException e) {
                logOnce("Lombok HandlerUtil.buildAccessorName failed", e.getCause() != null ? e.getCause() : e);
            } catch (ReflectiveOperationException e) {
                logOnce("Lombok HandlerUtil.buildAccessorName invoke failed", e);
            }
        }
        return prefix + basicCapitalize(suffix);
    }

    /**
     * Same shape as Lombok {@code CapitalizationStrategy.BASIC}.
     */
    static String basicCapitalize(String in) {
        if (in.isEmpty()) {
            return in;
        }
        var first = in.charAt(0);
        if (!Character.isLowerCase(first)) {
            return in;
        }
        var useUpperCase = in.length() > 2
            && (Character.isTitleCase(in.charAt(1)) || Character.isUpperCase(in.charAt(1)));
        return (useUpperCase ? Character.toUpperCase(first) : Character.toTitleCase(first)) + in.substring(1);
    }

    private static Method resolveBuildAccessorName() {
        try {
            var shadow = shadowClassLoader();
            if (shadow == null) {
                return null;
            }
            var handlerUtil = shadow.loadClass("lombok.core.handlers.HandlerUtil");
            var capitalizationStrategy = shadow.loadClass("lombok.core.configuration.CapitalizationStrategy");
            var method = handlerUtil.getDeclaredMethod(
                "buildAccessorName", String.class, String.class, capitalizationStrategy);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | LinkageError e) {
            logOnce(
                "Cannot load lombok.core.handlers.HandlerUtil via ShadowClassLoader; "
                    + "accessor names use BASIC capitalization fallback",
                e
            );
            return null;
        }
    }

    private static Object resolveDefaultCapitalization() {
        try {
            var shadow = shadowClassLoader();
            if (shadow == null) {
                return null;
            }
            var capitalizationStrategy = shadow.loadClass("lombok.core.configuration.CapitalizationStrategy");
            return capitalizationStrategy.getMethod("defaultValue").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            logOnce("Cannot load CapitalizationStrategy.defaultValue", e);
            return null;
        }
    }

    private static ClassLoader shadowClassLoader() throws ReflectiveOperationException {
        var main = Class.forName("lombok.launch.Main");
        var getShadow = main.getDeclaredMethod("getShadowClassLoader");
        getShadow.setAccessible(true);
        return (ClassLoader) getShadow.invoke(null);
    }

    private static void logOnce(String message, Throwable t) {
        if (!failureLogged) {
            synchronized (LombokAccessors.class) {
                if (!failureLogged) {
                    failureLogged = true;
                    log.warn(message, t);
                }
            }
        }
    }
}
