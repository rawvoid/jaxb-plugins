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

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares a compact single-text encoding for a nested option type.
 * <p>
 * When a nested type is annotated with {@code @Compact}, {@link AbstractPlugin} registers a
 * {@link TextParser} for that type so {@code List} (or single) options can accept
 * {@code -option=value} form. Placeholders refer to nested {@link Option#name()} values.
 * </p>
 * <p>
 * Example:
 * </p>
 * <pre>
 * {@code
 * @Compact(format = "{token}->{name}")
 * public static class NameMapping {
 *     @Option(name = "token") String token;
 *     @Option(name = "name", required = true) String name;
 * }
 * }
 * </pre>
 * CLI: {@code -package-name=http://example.com/ns->com.example.pkg}
 * <p>
 * Structured multi-arg form ({@code -package-name -token=… -name=…}) remains supported.
 * Values must not contain the literal separators used in the format (e.g. {@code ->}).
 * </p>
 *
 * @author Rawvoid
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface Compact {

    /**
     * Template for one element as a single text value.
     * <p>
     * Placeholders are {@code {optionName}} for nested {@link Option} fields; text outside
     * braces is a literal separator. Consecutive placeholders require a non-empty separator.
     * </p>
     *
     * @return compact format template, e.g. {@code "{token}->{name}"}
     */
    String format();

}
