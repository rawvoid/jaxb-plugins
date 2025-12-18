package io.github.rawvoid.jaxb.plugin;

import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;

/**
 * Plugin to disable generation of setter methods for fields.
 *
 * @author Rawvoid
 */
@Option(name = "Xdisable-setters", description = "Disable generation of setter methods for fields")
public class DisableSettersPlugin extends AbstractPlugin {

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
