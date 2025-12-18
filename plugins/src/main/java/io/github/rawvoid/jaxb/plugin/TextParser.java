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
