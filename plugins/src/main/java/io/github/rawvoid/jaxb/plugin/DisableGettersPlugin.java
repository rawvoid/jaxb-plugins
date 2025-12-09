package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

/**
 * Plugin to disable generation of getter methods for fields.
 *
 * @author Rawvoid
 */
public class DisableGettersPlugin extends Plugin {

    public static final String OPTION_NAME = "Xdisable-getters";

    @Override
    public String getOptionName() {
        return OPTION_NAME;
    }

    @Override
    public String getUsage() {
        return String.format("-%s: Disable generation of getter methods for fields", getOptionName());
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
        var classes = outline.getClasses();
        for (var classOutline : classes) {
            var methods = classOutline.implClass.methods();
            methods.removeIf(method -> (method.name().startsWith("get") || method.name().startsWith("is"))
                && method.params().isEmpty());
        }
        return true;
    }
}
