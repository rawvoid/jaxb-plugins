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
 * Thin reflective access to Lombok's {@code lombok.core.handlers.Singulars#autoSingularize}.
 * <p>
 * That class is packaged only inside Lombok's SCL shadow jar (not a normal classpath type).
 * We load it via {@code lombok.launch.Main#getShadowClassLoader()} so singularization stays
 * identical to what annotation processing uses — no local copy of the rules table.
 * </p>
 * <p>
 * If Lombok is missing or the reflective bootstrap fails, {@link #autoSingularize(String)}
 * returns {@code null} (callers should set an explicit {@code @Singular} value).
 * </p>
 */
public final class LombokSingulars {

    private static final Logger log = LoggerFactory.getLogger(LombokSingulars.class);

    /** Cached {@code Singulars.autoSingularize(String)}; {@code null} if resolve failed. */
    private static final Method AUTO_SINGULARIZE = resolveAutoSingularize();

    private static volatile boolean failureLogged;

    private LombokSingulars() {
    }

    /**
     * @return singular form from Lombok, or {@code null} when auto-singularization is refused
     *         or Lombok cannot be reached
     */
    public static String autoSingularize(String name) {
        if (name == null || name.isEmpty() || AUTO_SINGULARIZE == null) {
            return null;
        }
        try {
            return (String) AUTO_SINGULARIZE.invoke(null, name);
        } catch (InvocationTargetException e) {
            logOnce("Lombok Singulars.autoSingularize failed", e.getCause() != null ? e.getCause() : e);
            return null;
        } catch (ReflectiveOperationException e) {
            logOnce("Lombok Singulars.autoSingularize invoke failed", e);
            return null;
        }
    }

    /**
     * Bootstrap: {@code Main.getShadowClassLoader().loadClass("…Singulars").getMethod(…)}.
     */
    private static Method resolveAutoSingularize() {
        try {
            var main = Class.forName("lombok.launch.Main");
            var getShadow = main.getDeclaredMethod("getShadowClassLoader");
            getShadow.setAccessible(true);
            var shadow = (ClassLoader) getShadow.invoke(null);
            var singulars = shadow.loadClass("lombok.core.handlers.Singulars");
            return singulars.getMethod("autoSingularize", String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            logOnce(
                "Cannot load lombok.core.handlers.Singulars via ShadowClassLoader; "
                    + "@Singular will use explicit field names",
                e
            );
            return null;
        }
    }

    private static void logOnce(String message, Throwable t) {
        if (!failureLogged) {
            synchronized (LombokSingulars.class) {
                if (!failureLogged) {
                    failureLogged = true;
                    log.warn(message, t);
                }
            }
        }
    }
}
