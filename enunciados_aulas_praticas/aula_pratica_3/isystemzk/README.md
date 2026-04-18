# isystemzk (0.8.0) - Projeto Principal

Projeto base do `ISystemZk`, agora com uma única sub-área: `CSerZkServer`.

- **CSerZkServer** - Módulo pai do ZooKeeper Server.
- **CSerZkServerOPE** - Orquestração de quorum ZooKeeper (Podman).
- **CSerZkDashboardOPE** - Dashboard do quorum ZooKeeper (Vaadin/Quarkus).

## Requisitos

- Java versão 25
- Maven (testado na versão 3.9.9)

## Geração dos Artefactos

Para apagar todos os artefactos gerados:
```
mvn clean
```

Para gerar os artefactos:
```
mvn package
```

Opcionalmente pode-se importar o projeto Maven num IDE (Net Beans, Eclipse ou IntelliJ IDEA).
