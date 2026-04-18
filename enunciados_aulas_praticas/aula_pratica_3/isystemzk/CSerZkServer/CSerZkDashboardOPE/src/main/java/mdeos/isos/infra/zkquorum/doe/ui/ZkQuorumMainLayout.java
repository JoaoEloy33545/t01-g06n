package mdeos.isos.infra.zkquorum.doe.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.router.RouterLink;

public class ZkQuorumMainLayout extends Div implements RouterLayout {
    private final Main content = new Main();

    public ZkQuorumMainLayout() {
        addClassName("main-layout");
        setSizeFull();

        Div frame = new Div();
        frame.addClassName("main-layout-frame");

        Div body = new Div(buildDrawer(), buildContentShell());
        body.addClassName("main-layout-body");

        frame.add(buildHeader(), body);
        add(frame);
    }

    @Override
    public void showRouterLayoutContent(HasElement routerLayoutContent) {
        content.removeAll();
        if (routerLayoutContent instanceof Component component) {
            if (component instanceof HasSize sized) {
                sized.setSizeFull();
            }
            content.add(component);
        }
    }

    private Header buildHeader() {
        H1 title = new H1("ZooKeeper Quorum Console");
        title.addClassName("app-title");

        Span subtitle = new Span("Reliable Ensemble Operations");
        subtitle.addClassName("app-subtitle");

        Div titles = new Div(title, subtitle);
        titles.addClassName("app-titles");

        Div brand = new Div(titles);
        brand.addClassName("app-brand");

        Image iselLogo = new Image("images/isel-cropped.svg", "ISEL");
        iselLogo.addClassNames("partner-logo", "isel-logo");

        Div centerLogo = new Div(iselLogo);
        centerLogo.addClassName("app-center-logo");

        Image textLogo = new Image("images/MDEOS-enterprise-blue.png", "MDEOS enterprise");
        textLogo.addClassNames("partner-logo", "app-text-logo");

        Div partners = new Div(textLogo);
        partners.addClassName("app-partners");

        Header header = new Header(brand, centerLogo, partners);
        header.addClassNames("app-header", "main-layout-header");
        return header;
    }

    private Div buildDrawer() {
        Span menuTitle = new Span("Zookeeper Server");
        menuTitle.addClassName("drawer-title");

        RouterLink dashboard = new RouterLink("Ensemble", ZkQuorumView.class);
        RouterLink hierarchy = new RouterLink("Z-node Hierarchy", ZkQuorumHierarchyView.class);
        dashboard.addClassName("nav-link");
        hierarchy.addClassName("nav-link");

        Nav nav = new Nav(dashboard, hierarchy);
        nav.addClassName("app-drawer");

        Div shell = new Div(menuTitle, nav);
        shell.addClassNames("drawer-shell", "main-layout-drawer");
        return shell;
    }

    private Div buildContentShell() {
        content.addClassName("main-layout-content");
        content.setSizeFull();

        Div shell = new Div(content);
        shell.addClassName("main-layout-content-shell");
        return shell;
    }
}
