package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

/**
 * Plugin to disable generation of getter methods for fields.
 *
 * @author Rawvoid
 */
@Option(name = "Xdisable-getters", description = "Disable generation of getter methods for fields")
public class DisableGettersPlugin extends AbstractPlugin {

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
