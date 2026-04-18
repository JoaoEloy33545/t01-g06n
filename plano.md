# Plano de Trabalho — IESD Trabalho 01
## Coordenação de Acesso Concorrente a Elementos Serviço Distribuídos

**Entrega:** 19 de abril de 2026 (Moodle)
**Formato:** `iesd2526-gxx.zip` (projeto + relatório PDF)

---

## Decisões Tecnológicas (fechadas)

| Decisão | Escolha |
|---|---|
| Linguagem / Framework | Java + Quarkus |
| Protocolo de comunicação | gRPC |
| Containerização | Podman / Podman-Desktop |
| Tolerância a falhas | Apache ZooKeeper (eleição de líder no TPLM e onde mais fizer sentido) |
| MOM | **Não implementar** — apenas discutir a aplicabilidade no relatório |
| Service Registry (Consul) | **Não implementar** — apenas discutir no relatório |
| Comparação de tecnologias | Teórica no relatório; benchmarks variando K (clientes) e M (vetores) |

---

## Exemplos das Aulas Práticas (a reutilizar)

| Ficheiro / Componente | Aula | Reutilização |
|---|---|---|
| `aula_pratica_2/.../DvectorGrpcService.java` | AP2 | Base do `Service.Vector` — já tem `read`, `write`, `sumVector`, stub de `invariantCheck` |
| `aula_pratica_2/.../dvector.proto` | AP2 | Base do contrato gRPC do `Service.Vector` (estender com operações XA) |
| `aula_pratica_2/.../Calculator.proto` | AP2 | Exemplo de `.proto` com múltiplos tipos de mensagens |
| `aula_pratica_2/.../ConsulRegistrar.java` | AP2 | Padrão de registo de serviço (não usar Consul, mas o padrão é útil) |
| `aula_pratica_2/.../GenericConsulRegistrar.java` | AP2 | Idem |
| `aula_pratica_3/.../ZkClientService.java` | AP3 | Base da integração ZooKeeper: connect, create, getData, watcher, retry |
| `aula_pratica_3/.../ZkQuorumOrchestrator.java` | AP3 | Orquestração do cluster ZooKeeper em Podman |
| `aula_pratica_1/iesd-v2526/` | AP1 | Estrutura Maven multi-módulo APIM/OPE — padrão de projeto a seguir |

Localização base: `enunciados_aulas_praticas/`

---

## Estado das Fases

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
| 9 — Opcionais | ⬜ A decidir |
| 10 — Relatório | ⬜ Iniciar em paralelo com Fase 3 |
| 11 — Entrega | ⬜ Por fazer |

---

## Fase 1 — Fundamentação Teórica e Decisões de Arquitetura ✅

**Estado:** Concluída. Decisões tomadas (ver tabela acima).

Tópicos revistos: modelo X/Open DTP, 2PC, 2PL, ACID vs. LLT/Saga, Apache ZooKeeper, MOM, Consul.

Especificações oficiais disponíveis:
- `Distributed Transaction Processing - The TX Specification.pdf` — interface AP ↔ TM (`tx_begin`, `tx_commit`, `tx_rollback`, etc.)
- `Distributed Transaction Processing - The XA Specification.pdf` — interface TM ↔ RM (`xa_prepare`, `xa_commit`, `xa_rollback`, etc.)

---

## Fase 2 — Arquitetura e Design do Sistema ✅

**Estado:** Concluída.

**Componentes definidos:**

| Serviço | Papel X/Open | Descrição |
|---|---|---|
| `Service.Vector` (M instâncias) | Resource Manager (RM) | Gere um vetor de N inteiros; expõe operações transacionais |
| `Service.Client` (K instâncias) | Application Program (AP) | Executa transferências concorrentes entre vetores |
| `Service.TM` | Transaction Manager (TM) | Coordena o 2PC entre os RMs |
| `Service.TPLM` | Coordenador de concorrência | Gere locks 2PL por `(vecId, index)`; tolerância a falhas via ZooKeeper |
| `Service.Validator` | Verificador do invariante | Opera como AP transacional — adquire read locks em todos os elementos antes de somar |

**Invariante:** `Σ(sum de cada vetor i) = constante`, para todo `i ∈ {1..M}`

**Verificação do invariante:**
- Antes de `tx_begin()` de qualquer cliente (estado inicial / sanidade)
- Após `xa_commit()` concluído em todos os RMs (caminho de sucesso)
- Após `xa_rollback()` concluído em todos os RMs (caminho de abort)
- **Importante:** a verificação é ela própria uma operação transacional — o `Service.Validator` adquire read locks em todos os elementos via TPLM antes de calcular o somatório, garantindo uma leitura consistente mesmo em execução concorrente

**Granularidade dos locks:** `(vecId, index)` — cada posição de cada vetor é um recurso independente.

**Fluxo de uma transferência:**
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

## Fase 3 — Implementação do Service.Vector (Resource Manager)

**Objetivo:** Ter o RM base funcional com suporte a operações transacionais.

**Ficheiros de suporte:**
- `enunciados_aulas_praticas/aula_pratica_2/isysOaciCServices/CSerDvector/CSerDvectorGrpc/CSerDvectorGrpcOPE/src/main/java/isos/isysiesd/dvimpl/DvectorGrpcService.java` — implementação base a estender
- `enunciados_aulas_praticas/aula_pratica_2/isysOaciCServices/CSerDvector/CSerDvectorGrpc/CSerDvectorGrpcAPIM/src/main/proto/dvector.proto` — contrato gRPC base

**Tarefas:**

3.1. Partir do `DvectorGrpcService` existente (já tem `read`, `write`, `sumVector`)
3.2. Estender o `.proto` com operações XA: `xaPrepare(txId)`, `xaCommit(txId)`, `xaRollback(txId)`
3.3. Implementar staging area por transação: write temporário que só persiste no `xaCommit`
3.4. Implementar `getSum()` para uso pelo `Service.Validator`
3.5. Suporte a múltiplas instâncias (M vetores) via variável de ambiente ou config
3.6. Containerizar com Podman (reutilizar `Dockerfile.jvm` do exemplo da AP2)
3.7. Testar isoladamente: leituras, escritas, prepare/commit/rollback sem TM real

**Estrutura de projeto:** seguir o padrão APIM/OPE da `aula_pratica_1`

---

## Fase 4 — Implementação do Service.TPLM (Two-Phase Lock Manager)

**Objetivo:** Garantir serializabilidade via 2PL com tolerância a falhas.

**Ficheiros de suporte:**
- `enunciados_aulas_praticas/aula_pratica_3/isystemzk/CSerZkServer/CSerZkServerOPE/src/main/java/mdeos/isos/infra/zkquorum/doe/server/ZkClientService.java` — cliente ZooKeeper base
- `enunciados_aulas_praticas/aula_pratica_3/isystemzk/CSerZkServer/CSerZkServerOPE/src/main/java/mdeos/isos/infra/zkquorum/doe/runtime/ZkQuorumOrchestrator.java` — orquestração do cluster

**Tarefas:**

4.1. Definir contrato gRPC: `acquireLock(txId, vecId, index, lockType)`, `releaseLocks(txId)`, `getStatus()`
4.2. Implementar tabela de locks em memória: recurso `(vecId, index)` → lista de holders e tipo
4.3. Lógica de compatibilidade: read-read compatível; read-write e write-write bloqueiam
4.4. Fila de espera para locks em conflito
4.5. Deteção de deadlock por timeout (configurável, default: 5s) — abortar transação mais jovem
4.6. Integrar ZooKeeper para eleição de líder:
   - Usar z-nodes efémeros: `/tplm/leader` criado pelo TPLM ativo
   - Quando o líder falha, ZooKeeper deteta (z-node desaparece) e um standby assume
   - Reutilizar `ZkClientService` da AP3
4.7. Containerizar com Podman
4.8. Testar cenários de conflito entre 2 transações concorrentes
4.9. Testar failover: matar instância líder e verificar que o standby assume

---

## Fase 5 — Implementação do Service.TM (Transaction Manager)

**Objetivo:** Coordenar transações distribuídas segundo o modelo X/Open com 2PC.

**Referências:**
- TX Specification: funções `tx_begin`, `tx_commit`, `tx_rollback` (mapear para gRPC)
- XA Specification: funções `xa_prepare`, `xa_commit`, `xa_rollback` (o TM chama nos RMs)

**Tarefas:**

5.1. Definir contrato gRPC (interface TX para os clientes): `beginTx()`, `commitTx(txId)`, `abortTx(txId)`, `getTxStatus(txId)`
5.2. Implementar gestão de transações: geração de `txId` único, registo de RMs participantes
5.3. Implementar 2PC:
   - Fase 1 (Prepare): chamar `xaPrepare(txId)` em todos os RMs participantes; se algum votar NO → abort global
   - Fase 2 (Commit/Abort): chamar `xaCommit` ou `xaRollback` em todos os RMs
5.4. Implementar log de transações persistente (ficheiro ou estrutura em disco) para recuperação após falha do TM
5.5. Coordenar com TPLM: o TM chama `releaseLocks(txId)` no TPLM após commit/rollback completo
5.6. Containerizar com Podman
5.7. Testar com 1 cliente, 2 vetores; incluir cenário de RM a votar NO no prepare

---

## Fase 6 — Service.Client, Service.Validator e Integração

**Objetivo:** Sistema completo a funcionar com K clientes, M vetores e verificação do invariante.

**Tarefas:**

6.1. Implementar `Service.Client`:
   - Ciclo: `tx_begin()` → `acquireLock()` → `read()` → compute → `write()` → `tx_commit()`
   - Suportar transferências dentro do mesmo vetor e entre vetores diferentes
   - Simular concorrência: K instâncias a correr em paralelo

6.2. Implementar `Service.Validator`:
   - Executa como AP transacional: `tx_begin()` → `acquireReadLock()` em todos os (vecId, index) → `getSum()` em cada vetor → `tx_commit()`
   - Comparar soma total com o valor esperado (invariante)
   - Invocar nos 3 momentos definidos na Fase 2: antes de cada transação de cliente, após commit, após rollback

6.3. Integração completa:
   - Testar com K=2 clientes, M=2 vetores, N=4 elementos por vetor
   - Verificar que o invariante nunca é violado após commit
   - Verificar que o rollback restaura o estado

6.4. Criar `podman-compose.yml` unificado com todos os serviços

---

## Fase 7 — Tolerância a Falhas (ZooKeeper)

**Objetivo:** Validar a resiliência do TPLM com failover automático.

**Ficheiros de suporte:**
- `enunciados_aulas_praticas/aula_pratica_3/` — cluster ZooKeeper completo em Podman, já configurado

**Tarefas:**

7.1. Subir cluster ZooKeeper com 3 nós (reutilizar configuração da AP3)
7.2. Confirmar que o TPLM (implementado na Fase 4) inicia a eleição de líder corretamente
7.3. Testar failover: matar a instância líder do TPLM durante uma transação em curso
7.4. Verificar que o novo líder assume e que a transação é abortada/relançada corretamente
7.5. Documentar o comportamento observado (para o relatório)

---

## Fase 8 — Testes de Desempenho

**Objetivo:** Medir throughput e latência para a secção de avaliação do relatório.

**Tarefas:**

8.1. Criar script de benchmark (Java ou bash): lançar N transações concorrentes e medir tempo total
8.2. Variar K (número de clientes): K=1, 2, 4, 8
8.3. Variar M (número de vetores): M=1, 2, 4
8.4. Medir: throughput (tx/s), latência média, taxa de aborts/deadlocks
8.5. Produzir tabelas e gráficos para o Cap. 3 do relatório

---

## Fase 9 — Componentes Opcionais

**Objetivo:** Implementar elementos de valorização se houver tempo.

9.1. (Opcional) Service Registry com Consul — transparência à localização
9.2. (Opcional) Implementar um serviço numa segunda linguagem (Python ou .NET) para demonstrar interoperabilidade gRPC
9.3. (Opcional) Dashboard de monitorização do estado do sistema

**Decisão:** só avançar após Fase 6 concluída.

---

## Fase 10 — Relatório (máx. 15 páginas úteis)

**Iniciar em paralelo com a Fase 3** — as secções teóricas podem ser redigidas antes do código estar terminado.

**Cap. 1 — Introdução** (~1 página)
- Síntese e estrutura do documento

**Cap. 2 — Conhecimentos na fundamentação** (~4 páginas)
- Modelo X/Open DTP (AP, TM, RM, interfaces TX e XA)
- Two-Phase Commit (2PC)
- Two-Phase Locking (2PL) — incluir discussão sobre a verificação transacional do invariante como exemplo concreto de 2PL
- Propriedades ACID; confrontação com LLT/Saga
- Apache ZooKeeper e tolerância a falhas
- Aplicabilidade do MOM como alternativa ao RPC/REST (discutir, não implementar)
- Consul como service registry (discutir, não implementar)

**Cap. 3 — Arquitetura e demonstrador** (~7 páginas)
- Diagrama de arquitetura
- Descrição de cada serviço e decisões de design
- Fluxo completo de uma transação (diagrama de sequência)
- Estratégia de tolerância a falhas (ZooKeeper + log TM)
- Dificuldades com múltiplos elementos serviço independentes *(obrigatório)*
- Gestão do ciclo de vida dos elementos de infraestrutura *(obrigatório)*
- Resultados de desempenho (tabelas e gráficos da Fase 8)
- Cenário de escalabilidade

**Cap. 4 — Conclusões e desafios em aberto** (~2 páginas)
- Resumo, dificuldades, melhorias possíveis, referências

---

## Fase 11 — Entrega

11.1. Organizar estrutura final do projeto com README de execução
11.2. Criar `iesd2526-gxx.zip` (código + relatório PDF)
11.3. Submeter no Moodle até 19 de abril de 2026

---

## Dependências entre Fases

```
Fase 1 ──→ Fase 2 ──→ Fase 3 (Vector)
                           │
                      Fase 4 (TPLM + ZooKeeper base)
                           │
                      Fase 5 (TM)
                           │
                      Fase 6 (Client + Validator + Integração)
                           │
              ┌────────────┼────────────┐
         Fase 7         Fase 8       Fase 9
       (ZK failover)  (Benchmark)  (Opcionais)
              └────────────┼────────────┘
                      Fase 10 (Relatório) ← iniciar em paralelo com Fase 3
                           │
                      Fase 11 (Entrega)
```
