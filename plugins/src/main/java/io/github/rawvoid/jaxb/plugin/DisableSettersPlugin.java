package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

/**
 * Plugin to disable generation of setter methods for fields.
 *
 * @author Rawvoid
 */
public class DisableSettersPlugin extends Plugin {

    public static final String OPTION_NAME = "Xdisable-setters";

    @Override
    public String getOptionName() {
        return OPTION_NAME;
    }

    @Override
    public String getUsage() {
        return String.format("-%s: Disable generation of setter methods for fields", getOptionName());
    }

    @Override
    public boolean run(Outline outline, Options options, ErrorHandler errorHandler) {
        var classes = outline.getClasses();
        for (var classOutline : classes) {
            var methods = classOutline.implClass.methods();
            methods.removeIf(method -> method.name().startsWith("set") && method.params().size() == 1);
        }
        return true;
    }
}
