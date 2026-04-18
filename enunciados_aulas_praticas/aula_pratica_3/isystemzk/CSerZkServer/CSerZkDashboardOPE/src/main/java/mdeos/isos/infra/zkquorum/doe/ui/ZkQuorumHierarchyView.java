package mdeos.isos.infra.zkquorum.doe.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.UI;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import mdeos.isos.infra.zkquorum.doe.server.ExpectedNodesStore;
import mdeos.isos.infra.zkquorum.doe.server.ZNode;
import mdeos.isos.infra.zkquorum.doe.server.ZkClientService;
import mdeos.isos.infra.zkquorum.doe.server.ZkQuorumStatusService;

@Route(value = "hierarchy", layout = ZkQuorumMainLayout.class)
@PageTitle("Z-node Hierarchy")
public class ZkQuorumHierarchyView extends VerticalLayout {

    private final ZkClientService zkClientService;
    private final ZkQuorumStatusService statusService;
    private final ExpectedNodesStore expectedNodesStore;
    private final ManagedExecutor executor;

    @ConfigProperty(name = "zkquorum.zk.connect")
    String defaultConnectString;

    private final TextField connectField = new TextField("ZooKeeper connect");
    private final TreeGrid<ZNode> treeGrid = new TreeGrid<>();
    private final TextField pathField = new TextField("ZNode path");
    private final TextArea dataField = new TextArea("Data");
    private final Checkbox recursiveDelete = new Checkbox("Delete recursively");
    private final Span quorumWarning = new Span();

    public ZkQuorumHierarchyView(
        ZkClientService zkClientService,
        ZkQuorumStatusService statusService,
        ExpectedNodesStore expectedNodesStore,
        ManagedExecutor executor
    ) {
        this.zkClientService = zkClientService;
        this.statusService = statusService;
        this.expectedNodesStore = expectedNodesStore;
        this.executor = executor;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @jakarta.annotation.PostConstruct
    void init() {
        add(new H2("Z-node hierarchy"));

        connectField.setValue(defaultConnectString);
        connectField.setWidth("60%");

        Button refreshTree = new Button("Refresh z-node tree", event -> refreshTree());
        HorizontalLayout connectBar = new HorizontalLayout(connectField, refreshTree);
        connectBar.setWidthFull();
        connectBar.setFlexGrow(1, connectField);
        connectBar.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        connectBar.setAlignItems(FlexComponent.Alignment.END);
        add(connectBar);

        configureTreeGrid();
        Div treeWrap = new Div(treeGrid);
        treeWrap.addClassName("zk-tree-wrap");
        add(treeWrap);

        configureEditor();

        add(quorumWarning);
        configureWarning();

        addAttachListener(event -> refreshTree());
    }

    private void configureTreeGrid() {
        treeGrid.addHierarchyColumn(ZNode::getName)
            .setHeader("Node")
            .setAutoWidth(false)
            .setFlexGrow(1)
            .setWidth("300px");
        treeGrid.addColumn(new ComponentRenderer<>(node -> {
            String path = node.getPath();
            Span span = new Span("/".equals(path) ? "" : path);
            span.getStyle().set("white-space", "normal");
            span.getStyle().set("overflow-wrap", "anywhere");
            return span;
        }))
            .setHeader("Path")
            .setAutoWidth(false)
            .setFlexGrow(1)
            .setWidth("320px")
            .setResizable(true);
        treeGrid.addColumn(new ComponentRenderer<>(node -> {
            Span span = new Span(node.getData());
            span.getStyle().set("white-space", "normal");
            span.getStyle().set("overflow-wrap", "anywhere");
            span.getStyle().set("line-height", "1.2");
            return span;
        }))
            .setHeader("Data")
            .setAutoWidth(false)
            .setFlexGrow(0)
            .setWidth("900px")
            .setResizable(true);
        treeGrid.setWidthFull();
        treeGrid.setHeight("520px");
        treeGrid.addClassName("zk-tree-grid");
    }

    private void configureEditor() {
        pathField.setWidthFull();
        pathField.addClassName("zk-path-field");
        dataField.setWidthFull();
        dataField.addClassName("zk-data-field");
        dataField.setHeight("75px");

        Button load = new Button("Load", event -> loadData());
        Button createOrUpdate = new Button("Create/Update", event -> createOrUpdate());
        Button delete = new Button("Delete", event -> deleteNode());

        HorizontalLayout actions = new HorizontalLayout(load, createOrUpdate, delete, recursiveDelete);
        actions.setAlignItems(Alignment.BASELINE);

        VerticalLayout editor = new VerticalLayout(pathField, dataField, actions);
        editor.setPadding(false);
        editor.setSpacing(false);
        editor.addClassName("zk-editor");
        add(editor);
    }

    private void configureWarning() {
        quorumWarning.addClassName("quorum-warning");
        quorumWarning.setVisible(false);
        updateWarning();
    }

    private void updateWarning() {
        boolean inoperational = isInoperational();
        if (inoperational) {
            quorumWarning.setText("Ensemble without quorum, Zookeeper refuses reads and writes!...");
            quorumWarning.setVisible(true);
        } else {
            quorumWarning.setVisible(false);
        }
    }

    private boolean isInoperational() {
        ZkQuorumStatusService.ClusterSnapshot snapshot = statusService.getClusterSnapshot();
        int expected = Math.max(1, expectedNodesStore.get());
        int quorum = expected / 2 + 1;
        return snapshot.runningNodes() < quorum;
    }

    private boolean shouldUseOfflineStore() {
        return false;
    }

    private void refreshTree() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        updateWarning();
        String connectString = connectField.getValue();
        if (connectString == null || connectString.isBlank()) {
            appendLog("Connect string is required");
            return;
        }
        boolean useOffline = shouldUseOfflineStore();
        CompletableFuture
            .supplyAsync(() -> useOffline
                ? zkClientService.getTreeOffline("/")
                : zkClientService.getTreeBestEffort(connectString, "/"), executor)
            .whenComplete((root, error) -> ui.access(() -> {
                if (error != null) {
                    appendLog("Tree error: " + resolveMessage(error));
                    return;
                }
                if (root == null) {
                    treeGrid.setDataProvider(new TreeDataProvider<>(new TreeData<>()));
                    appendLog("Tree error: empty root returned");
                    return;
                }
                try {
                    TreeData<ZNode> treeData = new TreeData<>();
                    populateTreeData(treeData, null, root);
                    treeGrid.setDataProvider(new TreeDataProvider<>(treeData));
                    treeGrid.expandRecursively(treeData.getRootItems(), 2);
                } catch (RuntimeException e) {
                    appendLog("Tree render error: " + resolveMessage(e));
                }
            }));
    }

    private void populateTreeData(TreeData<ZNode> treeData, ZNode parent, ZNode node) {
        treeData.addItem(parent, node);
        for (ZNode child : node.getChildren()) {
            populateTreeData(treeData, node, child);
        }
    }

    private void loadData() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        String connectString = connectField.getValue();
        String path = pathField.getValue();
        if (path == null || path.isBlank()) {
            appendLog("Path is required");
            return;
        }
        boolean useOffline = shouldUseOfflineStore();
        CompletableFuture
            .supplyAsync(() -> useOffline
                ? zkClientService.getDataOffline(path.trim())
                : zkClientService.getDataBestEffort(connectString, path.trim()), executor)
            .whenComplete((data, error) -> ui.access(() -> {
                if (error != null) {
                    String message = error.getMessage() == null ? "Load failed" : error.getMessage();
                    appendLog("Load error: " + message);
                    return;
                }
                dataField.setValue(data == null ? "" : data);
            }));
    }

    private void createOrUpdate() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        String connectString = connectField.getValue();
        String path = pathField.getValue();
        if (path == null || path.isBlank()) {
            appendLog("Path is required");
            return;
        }
        boolean useOffline = shouldUseOfflineStore();
        CompletableFuture
            .runAsync(() -> {
                if (useOffline) {
                    zkClientService.createOrUpdateOffline(path.trim(), dataField.getValue());
                } else {
                    zkClientService.createOrUpdateBestEffort(connectString, path.trim(), dataField.getValue());
                }
            }, executor)
            .whenComplete((ignored, error) -> ui.access(() -> {
                if (error != null) {
                    appendLog("Update error: " + error.getMessage());
                    return;
                }
                refreshTree();
                appendLog("Node updated");
            }));
    }

    private void deleteNode() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        String connectString = connectField.getValue();
        String path = pathField.getValue();
        if (path == null || path.isBlank()) {
            appendLog("Path is required");
            return;
        }
        boolean useOffline = shouldUseOfflineStore();
        CompletableFuture
            .runAsync(() -> {
                if (useOffline) {
                    zkClientService.deleteOffline(path.trim(), recursiveDelete.getValue());
                } else {
                    zkClientService.deleteBestEffort(connectString, path.trim(), recursiveDelete.getValue());
                }
            }, executor)
            .whenComplete((ignored, error) -> ui.access(() -> {
                if (error != null) {
                    appendLog("Delete error: " + error.getMessage());
                    return;
                }
                refreshTree();
                appendLog("Node deleted");
            }));
    }


    private void appendLog(String message) {
        Notification.show(message, 4000, Notification.Position.MIDDLE);
    }

    private String resolveMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
