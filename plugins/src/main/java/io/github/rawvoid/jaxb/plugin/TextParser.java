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

/**
 * A generic interface for parsing text input into objects of a specified type.
 * <p>
 * This interface provides a contract for implementing text parsers that convert
 * string-based input into strongly-typed objects. It is typically used in plugin
 * configurations where textual option values need to be transformed into their
 * corresponding object representations.
 * </p>
 *
 * @param <T> the type of object that this parser produces
 * @author Rawvoid
 */
public interface TextParser<T> {

    /**
     * Parses the given text input into an object of type T.
     *
     * @param optionName the name of the plugin option, must not be null
     * @param text       the input text to be parsed, must not be null
     * @return the parsed object of type T
     */
    T parse(String optionName, CharSequence text) throws Exception;

}
