package mdeos.isos.infra.zkquorum.doe.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.component.page.TargetElement;
import com.vaadin.flow.theme.Theme;

@SuppressWarnings("deprecation")
@Theme("isosdesktop")
@Inline(value = "remove-devtools.js", target = TargetElement.BODY)
public class ZkQuorumAppShell implements AppShellConfigurator {

    // No-op: AppShellSettings not available in this Vaadin version.
}
