package io.github.rawvoid.jaxb.plugin;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Option is an annotation that is used to mark plugin options.
 *
 * @author Rawvoid
 */
@Retention(RUNTIME)
@Target({FIELD})
public @interface Option {

    /**
     * The name of the plugin option.
     *
     * @return the name of the plugin option
     */
    String name();

    /**
     * Whether the plugin option is required.
     *
     * @return whether the plugin option is required
     */
    boolean required() default false;

    /**
     * The description of the plugin option.
     *
     * @return the description of the plugin option
     */
    String description() default "";

}
