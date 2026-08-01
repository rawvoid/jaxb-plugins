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

package io.github.rawvoid.jaxb.plugin.xjc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author Rawvoid
 */
public class ReflectUtils {

    public static Field getField(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ex) {
            throw new IllegalStateException("Failed to access field '" + name + "' on " + type.getName(), ex);
        }
    }

    public static Field getField(String className, String name) {
        try {
            var type = Class.forName(className);
            return getField(type, name);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to load class '" + className + "'", ex);
        }
    }

    public static <T> T getFieldValue(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Failed to access field '" + field.getName() + "' on " + instance.getClass().getName(), ex);
        }
    }

    public static void setFieldValue(Field field, Object instance, Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Failed to set field '" + field.getName() + "' on " + instance.getClass().getName(), ex);
        }
    }

    public static Method getMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            var method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Failed to access method '" + name + "' on " + type.getName(), ex);
        }
    }

    public static Method getMethod(String className, String name, Class<?>... parameterTypes) {
        try {
            var type = Class.forName(className);
            return getMethod(type, name, parameterTypes);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to load class '" + className + "'", ex);
        }
    }

    public static <T> T invokeMethod(Method method, Object instance, Object... args) {
        try {
            return (T) method.invoke(instance, args);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Failed to invoke method '" + method.getName() + "' on " + instance.getClass().getName(), ex);
        }
    }

    public static Constructor<?> getConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            var constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Failed to access constructor on " + type.getName(), ex);
        }
    }

    public static Constructor<?> getConstructor(String className, Class<?>... parameterTypes) {
        try {
            var type = Class.forName(className);
            return getConstructor(type, parameterTypes);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to load class '" + className + "'", ex);
        }
    }

    public static <T> T newInstance(Constructor<?> constructor, Object... args) {
        try {
            return (T) constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to create instance of " + constructor.getDeclaringClass().getName(), e);
        }
    }

}
