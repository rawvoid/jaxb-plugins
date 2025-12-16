package io.github.rawvoid.jaxb.plugin;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Option is an annotation that is used to mark plugin options.
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
     * The description of the plugin option.
     *
     * @return the description of the plugin option
     */
    String description() default "";

}
