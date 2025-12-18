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

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation to mark plugin options.
 * <p>
 * This annotation is used to mark fields in plugin classes that represent configurable
 * options. It provides metadata about the option, such as its name, prefix, delimiter,
 * requirement, default value, placeholder, and description.
 * </p>
 *
 * @author Rawvoid
 */
@Retention(RUNTIME)
@Target({TYPE, FIELD})
public @interface Option {

    /**
     * The name of the plugin option.
     *
     * @return the name of the plugin option
     */
    String name();

    /**
     * The prefix used to denote the plugin option.
     *
     * @return the prefix used to denote the plugin option
     */
    String prefix() default "-";

    /**
     * The delimiter used to separate the option name from the option value.
     *
     * @return the delimiter used to separate the option name from the option value
     */
    String delimiter() default "=";

    /**
     * Whether the plugin option is required.
     *
     * @return whether the plugin option is required
     */
    boolean required() default false;

    /**
     * The default value of the plugin option.
     *
     * @return the default value of the plugin option
     */
    String defaultValue() default "";

    /**
     * The placeholder of the plugin option.
     *
     * @return the placeholder of the plugin option
     */
    String placeholder() default "";

    /**
     * The description of the plugin option.
     *
     * @return the description of the plugin option
     */
    String description() default "";

}
