package mdeos.isos.infra.zkquorum.doe.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.component.UI;
import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mdeos.isos.infra.zkquorum.doe.runtime.ZkQuorumVerifier;
import mdeos.isos.infra.zkquorum.doe.server.ZkClientService;
import mdeos.isos.infra.zkquorum.doe.server.ZkQuorumScaleService;
import mdeos.isos.infra.zkquorum.doe.server.ZkQuorumStatusService;
import mdeos.isos.infra.zkquorum.doe.server.ExpectedNodesStore;
import mdeos.isos.infra.zkquorum.doe.server.ZkQuorumStatusService.ClusterSnapshot;

@Route(value = "", layout = ZkQuorumMainLayout.class)
@PageTitle("Zookeeper Quorum status")
@CssImport("./styles/copilot-hide.css")
@CssImport("./styles/zkquorum.css")
public class ZkQuorumView extends VerticalLayout {

    private final ZkQuorumStatusService statusService;
    private final ZkQuorumScaleService scaleService;
    private final ExpectedNodesStore expectedNodesStore;
    private final ZkClientService zkClientService;

    @ConfigProperty(name = "zkquorum.zk.connect")
    String defaultConnectString;

    private final TextField connectField = new TextField("ZooKeeper connect");
    private final Grid<ZkQuorumVerifier.NodeStatus> statusGrid = new Grid<>();
    private final TextField ensembleStatusField = new TextField();
    private final IntegerField nodesField = new IntegerField("Expected nodes running:");
    private final TextArea logArea = new TextArea("Logs");
    private final Span ensembleLabel = new Span("Ensemble status");
    private Registration pollRegistration;
    private ScheduledExecutorService sessionWatch;
    private final AtomicReference<Boolean> lastConnectState = new AtomicReference<>(null);

    public ZkQuorumView(
        ZkQuorumStatusService statusService,
        ZkQuorumScaleService scaleService,
        ExpectedNodesStore expectedNodesStore,
        ZkClientService zkClientService
    ) {
        this.statusService = statusService;
        this.scaleService = scaleService;
        this.expectedNodesStore = expectedNodesStore;
        this.zkClientService = zkClientService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @PostConstruct
    void init() {
        add(new H2("Zookeeper Quorum status"));
        connectField.setValue(defaultConnectString);
        connectField.addClassName("label-size-match");
        connectField.setWidth("25%");

        ensembleStatusField.setReadOnly(true);
        ensembleStatusField.setWidthFull();
        ensembleStatusField.addClassName("ensemble-status-field");
        ensembleLabel.addClassNames("ensemble-label", "label-size-match");
        VerticalLayout ensembleBlock = new VerticalLayout(ensembleLabel, ensembleStatusField);
        ensembleBlock.setPadding(false);
        ensembleBlock.setSpacing(false);
        ensembleBlock.setWidth("35%");
        HorizontalLayout topBar = new HorizontalLayout(connectField, ensembleBlock);
        topBar.setWidthFull();
        topBar.setFlexGrow(1, connectField);
        topBar.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        topBar.setAlignItems(FlexComponent.Alignment.END);
        add(topBar);

        configureStatusGrid();
        add(statusGrid);

        configureLogs();
        configureNodeCountField();
        Button clearLogs = new Button("Clear logs", event -> logArea.clear());
        clearLogs.addClassName("logs-clear");
        logArea.addClassName("logs-area");
        Button decrementNodes = new Button(VaadinIcon.ANGLE_DOWN.create(), event -> adjustNodeCount(-1));
        Button incrementNodes = new Button(VaadinIcon.ANGLE_UP.create(), event -> adjustNodeCount(1));
        decrementNodes.addClassName("node-count-button");
        incrementNodes.addClassName("node-count-button");
        Button reconfigure = new Button("Reconfigure & Restart", event -> reconfigureEnsemble());
        reconfigure.addClassName("node-count-button");
        HorizontalLayout nodeControls = new HorizontalLayout(nodesField, decrementNodes, incrementNodes, reconfigure);
        nodeControls.setAlignItems(FlexComponent.Alignment.END);
        nodeControls.setSpacing(true);
        add(nodeControls);
        Div logsWrap = new Div(logArea, clearLogs);
        logsWrap.addClassName("logs-wrap");
        logsWrap.setWidthFull();
        add(logsWrap);

        addAttachListener(event -> {
            if (pollRegistration == null) {
                event.getUI().setPollInterval(15000);
                pollRegistration = event.getUI().addPollListener(pollEvent -> refreshStatus());
            }
            startSessionWatch(event.getUI());
            refreshStatus();
        });
        addDetachListener(event -> {
            if (pollRegistration != null) {
                pollRegistration.remove();
                pollRegistration = null;
            }
            stopSessionWatch();
            event.getUI().setPollInterval(-1);
        });
    }


    private void configureStatusGrid() {
        statusGrid.addColumn(ZkQuorumVerifier.NodeStatus::podName).setHeader("Pod");
        statusGrid.addColumn(ZkQuorumVerifier.NodeStatus::containerName).setHeader("Container");
        statusGrid.addColumn(ZkQuorumVerifier.NodeStatus::mode).setHeader("Mode");
        statusGrid.addColumn(ZkQuorumVerifier.NodeStatus::details).setHeader("Details");
        statusGrid.setColumnReorderingAllowed(false);
        statusGrid.setAllRowsVisible(false);
        statusGrid.setPageSize(3);
        statusGrid.addClassName("status-grid");
        statusGrid.setWidthFull();
    }

    private void configureLogs() {
        logArea.setWidthFull();
        logArea.setHeight("135px");
        logArea.setReadOnly(true);
    }

    private void configureNodeCountField() {
        nodesField.setMin(0);
        nodesField.setMax(6);
        nodesField.setValue(expectedNodesStore.get());
        nodesField.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        nodesField.addClassName("label-size-match");
        nodesField.setWidth("240px");
        nodesField.addValueChangeListener(event -> {
            Integer value = event.getValue();
            if (value == null) {
                return;
            }
            int desired = Math.max(0, Math.min(6, value));
            if (desired != value) {
                nodesField.setValue(desired);
                return;
            }
            UI ui = event.getSource().getUI().orElse(null);
            if (ui == null) {
                return;
            }
            expectedNodesStore.set(desired);
            appendLog("Scaling to " + desired + " nodes.");
            CompletableFuture
                .runAsync(() -> scaleService.setDesiredNodeCount(desired))
                .whenComplete((ignored, error) -> ui.access(() -> {
                    if (error != null) {
                        String message = error.getMessage() == null ? "Scale failed" : error.getMessage();
                        appendLog("Scale error: " + message);
                    }
                    refreshStatus();
                }));
        });
    }

    private void adjustNodeCount(int delta) {
        Integer current = nodesField.getValue();
        int next = (current == null ? expectedNodesStore.get() : current) + delta;
        int clamped = Math.max(0, Math.min(6, next));
        if (current == null || clamped != current) {
            nodesField.setValue(clamped);
        }
    }

    private void reconfigureEnsemble() {
        Integer value = nodesField.getValue();
        int desired = value == null ? expectedNodesStore.get() : Math.max(0, Math.min(6, value));
        expectedNodesStore.set(desired);
        UI ui = getUI().orElse(null);
        if (ui == null) {
            return;
        }
        appendLog("Reconfiguring ensemble to " + desired + " nodes.");
        CompletableFuture
            .runAsync(() -> scaleService.reconfigureAndRestart(desired))
            .whenComplete((ignored, error) -> ui.access(() -> {
                if (error != null) {
                    String message = error.getMessage() == null ? "Reconfigure failed" : error.getMessage();
                    appendLog("Reconfigure error: " + message);
                }
                refreshStatus();
            }));
    }


    private void appendLog(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String timestamp = java.time.LocalTime.now().withNano(0).toString();
        String line = "[" + timestamp + "] " + message;
        String existing = logArea.getValue();
        String combined = (existing == null || existing.isBlank()) ? line : existing + "\n" + line;
        logArea.setValue(trimLog(combined, 200));
    }

    private String trimLog(String text, int maxLines) {
        String[] lines = text.split("\\r?\\n");
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        int start = lines.length - maxLines;
        for (int i = start; i < lines.length; i++) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private void refreshStatus() {
        statusGrid.setItems(List.of());
        try {
            ClusterSnapshot snapshot = statusService.getClusterSnapshot();
            List<ZkQuorumVerifier.NodeStatus> statuses = snapshot.nodes();
            updateEnsembleBadge(snapshot.runningNodes());
            if (statuses == null || statuses.isEmpty()) {
                statusGrid.setItems(buildEmptyStatuses());
            } else {
                statusGrid.setItems(trimStatuses(statuses));
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Status refresh failed" : e.getMessage();
            updateEnsembleBadge(0);
            statusGrid.setItems(buildEmptyStatuses());
            appendLog("Status error: " + message);
        }
    }

    private List<ZkQuorumVerifier.NodeStatus> buildEmptyStatuses() {
        int expected = getExpectedNodeCount();
        return java.util.stream.IntStream.rangeClosed(1, expected)
            .mapToObj(i -> new ZkQuorumVerifier.NodeStatus("zoo" + i, "-", "unknown", "No data"))
            .toList();
    }

    private List<ZkQuorumVerifier.NodeStatus> trimStatuses(List<ZkQuorumVerifier.NodeStatus> statuses) {
        int expected = getExpectedNodeCount();
        return statuses.stream()
            .filter(status -> !"missing".equalsIgnoreCase(status.mode()))
            .filter(status -> {
                String name = status.podName();
                if (name == null || !name.startsWith("zoo")) {
                    return false;
                }
                try {
                    int idx = Integer.parseInt(name.substring(3));
                    return idx >= 1 && idx <= expected;
                } catch (NumberFormatException e) {
                    return false;
                }
            })
            .toList();
    }

    private int getExpectedNodeCount() {
        return Math.max(1, expectedNodesStore.get());
    }

    private void updateEnsembleBadge(int runningNodes) {
        ensembleStatusField.removeClassNames("cluster-badge-good", "cluster-badge-warn", "cluster-badge-bad");
        int expected = Math.max(1, expectedNodesStore.get());
        int quorum = expected / 2 + 1;
        if (runningNodes >= quorum) {
            ensembleStatusField.setValue("Operational with " + runningNodes + " nodes");
            ensembleStatusField.addClassName(runningNodes == quorum ? "cluster-badge-warn" : "cluster-badge-good");
            return;
        }
        ensembleStatusField.setValue("Inoperational with " + runningNodes + " nodes");
        ensembleStatusField.addClassName("cluster-badge-bad");
    }

    private void startSessionWatch(UI ui) {
        if (sessionWatch != null) {
            return;
        }
        sessionWatch = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zk-session-watch");
            t.setDaemon(true);
            return t;
        });
        sessionWatch.scheduleAtFixedRate(() -> {
            boolean connected = zkClientService.canConnect(connectField.getValue(), 3, 1);
            Boolean previous = lastConnectState.getAndSet(connected);
            if (previous == null || previous != connected) {
                ui.access(this::refreshStatus);
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void stopSessionWatch() {
        if (sessionWatch != null) {
            sessionWatch.shutdownNow();
            sessionWatch = null;
            lastConnectState.set(null);
        }
    }
}
