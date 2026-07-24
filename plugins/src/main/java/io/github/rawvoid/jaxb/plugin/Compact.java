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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares compact single-text encoding(s) for a nested option type (or a specific list field).
 * <p>
 * When present, {@link AbstractPlugin} registers a {@link TextParser} so options can accept
 * {@code -option=value} form. Placeholders refer to nested {@link Option#name()} values.
 * Templates are tried <strong>in declaration order</strong>; put more specific patterns first.
 * </p>
 * <p>
 * Type-level example:
 * </p>
 * <pre>
 * {@code
 * @Compact(formats = {"/{name}/->{to}", "{input}->{to}"})
 * public static class NameMappingConfig {
 *     @Option(name = "input") String input;
 *     @Option(name = "name") Pattern name;
 *     @Option(name = "to", required = true) String to;
 * }
 * }
 * </pre>
 * CLI: {@code -class-name=Person->Human} or {@code -class-name=/(.*)_ID/->$1Id}
 * <p>
 * Field-level {@code @Compact} on a {@code List} option overrides the element type's templates
 * for that option only (registered by option name).
 * </p>
 * <p>
 * Structured multi-arg form remains supported. Values must not contain the literal separators
 * used in a template (e.g. {@code ->}). For {@code /{name}/->…}, the regex body must not
 * contain an unescaped {@code /}.
 * </p>
 *
 * @author Rawvoid
 */
@Retention(RUNTIME)
@Target({TYPE, FIELD})
public @interface Compact {

    /**
     * One or more templates for a single element as text.
     * <p>
     * Placeholders are {@code {optionName}}; text outside braces is a literal separator.
     * Consecutive placeholders require a non-empty separator. Tried in order until one matches.
     * </p>
     *
     * @return compact format templates, e.g. {@code {"/{name}/->{to}", "{input}->{to}"}}
     */
    String[] formats();

}
