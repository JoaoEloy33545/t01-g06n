# IESD — Trabalho 01
## Coordenação de Acesso Concorrente a Elementos Serviço Distribuídos

**Unidade curricular:** Integração de Elementos em Sistemas Distribuídos (IESD)  
**Deadline de entrega:** 19 de Abril de 2026 (Moodle — `iesd2526-gxx.zip`)

---

## Stack Tecnológica

| Componente | Escolha |
|---|---|
| Linguagem / Framework | Java + Quarkus |
| Comunicação inter-serviços | gRPC |
| Containerização | Podman / Podman-Desktop |
| Tolerância a falhas | Apache ZooKeeper (eleição de líder no TPLM) |
| MOM (RabbitMQ) | Apenas discutido no relatório — não implementar |
| Service Registry (Consul) | Apenas discutido no relatório — não implementar |

---

## Arquitetura — Modelo X/Open DTP

O sistema implementa o modelo X/Open DTP com os seguintes serviços:

| Serviço | Papel | Descrição |
|---|---|---|
| `Service.Vector` (M instâncias) | Resource Manager (RM) | Gere um vetor de N inteiros; expõe operações transacionais XA |
| `Service.Client` (K instâncias) | Application Program (AP) | Executa transferências concorrentes entre vetores |
| `Service.TM` | Transaction Manager (TM) | Coordena o 2PC entre os RMs |
| `Service.TPLM` | Lock Manager | Gere locks 2PL por `(vecId, index)`; tolerância a falhas via ZooKeeper |
| `Service.Validator` | Verificador | Opera como AP transacional — verifica o invariante de forma consistente |

**Invariante:** `Σ(sum de cada vetor i) = constante`, para todo `i ∈ {1..M}`

**Granularidade dos locks:** `(vecId, index)` — cada posição de cada vetor é um recurso independente.

---

## Fluxo de uma Transferência

```
Client → TM: tx_begin()
Client → TPLM: acquireWriteLock(txId, vecId_src, idx_src)
Client → TPLM: acquireWriteLock(txId, vecId_dst, idx_dst)
Client → Vector_src: read(idx_src)
Client → Vector_dst: read(idx_dst)
Client → Vector_src: write(idx_src, new_value)
Client → Vector_dst: write(idx_dst, new_value)
Client → TM: tx_commit()
  TM → Vector_src: xa_prepare()
  TM → Vector_dst: xa_prepare()
  TM → Vector_src: xa_commit()
  TM → Vector_dst: xa_commit()
  TM → TPLM: releaseLocks(txId)
```

---

## Estado de Implementação

| Fase | Estado |
|---|---|
| 1 — Fundamentação teórica | ✅ Concluída |
| 2 — Arquitetura e design | ✅ Concluída (decisões fechadas) |
| 3 — Service.Vector | ⬜ Por fazer |
| 4 — Service.TPLM | ⬜ Por fazer |
| 5 — Service.TM | ⬜ Por fazer |
| 6 — Service.Client + Validator + Integração | ⬜ Por fazer |
| 7 — ZooKeeper (tolerância a falhas) | ⬜ Por fazer |
| 8 — Benchmarks | ⬜ Por fazer |
| 10 — Relatório | ⬜ Iniciar em paralelo com Fase 3 |

Ver `plano.md` para o detalhe completo de cada fase.
Ver `TODO.md` para detalhes e fluxo de implementação a não esquecer em cada fase.

---

## Estrutura da Pasta

```
Trabalho1/
├── plano.md                        # Plano detalhado de todas as fases
├── TODO.md                         # Tarefas pendentes
├── enunciados_aulas_praticas/      # Código de referência das APs (reutilizar!)
│   ├── aula_pratica_1/             # Estrutura Maven multi-módulo APIM/OPE
│   ├── aula_pratica_2/             # DvectorGrpcService + dvector.proto (base do Service.Vector)
│   └── aula_pratica_3/             # ZkClientService + ZkQuorumOrchestrator (base do TPLM)
├── slides_das_aulas/               # Slides das aulas teóricas (referência)
├── iesd2526sv-trabalho-01-v1.0.0_enunciado.pdf  # Enunciado oficial
└── [diagramas e documentos de análise]
```

---

## Por Onde Começar

A implementação segue a ordem das fases no `plano.md`. A sugestão é começar pela **Fase 3 (Service.Vector)**:

1. Partir de `enunciados_aulas_praticas/aula_pratica_2/.../DvectorGrpcService.java`
2. Estender o `dvector.proto` com operações XA: `xaPrepare(txId)`, `xaCommit(txId)`, `xaRollback(txId)`
3. Seguir a estrutura Maven multi-módulo APIM/OPE de `enunciados_aulas_praticas/aula_pratica_1/`

---

## Pré-requisitos

- Java 21+
- Maven 3.9+
- Quarkus CLI (ou usar o wrapper Maven)
- Podman + Podman-Desktop
