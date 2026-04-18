# CSerZkServer

Quarkus DOE module that starts and stops a 4-node ZooKeeper ensemble on Podman and provides a Vaadin UI.

## Podman quick start

```
podman play kube src/main/resources/podman/zk-quorum.yaml
```

The client port is exposed from `zoo1` on `localhost:2181`.

## Build

From repo root (recommended):
```
mvn clean install
```

From a submodule (auto-build dependencies):
```
mvn -pl . -am clean install
```

## Quarkus behavior

On application startup, the module runs `podman play kube` and waits until exactly
one leader and three followers are detected. On shutdown it runs
`podman kube down` to remove the pods.

## UI

Start dev mode and open:
```
http://localhost:5086/
```
