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

import java.lang.reflect.Field;

/**
 * @author Rawvoid
 */
public class DefaultFieldAccessor<T, V> implements FieldAccessor<T, V> {

    private final Class<T> clazz;
    private final Field field;

    @SuppressWarnings("unchecked")
    public DefaultFieldAccessor(String className, String fieldName) {
        try {
            this.clazz = (Class<T>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("No such class: " + className, e);
        }
        try {
            this.field = clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("No such field: " + clazz.getName() + "." + fieldName, e);
        }
    }

    public DefaultFieldAccessor(Class<T> clazz, String fieldName) {
        try {
            this.field = clazz.getDeclaredField(fieldName);
            this.clazz = clazz;
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("No such field: " + clazz.getName() + "." + fieldName, e);
        }
    }

    @Override
    public void setValue(T instance, V value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot set field value: " + clazz.getName() + "." + field.getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getValue(T instance) {
        try {
            return (V) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot get field value: " + field.getName(), e);
        }
    }
}
