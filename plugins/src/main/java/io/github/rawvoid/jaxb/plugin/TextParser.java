package io.github.rawvoid.jaxb.plugin;

/**
 *
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
