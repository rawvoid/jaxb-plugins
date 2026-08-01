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

package io.github.rawvoid.jaxb.plugin.lombok;

/**
 * Shared access to Lombok's SCL shadow {@link ClassLoader}.
 * <p>
 * Internal types such as {@code lombok.core.handlers.HandlerUtil} and
 * {@code lombok.core.handlers.Singulars} are not normal classpath types; they must be loaded via
 * {@code lombok.launch.Main#getShadowClassLoader()}.
 * </p>
 *
 * @author Rawvoid
 * @see LombokAccessors
 * @see LombokSingulars
 */
public final class LombokShadow {

    private LombokShadow() {
    }

    /**
     * @return Lombok's shadow class loader
     * @throws ReflectiveOperationException if {@code lombok.launch.Main} is missing or the API changed
     */
    public static ClassLoader classLoader() throws ReflectiveOperationException {
        var main = Class.forName("lombok.launch.Main");
        var getShadow = main.getDeclaredMethod("getShadowClassLoader");
        getShadow.setAccessible(true);
        return (ClassLoader) getShadow.invoke(null);
    }

    /**
     * Loads a class from the shadow class loader.
     *
     * @throws ReflectiveOperationException if the shadow loader cannot be obtained or the class is missing
     */
    public static Class<?> loadClass(String name) throws ReflectiveOperationException {
        return classLoader().loadClass(name);
    }
}
