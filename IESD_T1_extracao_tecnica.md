# Extração Técnica — IESD 2025/26 Trabalho 1
**Fonte primária:** *Distributed Transaction Processing: The TX (Transaction Demarcation) Specification*, X/Open CAE Specification C504, 1995.

> ⚠️ **Cobertura dos documentos partilhados:** Apenas a especificação TX foi fornecida. As secções 2 (2PL), 4 (RabbitMQ/ZooKeeper) e 5 (CAP/escalabilidade) têm cobertura **parcial ou nula** nos documentos disponíveis — identificadas com `[FONTE EXTERNA NECESSÁRIA]`.

---

## 1. MODELO X/OPEN DTP

### 1.1 Visão Geral do Modelo
*(p. 1, p. 3–4)*

O modelo X/Open DTP (*Distributed Transaction Processing*) é uma arquitectura de software que permite a múltiplos programas de aplicação partilharem recursos fornecidos por múltiplos *resource managers*, coordenando o seu trabalho em **transacções globais**.

O modelo define **cinco componentes funcionais**:

| Componente | Sigla | Papel |
|---|---|---|
| Application Program | AP | Define fronteiras de transacção e especifica as acções que a constituem |
| Resource Manager | RM | Gere recursos partilhados (ex: DBMS, sistema de ficheiros) |
| Transaction Manager | TM | Atribui identificadores, monitoriza progresso, coordena conclusão e recuperação de falhas |
| Communication Resource Manager | CRM | Controla comunicação entre instâncias distribuídas dentro ou entre domínios TM |
| Communication Protocol | — | Serviços de comunicação subjacentes usados pelos CRMs (OSI TP) |

### 1.2 Papéis Detalhados
*(p. 4)*

**AP (Application Program):**
- Implementa a função desejada pelo utilizador final
- Especifica sequências de operações que envolvem recursos (ex: bases de dados)
- Define início e fim de transacções globais
- Normalmente toma a decisão de *commit* ou *rollback*
- Acede a recursos dentro das fronteiras de transacção

**TM (Transaction Manager):**
- Gere transacções globais e coordena a decisão de as iniciar, confirmar (*commit*) ou reverter (*rollback*)
- Garante conclusão atómica da transacção (*atomic transaction completion*)
- Coordena actividades de recuperação dos RMs após falhas de componentes
- Informa cada RM da existência e dirige a conclusão de transacções globais

**RM (Resource Manager):**
- Gere uma parte definida dos recursos partilhados do computador
- Exemplos: DBMS, método de acesso a ficheiros X/Open ISAM, servidor de impressão
- Estrutura todas as alterações como transacções atómicas e recuperáveis
- Deixa o TM coordenar a conclusão atomicamente com o trabalho de outros RMs
- Cada RM é tipicamente **desconhecedor** do trabalho que outros RMs estão a fazer — é o TM que informa cada RM da existência da transacção global

### 1.3 Interfaces entre Componentes
*(p. 5–6)*

O modelo define **seis interfaces** entre componentes:

| Nº | Interface | Sigla | Descrição |
|---|---|---|---|
| (1) | AP ↔ RM | — | Acesso do AP aos recursos; ex: SQL, ISAM |
| (2) | AP ↔ TM | **TX** | API pela qual o AP coordena gestão de transacções globais com o TM |
| (3) | TM ↔ RM | **XA** | Interface bidirecional; permite ao TM estruturar trabalho dos RMs em transacções globais |
| (4) | TM ↔ CRM | **XA+** | Fluxo de informação de transacção global entre domínios TM |
| (5) | AP ↔ CRM | TxRPC/XATMI/CPI-C | APIs portáteis para comunicação DTP entre APs |
| (6) | CRM ↔ OSI TP | **XAP-TP** | Interface entre CRM e serviços OSI TP |

### 1.4 Interface TX (AP ↔ TM) — Funções C
*(p. 9–10, p. 19–32)*

A interface TX é fornecida pelos TMs e chamada pelos APs. Funções principais:

| Função | Descrição |
|---|---|
| `tx_open()` | Abre todos os RMs ligados ao AP; deve ser chamada antes de qualquer transacção global |
| `tx_close()` | Fecha todos os RMs; falha se o AP estiver em modo transaccional |
| `tx_begin()` | Inicia uma transacção global; coloca o AP em *transaction mode* |
| `tx_commit()` | Dirige o TM a confirmar a transacção global activa |
| `tx_rollback()` | Dirige o TM a reverter a transacção global activa |
| `tx_info()` | Retorna informação sobre a transacção global corrente (XID, estado, características) |
| `tx_set_commit_return()` | Configura o ponto de retorno do `tx_commit()` (após fase 1 ou após 2PC completo) |
| `tx_set_transaction_control()` | Configura modo encadeado (*chained*) ou não-encadeado (*unchained*) |
| `tx_set_transaction_timeout()` | Define timeout para a transacção (em segundos; 0 = desactivado) |

**Convenções de nomenclatura** *(p. 15)*: funções com prefixo `tx_`; constantes e tipos com prefixo `TX_`.

### 1.5 Protocolo 2PC (Two-Phase Commit)
*(p. 11, p. 60–61)*

O `tx_commit()` acciona o **protocolo de commit em duas fases** coordenado pelo TM:

**Fase 1 — Prepare:**
1. O TM chama `xa_end()` em cada RM envolvido (dissocia a thread da transacção global)
2. O TM chama `xa_prepare()` em cada RM associado à transacção global
3. Cada RM vota: preparado para commit (`XA_OK`) ou forçar rollback (`XA_RB*`)

**Fase 2 — Commit/Rollback:**
- Se **todos** os RMs votaram OK: TM regista decisão de commit de forma estável (*stable log*) e chama `xa_commit()` em cada RM
- Se **qualquer** RM vetou: TM chama `xa_rollback()` em todos os RMs que ainda não reverteram, e `tx_commit()` retorna `TX_ROLLBACK` ao AP

**Optimizações** *(p. 60)*:
- Se um RM reporta que não alterou recursos partilhados (`XA_RDONLY`), elimina-se a chamada de Fase 2 a esse RM
- Se apenas **um RM subordinado** está envolvido, o TM pode omitir a Fase 1 (one-phase commit optimisation)

**Mapeamento TX → XA** *(p. 60–61)*:
```
tx_open()    → xa_open()
tx_begin()   → xa_start()
tx_commit()  → xa_end() + xa_prepare() + xa_commit()
              (ou xa_rollback() se algum RM vetar)
tx_rollback() → xa_end() + xa_rollback()
tx_close()   → xa_close()
```

### 1.6 Completação Heurística (Casos de Falha)
*(p. 11, p. 62, p. 65–66)*

Em condições de falha incomuns, um RM pode **unilateralmente** confirmar ou reverter as suas alterações (*heuristic completion*). Os casos reportados ao AP:

| Código de retorno | Significado |
|---|---|
| `TX_MIXED` | Parte da transacção foi confirmada e parte revertida |
| `TX_HAZARD` | Pode ter havido decisões parcialmente conflituantes (incerteza) |
| `TX_COMMITTED` | Transacção heuristicamente confirmada (retornado por `tx_rollback()`) |

**Heurísticas que coincidem com o pedido do AP não são reportadas** como heurísticas — ex: `XA_HEURCOM` durante `tx_commit()` é reportado como `TX_OK`.

Após decisão heurística, o TM deve chamar `xa_forget()` para autorizar o RM a descartar o conhecimento da transacção global.

**Severidade de erros** (hierarquia para múltiplos RMs, p. 67):
```
TX_FAIL > TX_MIXED > TX_HAZARD > TX_ERROR > TX_OUTSIDE/TX_ROLLBACK/TX_COMMITTED > TX_OK
```

### 1.7 Interface XA (TM ↔ RM) — Funções
*(p. 5, p. 59–61)*

O TM chama as funções `xa_*()` nos RMs; os RMs chamam as funções `ax_*()` no TM:

| Função XA (TM→RM) | Propósito |
|---|---|
| `xa_open()` | Abre o RM; resposta `XA_OK` habilita participação em transacções globais |
| `xa_close()` | Fecha o RM |
| `xa_start()` | Associa a thread à transacção global (gera XID) |
| `xa_end()` | Dissocia a thread da transacção global |
| `xa_prepare()` | Voto de preparação para commit (Fase 1) |
| `xa_commit()` | Confirma a transacção (Fase 2) |
| `xa_rollback()` | Reverte a transacção |
| `xa_forget()` | Autoriza o RM a descartar conhecimento de transacção heurística |
| `xa_recover()` | Recuperação após falha |

| Função AX (RM→TM) | Propósito |
|---|---|
| `ax_reg()` | RM regista-se dinamicamente com o TM |

### 1.8 Identificador de Transacção (XID)
*(p. 15–16)*

```c
struct xid_t {
    long formatID;       /* -1 = XID nulo */
    long gtrid_length;   /* 1–64 bytes */
    long bqual_length;   /* 1–64 bytes */
    char data[128];      /* gtrid + bqual concatenados */
};
```

- **gtrid**: identificador global da transacção (global transaction identifier)
- **bqual**: qualificador de ramo (branch qualifier)
- **Unicidade global** baseada nos bits exactos do campo `data`
- Usado para auditoria/debug; o AP **não pode** usar o XID para afectar a coordenação do TM

### 1.9 Thread of Control
*(p. 8)*

O **thread of control** é a entidade, com todo o seu contexto (locks, ficheiros abertos), que controla o processador. O RM associa um pedido de trabalho a uma transacção global porque o AP e o TM fazem chamadas **a partir do mesmo thread de controlo**. Um AP thread pode ter **apenas uma transacção global activa** de cada vez *(p. 53)*.

### 1.10 Tabela de Estados TX
*(p. 51–52)*

| Estado | Descrição |
|---|---|
| S0 | Nenhum RM foi aberto; o AP não pode iniciar transacções globais |
| S1 | RMs abertos, fora de transacção, `transaction_control = TX_UNCHAINED` |
| S2 | RMs abertos, fora de transacção, `transaction_control = TX_CHAINED` |
| S3 | RMs abertos, em transacção, `transaction_control = TX_UNCHAINED` |
| S4 | RMs abertos, em transacção, `transaction_control = TX_CHAINED` |

Transições ilegais retornam `TX_PROTOCOL_ERROR`. `TX_FAIL` coloca o AP num estado indefinido.

### 1.11 Modo Encadeado vs. Não-Encadeado
*(p. 12)*

- **Unchained (default):** quando uma transacção termina, não começa nova até o AP chamar `tx_begin()`
- **Chained:** quando `tx_commit()` ou `tx_rollback()` retornam, uma nova transacção inicia automaticamente no mesmo thread

---

## 2. COORDENAÇÃO DE CONCORRÊNCIA

> ⚠️ `[FONTE EXTERNA NECESSÁRIA]` — A especificação TX não cobre mecanismos de controlo de concorrência internos. O conteúdo abaixo deve ser complementado com slides de aula ou referências IESD sobre 2PL.

### 2.1 Two-Phase Locking (2PL) — Estrutura Geral
*(conceito geral — não coberto nos documentos fornecidos)*

O 2PL define duas fases para aquisição de locks por uma transacção:
- **Fase de crescimento (growing phase):** a transacção adquire locks mas nunca os liberta
- **Fase de encolhimento (shrinking phase):** a transacção liberta locks mas nunca adquire novos

O ponto de transição entre fases é o **lock point**. O teorema do 2PL garante serializabilidade dos escalonamentos.

### 2.2 Tipos de Locks e Compatibilidade
*(conceito geral — não coberto nos documentos fornecidos)*

| Lock solicitado ↓ \ Lock detido → | Partilhado (S) | Exclusivo (X) |
|---|---|---|
| Partilhado (S / Read) | Compatível ✓ | Incompatível ✗ |
| Exclusivo (X / Write) | Incompatível ✗ | Incompatível ✗ |

### 2.3 Deadlock
*(conceito geral — não coberto nos documentos fornecidos)*

- **Detecção:** grafo *wait-for* — ciclo no grafo indica deadlock; resolução por abort de uma vítima
- **Prevenção:** ordenação de timestamps (wound-wait, wait-die)

---

## 3. PROPRIEDADES ACID

### 3.1 Definição das Propriedades ACID no Modelo X/Open
*(p. 7)*

| Propriedade | Definição |
|---|---|
| **Atomicidade** | Os resultados da execução da transacção são todos confirmados ou todos revertidos |
| **Consistência** | Uma transacção concluída transforma um recurso partilhado de um estado válido para outro estado válido |
| **Isolamento** | As alterações a recursos partilhados efectuadas por uma transacção não ficam visíveis fora dela até ao commit |
| **Durabilidade** | As alterações resultantes do commit sobrevivem a falhas subsequentes do sistema ou de suporte de armazenamento |

**Responsabilidades no modelo X/Open** *(p. 7)*:
- O **TM** coordena a **Atomicidade a nível global**
- Cada **RM** é responsável pelas propriedades ACID dos **seus próprios recursos**

### 3.2 Transacções Globais e Atomicidade Distribuída
*(p. 8)*

Quando múltiplos RMs operam em suporte da mesma unidade de trabalho, essa é uma **transacção global**. A confirmação do trabalho interno de um RM depende não apenas de se as suas próprias operações podem ter sucesso, mas também das operações a ocorrer noutros RMs (possivelmente remotos). Se **qualquer operação falhar em qualquer lado**, todos os RMs participantes devem reverter todas as operações efectuadas em nome da transacção global.

### 3.3 Rollback — Condições e Mecanismo
*(p. 7)*

Uma transacção pode ser revertida por:
- Decisão do utilizador humano em resposta a um evento real
- Decisão programática (ex: falha de verificação de conta)
- Falha de um componente do sistema que impede recuperação/comunicação/armazenamento

Cada componente DTP sujeito a controlo transaccional deve conseguir **desfazer o seu trabalho** em qualquer momento em que a transacção seja revertida.

### 3.4 Transacção com Timeout e Estado Rollback-Only
*(p. 12–13, p. 16)*

Quando o timeout expira, o TM marca a transacção como *rollback-only*. Estados possíveis (`TRANSACTION_STATE`):

| Constante | Valor | Significado |
|---|---|---|
| `TX_ACTIVE` | 0 | Transacção activa normalmente |
| `TX_TIMEOUT_ROLLBACK_ONLY` | 1 | Timeout expirou; marcada para rollback |
| `TX_ROLLBACK_ONLY` | 2 | Marcada para rollback (outra razão) |

Se o AP chamar `tx_commit()` numa transacção marcada *rollback-only*, o TM faz rollback da mesma.

### 3.5 Níveis de Isolamento / LLT / Saga
> ⚠️ `[FONTE EXTERNA NECESSÁRIA]` — A especificação TX não detalha níveis de isolamento SQL nem padrões Saga/LLT. Complementar com slides de aula IESD.

---

## 4. MIDDLEWARE E COMUNICAÇÃO ENTRE SERVIÇOS

> ⚠️ `[FONTE EXTERNA NECESSÁRIA]` — A especificação TX não cobre MOM, RabbitMQ, ZooKeeper nem Service Registry. O conteúdo desta secção deve vir integralmente de outras fontes (slides de aula, documentação Apache).

### 4.1 Comunicação entre Domínios TM no Modelo X/Open
*(p. 4–6)*

O modelo X/Open define comunicação entre instâncias distribuídas através de **CRMs** que usam **OSI TP**. Os paradigmas de comunicação AP↔AP suportados são:
- **TxRPC** — Remote Procedure Call transaccional
- **XATMI** — interface transaccional para mensagens e serviços
- **CPI-C** — interface de programação de comunicações

A interface **XA+** *(p. 5)* é um superset do XA, usada entre TM e CRM para propagar informação de transacção global e protocolos de commit entre ramos de transacção em domínios TM distintos. É **invisível ao AP**.

### 4.2 Pontos de Discussão para o Relatório — RabbitMQ como MOM
*(a fundamentar com documentação RabbitMQ/AMQP e slides IESD)*

A utilização de RabbitMQ entre serviços no Trabalho 1 é justificável pelos seguintes aspectos teóricos a desenvolver:
- Desacoplamento temporal e espacial entre produtores e consumidores
- Padrões *publish/subscribe* e *point-to-point* (queues vs. exchanges)
- Comparação com chamadas síncronas (RPC/REST): tolerância a falhas temporárias, buffering
- Garantias de entrega: *at-most-once*, *at-least-once*, *exactly-once* (com confirmações)

### 4.3 Pontos de Discussão para o Relatório — Apache ZooKeeper
*(a fundamentar com documentação ZooKeeper e slides IESD)*

Aspectos teóricos a desenvolver para justificar uso de ZooKeeper:
- Z-nodes: efémeros vs. persistentes; watches
- Eleição de líder distribuída
- Gestão de configuração e descoberta de serviços
- Garantias de consistência do ZooKeeper (linearizabilidade para escritas)
- Tolerância a falhas: quórum, modelo ZAB (ZooKeeper Atomic Broadcast)

---

## 5. ESCALABILIDADE E QUALIDADE DE SERVIÇO

> ⚠️ `[FONTE EXTERNA NECESSÁRIA]` — A especificação TX não cobre CAP theorem, BASE, métricas de desempenho nem estratégias de replicação.

### 5.1 Características de Desempenho Relevantes do Modelo TX
*(p. 11–12)*

**Retorno antecipado do commit** (`TX_COMMIT_DECISION_LOGGED`): o `tx_commit()` retorna após o registo da decisão de commit na Fase 1, antes de a Fase 2 completar. Motivação: reduzir tempo de resposta ao utilizador. Risco: se ocorrer resultado heurístico, o AP não é notificado via código de retorno.

**Transaction Timeout** *(p. 12)*: mecanismo para controlar recursos gastos por uma transacção. Valor 0 desactiva; o intervalo é reiniciado em cada `tx_begin()` e em cada início de transacção em modo *chained*.

### 5.2 Tolerância a Falhas no Modelo X/Open
*(p. 4, p. 7)*

- O **TM** coordena actividades de **recuperação dos RMs** quando necessário (ex: após falha de componente)
- O sistema deve conseguir **referenciar uma transacção** que abrange todo o trabalho feito em qualquer parte do sistema
- A decisão de commit/rollback deve ter **efeito uniforme** em todo o sistema DTP

---

## 6. CONCEITOS ADICIONAIS RELEVANTES PARA DISCUSSÃO TEÓRICA

*(Conteúdo da especificação TX não encaixado nas secções anteriores mas útil para fundamentação teórica)*

### 6.1 Portabilidade como Objectivo de Design
*(p. 1, p. 53)*

O modelo X/Open visa três objectivos de portabilidade/interoperabilidade:
1. **Portabilidade** do código-fonte do AP para qualquer ambiente X/Open que ofereça as APIs
2. **Intercambiabilidade** de TMs, RMs e CRMs de vários fornecedores
3. **Interoperabilidade** de TMs, RMs e CRMs diversos na mesma transacção global

O design da interface TX (abertura/fecho de RMs via TM, e não via chamadas nativas RM) **evita que o AP inclua chamadas específicas ao RM**, protegendo a portabilidade *(p. 10)*.

### 6.2 Registo Dinâmico de RMs
*(p. 60)*

RMs com registo dinâmico não recebem `xa_start()` do TM no início da transacção. Em vez disso, chamam `ax_reg()` apenas quando o AP os contacta através da interface nativa para pedir trabalho real. Isto optimiza o protocolo evitando envolvimento de RMs que não são usados numa dada transacção.

### 6.3 Transacções Locais vs. Globais
*(p. 20)*

Um AP pode estar a participar em trabalho **fora de qualquer transacção global** com um ou mais RMs (*RM local transaction*). Nesse estado, `tx_begin()` falha com `TX_OUTSIDE`. Todo esse trabalho local deve completar antes de se poder iniciar uma transacção global.

### 6.4 Requisitos de Implementação
*(p. 53–54)*

- **AP:** deve usar um TM e delegar-lhe a responsabilidade de controlar e coordenar cada transacção global; não está envolvido no protocolo de commit nem no processo de recuperação
- **RM:** a demarcação transaccional com um RM específico é coordenada pelo TM associado, usando a interface XA
- **TM:** deve suportar interacção com RMs via XA; deve publicar comportamento relativo à característica `commit_return` (valor por omissão e settings suportados)

### 6.5 Modo Assíncrono XA
*(p. 63)*

O XA suporta um **modo de chamada assíncrona** que permite ao TM agendar actividades concorrentes em diferentes RMs de forma eficiente. O código `XAER_ASYNC` informa o TM de que excedeu o limite de pedidos assíncronos pendentes do RM. Este modo é transparente ao AP.

---

## RESUMO DE LACUNAS — O QUE FALTA DOCUMENTAR

| Secção | Tópico | Estado |
|---|---|---|
| 2 | Two-Phase Locking (2PL), granularidade, deadlock | ❌ Não coberto nos documentos fornecidos |
| 3 | Níveis de isolamento SQL (read uncommitted → serializable) | ❌ Não coberto |
| 3 | Long-Lived Transactions / Saga / compensating transactions | ❌ Não coberto |
| 4 | RabbitMQ — padrões MOM, AMQP, garantias de entrega | ❌ Não coberto |
| 4 | Apache ZooKeeper — z-nodes, watches, eleição de líder, ZAB | ❌ Não coberto |
| 4 | Service Registry (Consul) — registo e descoberta | ❌ Não coberto |
| 5 | CAP Theorem, BASE vs. ACID | ❌ Não coberto |
| 5 | Modelos de replicação e consistência | ❌ Não coberto |
| 5 | Métricas de benchmarking transaccional | ❌ Não coberto |
| 5 | Contentorização com Podman | ❌ Não coberto |
