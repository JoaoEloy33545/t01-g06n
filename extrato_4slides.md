# Extração de Conteúdo Técnico — IESD Trabalho 1 (2025/26)

> **Fontes utilizadas:**
> - **[XOpen]** `04-X-OPEN-and-services-v1_0_4.pdf` (slides 1–39)
> - **[Locks]** `05-Coordination-locks-v1_0_3.pdf` (slides 1–12)
> - **[ZK]** `06-Distributed_Coordination-Zookeeper-v1_0_5.pdf` (slides 1–27)
> - **[ISoS]** `03_2-The_ISoS-Framework-v1_0_1.pdf` (slides 1–15)

---

## 1. MODELO X/OPEN DTP

### 1.1 Enquadramento do Problema

O problema motivador é o acesso concorrente a múltiplos serviços com estado (ex.: `service.vect01`, `service.vect02`) por parte de vários clientes concorrentes (`service.cliente.1`, `.2`, `.n`). Cada cliente executa uma sequência de operações read/write sobre dois vetores com um **invariante** — a soma total dos elementos deve permanecer constante (`Σ vector[i] = 1379`). Sem coordenação, operações intercaladas violam o invariante. A solução passa por introduzir um **Transaction Manager (TM)** e um **Transaction Processing Lock Manager (TPLM)** na arquitetura. ([XOpen] slides 3–5, 13)

### 1.2 Standard X/Open DTP

- **Standard:** ISO/IEC 14834:1996 — *Distributed Transaction Processing: The XA Specification* ([XOpen] slide 6)
- **Consórcio X/Open (Open Group):** facilitador de interoperabilidade global com base em standards abertos; define o *Common Applications Environment (CAE)*. ([XOpen] slide 25)

### 1.3 Definições Fundamentais

| Conceito | Definição |
|---|---|
| **Transação** | Unidade de trabalho computacional, constituída por duas ou mais tarefas, cuja execução se pretende atómica: todas concluem com êxito ou nenhuma é realizada. |
| **Transação global (Global Transaction)** | Trabalho realizado por um conjunto de **Resource Managers (RM)** enquanto participantes numa unidade atómica. |
| **Transação distribuída** | Transação global que envolve participantes distribuídos. |
| **Commitment** | Ato que desencadeia o fim da transação tornando permanentes todas as alterações dos RMs. |
| **Rollback** | Ato que desencadeia o fim da transação anulando todas as alterações dos RMs (retorno ao estado anterior). |
| **Transaction Branch** | Quando a aplicação envolve múltiplos processos ou aplicações remotas, uma transação global pode ter vários *branches* (ramos). O **XID** identifica uniquamente a transação global e o seu branch. |

([XOpen] slides 6–8, 21)

### 1.4 Papéis no Modelo X/Open DTP

| Papel | Responsabilidade |
|---|---|
| **AP (Application Program)** | Estabelece a fronteira da transação; associa um conjunto de operações a executar atomicamente. Implementado em linguagem STDL, Java, C#, etc. |
| **RM (Resource Manager)** | Gere um conjunto de recursos (bases de dados, ficheiros, serviços). Expõe uma *RM Interface Nativa* (API standard). |
| **TM (Transaction Manager)** | Gere transações com identificador único (XID); monitoriza-as; responsável pela conclusão, coordenação de falhas e recuperação. |
| **CRM (Communication Resource Manager)** | Gere a comunicação entre diferentes Transaction Managers (necessário em ambientes distribuídos multi-nó). |

([XOpen] slides 9, 23)

No cenário dos vetores, `SerVectorCli` mapeia para AP, `SerVector` para RM, e o TM é um elemento serviço autónomo. ([XOpen] slides 10, 13, 23)

### 1.5 Interfaces entre Componentes

| Interface | Especificação | Descrição |
|---|---|---|
| AP ↔ RM | **XA** | Application Program acede diretamente ao RM |
| AP ↔ TM | **TX** (*Transaction Demarcation Specification*) | Demarcação de fronteiras de transação |
| RM ↔ TM | **XA** | Protocolo de preparação e commit entre RM e TM |
| TM ↔ CRM | **XA+** | Extensão para comunicação entre TMs |
| AP ↔ CRM | **TxRPC** e outras infra-estruturas | Chamadas remotas transacionais |
| CRM ↔ OSI TP | **XAP-TP** | Interface com a camada de transporte OSI |

([XOpen] slides 12, 17)

### 1.6 Interface TX (AP ↔ TM)

| Operação | Descrição |
|---|---|
| `tx_begin` | Inicia uma transação global |
| `tx_open` | Abre um conjunto de Resource Managers |
| `tx_close` | Encerra um conjunto de Resource Managers |
| `tx_commit` | Commit da transação global |
| `tx_rollback` | Rollback da transação global |
| `tx_info` | Retorna informação sobre a transação global |
| `tx_set_commit_return` | Ativa/desativa retorno antecipado de `tx_commit` |
| `tx_set_transaction_control` | Ativa/desativa encadeamento de transações |
| `tx_set_transaction_timeout` | Define o tempo máximo de execução de uma transação |

([XOpen] slide 14)

### 1.7 Interface XA (AP ↔ RM e RM ↔ TM)

| Operação | Direção | Descrição |
|---|---|---|
| `xa_open` | TM → RM | RM abre e prepara-se para transações distribuídas |
| `xa_close` | TM → RM | TM fecha um RM atualmente aberto |
| `xa_start` | TM → RM | TM informa RM que uma aplicação pode iniciar trabalho |
| `xa_end` | TM → RM | TM informa RM que o AP terminou/suspendeu trabalho numa transação |
| `xa_prepare` | TM → RM | TM pede ao RM para se preparar para commit (fase 1 do 2PC) |
| `xa_commit` | TM → RM | TM ordena commit do trabalho associado ao XID |
| `xa_rollback` | TM → RM | TM ordena rollback do trabalho realizado |
| `xa_recover` | TM → RM | TM obtém transações em estado *prepared* (para recovery) |
| `xa_forget` | TM → RM | TM instrui RM a esquecer uma transação completada heuristicamente |
| `xa_complete` | TM → RM | TM aguarda conclusão de operação assíncrona |
| `ax_reg` | RM → TM | RM informa TM que está prestes a iniciar trabalho |
| `ax_unreg` | RM → TM | RM informa TM que sai da associação |

([XOpen] slide 16)

### 1.8 Estrutura XID

O identificador de transação `XID` (em C):

```c
#define XIDDATASIZE 128  /* size in bytes */
struct xid_t {
    long formatID;       /* format identifier; -1 = XID nulo */
    long gtrid_length;   /* até 64 bytes — ID da transação global */
    long bqual_length;   /* até 64 bytes — qualificador do branch */
    char data[XIDDATASIZE];
};
typedef struct xid_t XID;
```

O `gtrid` identifica a transação global; o `bqual` distingue os branches dentro da mesma transação global. ([XOpen] slide 15)

### 1.9 Protocolo Two-Phase Commit (2PC)

**Fase 1 — Prepare:**
- O TM chama `xa_prepare()` em cada RM participante.
- Cada RM responde afirmativamente (*Ready-to-Commit*) ou negativamente (*Not-Ready*).

**Fase 2 — Commit/Rollback:**
- Se **todos** os RMs responderam afirmativamente → TM chama `xa_commit()` em todos.
- Se **algum** respondeu negativamente → TM chama `xa_rollback()` em todos.
- Um RM que tenha respondido *Not-Ready* já se encontra automaticamente em estado *rolled back*.

**Otimizações XA:**
- Um RM que não realizou nenhuma escrita pode **abandonar** a participação na transação (responde `XA_RDONLY`).
- Se apenas um RM está envolvido, o TM pode usar **one-phase-commit**.

([XOpen] slides 18–20)

### 1.10 Fluxo de Estados

**Do ponto de vista do cliente (AP):**
`ACTIVE` → *(commit)* → `VOTING` → *(all ok)* → `COMMITTED` → *Clean-up*
`ACTIVE` → *(abort)* → `ABORTED` → *Clean-up*
`VOTING` → *(participant aborted)* → `ABORTED`

**Do ponto de vista do participante (RM):**
`ACTIVE` → *(prepare)* → `VOTING` → `PREPARED` → *(commit)* → `COMMITTED`
`VOTING` → *(abort ou NOTCHANGED)* → `ABORTED`
`PREPARED` → *(abort)* → `ABORTED`

**Do ponto de vista do TM:**
Idêntico ao do cliente, com adição de timeout: `VOTING` → *(participant aborted or time-out)* → `ABORTED`

([XOpen] slides 37–39)

### 1.11 Protocolo Jini/Mahalo (Java Transaction Framework)

**Distinção face a sistemas tradicionais:**
- Sistemas tradicionais baseiam-se em TPM (*Transaction Processing Monitor*) que asseguram a semântica transacional em todos os participantes.
- O servidor Mahalo adota a **responsabilidade distribuída**: cada participante implementa a semântica transacional; o TM fornece apenas os mecanismos mínimos de coordenação.

**Interfaces Java chave:**
- `net.jini.core.transaction.server.TransactionManager` — interface do servidor 2PC
- `net.jini.core.transaction.server.TransactionParticipant` — interface implementada pelos participantes (RM)
- `TransactionFactory` — responsável pela criação de transações

**Interface `TransactionParticipant` (RM):**
```java
int prepare(TransactionManager mgr, long id)
void commit(TransactionManager mgr, long id)
void abort(TransactionManager mgr, long id)
int prepareAndCommit(TransactionManager mgr, long id)  // otimização 1PC
```

**Interface `TransactionManager`:**
```java
Created create(long leaseFor)          // cria transação, retorna id + lease
void join(long id, TransactionParticipant part, long crashCount)  // RM regista-se
int getState(long id)                  // consulta estado
void commit(long id [, long waitFor])  // inicia 2PC
void abort(long id [, long waitFor])   // aborta
```

([XOpen] slides 29–36)

---

## 2. COORDENAÇÃO DE CONCORRÊNCIA

### 2.1 Motivação — Violação da Seriabilidade

Dado um cenário com dois elementos de dados `x` e `y`, invariante `x + y = 150`, e duas transações T1 (z=20) e T2 (k=10) que executam concorrentemente:

- Execução concorrente E' = `r1[x] r2[y] r2[x] w2[x-k] w2[y+k] r1[y] w1[y+z] w1[x-z]`
- T1 liberta o lock de `x` antes de obter o write-lock em `y`, criando uma **janela de oportunidade** para T2 aceder a `x` com valor inconsistente.
- Resultado: o invariante `x + y = 150` é violado.

As execuções serializadas válidas seriam T1T2 ou T2T1, nenhuma das quais ocorre na execução concorrente E'. ([Locks] slides 5–7)

### 2.2 Conceito Fundamental de Locking

- **Objetivo:** garantir acesso exclusivo a elementos de dados (recursos críticos).
- **Tipos de lock:**
  - **ReadLock (Shared):** para operações de leitura.
  - **WriteLock (Exclusive):** para operações de escrita.
- **Regras de obtenção:**
  - Pode obter ReadLock em X se nenhuma outra transação tem WriteLock em X.
  - Pode obter WriteLock em X se nenhuma outra transação tem ReadLock **ou** WriteLock em X.
- **Conflitos:**
  - ReadLock conflitua com WriteLock.
  - WriteLock conflitua com ReadLock e WriteLock.

([Locks] slides 2–3)

### 2.3 Two-Phase Locking (2PL)

**Teorema (Ullman, 1982):** Uma transação que adquire todos os locks antes de libertar qualquer um garante execuções serializáveis.

**As duas fases:**
1. **Growing phase:** a transação adquire locks; nunca liberta.
2. **Shrinking phase:** a transação liberta locks; nunca adquire novos.

**Aplicação ao exemplo:**
- T1 não devia ter libertado o lock em `x` antes de obter o write-lock em `y`.
- O write-lock `wu1[x]` (obtido no início) só devia ser libertado no final, pois existe uma atualização pendente sobre `x`.

([Locks] slide 8)

### 2.4 Interface Possível para um LockManager

```
Operations:
  getLocks(transactionID, lockElements[])
  releaseLocks(transactionID, lockElements[])
  unlock(transactionID)              // liberta todos os locks da transação

LockElements[]:
  LockElement { DataItem, LockMode }
  LockMode = read | write
```

**Decisões arquiteturais importantes:**
- Granularidade dos elementos de dados.
- Necessidade de um LockManager para garantir consistência durante a execução (coordenador de concorrência).

([Locks] slide 9)

### 2.5 Impacto na Performance e Deadlocks

- Um lock detido por uma transação **atrasa** outras transações com locks conflituantes.
- **Deadlocks** requerem estratégias de definição da estrutura do modelo de dados e políticas de coordenação.
- A **granularidade** do locking é um aspeto fundamental dessas estratégias.

([Locks] slide 3)

### 2.6 Estratégias Alternativas de Controlo de Concorrência

- **OCC (Optimistic Concurrency Control):** opera no princípio de que múltiplas transações podem frequentemente completar sem interferir entre si — as verificações de conflito fazem-se no momento do commit.
- **MVCC (Multi-Version Concurrency Control):** melhora a concorrência armazenando múltiplas versões de uma linha em diferentes pontos no tempo — leituras não bloqueiam escritas.

([Locks] slide 10)

---

## 3. PROPRIEDADES ACID

### 3.1 Definição por Propriedade

| Propriedade | Descrição no modelo X/Open |
|---|---|
| **Atomicidade** | O resultado é tudo ou nada — ou todas as tarefas constituintes são concluídas com êxito ou nenhuma é realizada. |
| **Consistência** | A transação transforma um estado válido noutro estado válido (os invariantes são preservados). |
| **Isolamento** | Mudanças nos recursos partilhados só são visíveis fora da transação depois de *committed* — transações concorrentes não veem estados intermédios. |
| **Durabilidade** | As alterações resultantes de uma transação sobrevivem para além de uma falha do sistema. |

([XOpen] slide 6; [Locks] slide 1)

### 3.2 Mecanismos que Garantem Cada Propriedade

- **A** → protocolo 2PC: se um RM falha, o TM faz rollback em todos.
- **C** → responsabilidade do AP (verificação de invariantes) + controlo de concorrência (locks).
- **I** → Two-Phase Locking (2PL) e gestão de locks (ReadLock/WriteLock).
- **D** → logging persistente nos RMs antes de responder *Ready-to-Commit* na fase 1 do 2PC.

### 3.3 Long-Lived Transactions (LLT) / Saga

**Definição (Molina, 1987):** *"A LLT is a saga if it can be written as a sequence of transactions that can be interleaved with other transactions."* ([XOpen] slide 1; [Locks] slide 11)

**Motivação:** em certos cenários (ex.: reserva de viagem — voo + hotel + transfer), a duração pode ser de horas ou dias e o conceito clássico de rollback não é aplicável, pois uma falha requer a execução de um processo de compensação para retornar ao estado anterior.

**Padrão Compensating Transactions:**
*"With sagas, many small atomic transactions are wrapped by a larger longer running transaction. Each small atomic transaction is paired with a compensation handler that is capable of reversing the activity done in the atomic transaction."* ([Locks] slide 11)

**Adoção em microserviços:** o conceito foi adotado no contexto de microserviços para casos de transações longas onde o rollback simples não é aplicável.

---

## 4. MIDDLEWARE E COMUNICAÇÃO ENTRE SERVIÇOS

### 4.1 Questões de Investigação no Contexto SOA

A evolução do modelo X/Open para uma lógica SOA levanta as seguintes questões relevantes para o trabalho:

- **TM como serviço:** que protocolo (*wire-protocol*) usar para cooperação entre cliente e TM?
- **Coordenação de concorrência:** o LockManager deve ser integrado no mesmo serviço TM ou ser um serviço autónomo?
- **Heterogeneidade:** como garantir cooperação entre serviços heterogéneos? Como resolver a transparência à localização (*quando um SerVector é instanciado, como podem os SerVectorCli encontrá-lo*)?

([XOpen] slide 11)

### 4.2 WS-Transaction Architecture

A arquitetura WS-Transaction para ambientes de Web Services envolve:
- **Transaction Coordinator** — equivalente ao TM.
- **Transaction-Aware Web Service** — equivalente ao RM.
- **Participant** — papel desempenhado pelos serviços no contexto da transação.
- Comunicação via **Application Messages + Transaction Context** (no corpo) e **Transaction Protocol Messages** (no cabeçalho).

([XOpen] slides 26–27)

### 4.3 WS-Coordination (W3C)

Arquitetura com separação entre **Coordinator** e **Participant**, onde as mensagens de coordenação são transportadas via SOAP (Header + Body), com separação entre *coordination messages* e *application messages*. Suporta Security, Workflow, e Replication como funcionalidades adicionais no Coordinator e Participant. ([XOpen] slide 27)

### 4.4 Apache ZooKeeper — Introdução

O ZooKeeper é um **servidor de persistência fiável de znodes** (reliable node persistence server):
- Um **znode** é parte de uma estrutura em árvore (mix de ficheiro e diretório) com raiz em `/`.
- API simples com 6 operações: `create /path data`, `delete /path`, `exists /path`, `setData /path data`, `getData /path`, `getChildren /path`.
- O estado de um znode **não pode ser alterado parcialmente** — só pode ser substituído. As operações são **idempotentes**.

([ZK] slides 9–11)

### 4.5 Tipos de Znodes

| Tipo | Comportamento |
|---|---|
| **PERSISTENT** (`CreateMode.PERSISTENT`) | Mantém-se mesmo quando o cliente que o criou se desliga. |
| **EPHEMERAL** (`CreateMode.EPHEMERAL`) | Eliminado quando o cliente que o criou se desliga. |
| **PERSISTENT_SEQUENTIAL** | Persistente + número de sequência automaticamente appended ao path. |
| **EPHEMERAL_SEQUENTIAL** | Efémero + número de sequência. |

([ZK] slide 11)

### 4.6 Sessões de Cliente

- Um cliente acede ao ZooKeeper com base numa **Session** (TCP, porta configurada).
- Preserva ordem FIFO para qualquer sequência de chamadas na mesma sessão.
- Uma sessão cria-se via `new ZooKeeper(ZOOKEEPER_SERVER, timeOut [, Watcher])`.
- O servidor pode ser um único host ou uma lista de hosts do Ensemble.
- **ACL (Access Control List):** permissões CREATE, READ, WRITE, DELETE, ADMIN.

([ZK] slide 12)

### 4.7 ISoS Reference Implementation — ZooKeeper como Registo de Serviços

No contexto do ISoS, o ZooKeeper é usado como **meta-data store** para a árvore ISoS (ISystem/CES/Service). O ISystem₀ serve como registo de serviços com uma interface REST (I₀) em `isos.<organization_domain>:2058`. A estrutura ZooKeeper espelha a hierarquia ISoS: `/ISoS/ISytemp/cestemp/sertemp`. ([ZK] slides 26–27)

---

## 5. ESCALABILIDADE, TOLERÂNCIA A FALHAS E QUALIDADE DE SERVIÇO

### 5.1 O Problema da Fiabilidade em Serviços com Estado

Dado um serviço `SerFSM` (máquina de estados finita), a questão fundamental é: **como torná-lo fiável?** A abordagem é a **replicação**. Mas surgem duas sub-questões:
1. Replicar o **serviço** (múltiplas instâncias)?
2. Replicar o **estado** (state machine replication)?

Como afirma Schneider: *"every protocol we know of that employs replication can be derived using the state machine approach."* ([ZK] slides 3–5)

### 5.2 Teorema CAP

O **teorema CAP** (Eric Brewer, 2000) estabelece que nenhum sistema distribuído pode garantir simultaneamente as três dimensões:
- **C** — Consistency (consistência)
- **A** — Availability (disponibilidade)
- **P** — Partition-tolerance (tolerância a partições de rede)

O ZooKeeper desafia esta limitação através do modo **Quorum/Ensemble**. ([ZK] slide 13)

### 5.3 Ensemble e Quorum

**Standalone mode:** não fiável (usado para desenvolvimento). ([ZK] slide 13)

**Quorum mode (Ensemble):**
- Um conjunto de servidores (`Ensemble`) garante replicação do estado do ZooKeeper.
- O **quorum** é o número mínimo de servidores necessários para o ZooKeeper funcionar.
- Análogo ao quorum de uma assembleia: maioria necessária para tomar decisões.

**Problema do Split-Brain:** se o quorum for demasiado pequeno (ex.: 2 de 5), pode haver duas partições que ambas acreditam ter quorum — resultando em estado inconsistente.

**Fórmula do quorum mínimo (Lamport):**
`N > 3 × NF` onde N = total de servidores, NF = número de servidores com falha tolerados.
- Para tolerar 1 falha: N = 3×1+1 = **4 servidores mínimos**.
- Para tolerar 2 falhas (ensemble de 5): quorum mínimo = **3 servidores**.

**Nota:** Os serviços devem correr em ambientes de execução independentes em termos de falha (racks diferentes com alimentação independente). Para tolerância a desastres: localização geográfica diferente + redundância de comunicação. ([ZK] slides 14–16)

### 5.4 Papéis dos Servidores ZooKeeper

| Papel | Responsabilidade |
|---|---|
| **Leader** | Coordena commits das transações de estado; processa state changes. |
| **Follower** | Reencaminha state changes para o Leader; processa reads; inicia eleição de líder se não contactar o Leader. |
| **Observer** | Processa reads (escalabilidade); **não** conta para o quorum; **não** participa na eleição do líder. |

Os Observers permitem escalar o throughput de leituras sem aumentar o quorum (sem penalizar a latência de writes). ([ZK] slide 18)

### 5.5 Operações de ZooKeeper

- **Leitura (processadas por qualquer servidor):** `exists`, `getData`, `getChildren`
- **Escrita/update (encaminhadas para o Leader):** `create`, `delete`, `setData`

As **transações ZooKeeper são idempotentes** — podem ser repetidas sem alterar o resultado esperado. ([ZK] slides 18–19)

### 5.6 ZooKeeper Transactions (zxid)

Uma transação ZooKeeper (`zxid`) é um valor de 64 bits com duas partes:
- **Epoch** (32 bits): número da era de liderança.
- **Counter** (32 bits): número de sequência dentro da era.

([ZK] slide 19)

### 5.7 Eleição de Líder

**Algoritmo de eleição:**
1. Cada servidor inicia no estado `LOOKING` e envia notificações de eleição com `vote(sid, zxid)`.
2. Ao receber um voto, o servidor aplica as regras:
   - Se `voteZxid > myZxid` → muda o seu voto para o recebido.
   - Se `voteZxid == myZxid` e `voteId > mySid` → muda o voto.
   - Caso contrário → mantém o voto atual.
3. O servidor com **maior zxid** (mais atualizado) ganha. Em caso de empate, ganha o maior `sid`.
4. Ao atingir maioria de votos → um servidor entra em estado `LEADING`; os restantes em `FOLLOWING`.

**Exemplo:** Servidores S1(vote: 1,6), S2(vote: 2,5), S3(vote: 3,5) → S1 e S3 mudam para (1,6) → todos elegem S1. ([ZK] slides 20–21)

### 5.8 Protocolo ZAB (Zookeeper Atomic Broadcast)

Baseado no algoritmo de consenso Paxos (Lamport, 2000) e proposto por Junqueira, Reed e Serafini (Yahoo! Research, 2011). O protocolo de commit é análogo ao 2PC do standard X/Open:

1. O **Leader** envia `PROPOSAL(zxid)` a todos os Followers.
2. Os **Followers** respondem com `ACK` (aceitam a proposta).
3. Após receber ACKs de um quorum, o **Leader** envia `COMMIT(zxid)`.

**Garantias ZAB:**
- Se o Leader faz broadcast de T antes de T', cada servidor deve commitar T antes de T'.
- Se qualquer servidor commita T e T' por essa ordem, todos os servidores devem commitar T antes de T'.

([ZK] slides 22–23)

### 5.9 Configuração do Servidor ZooKeeper

**Standalone (development):**
```cfg
tickTime=2000          # heartbeat em ms; session timeout min = 2*tickTime
dataDir=/tmp/zookeeper
clientPort=2181
```

**Quorum mode:**
```cfg
tickTime=2000
dataDir=/tmp/zookeeper
clientPort=2181
server.1=siserver0.local:2888:3888   # porta 2888 = leader comms; 3888 = eleição
server.2=siserver1.local:2888:3888
server.3=siserver2.local:2888:3888
```

([ZK] slides 24–25)

---

## 6. FRAMEWORK ISoS (Informatics System of Systems)

*(Relevante para contextualização da arquitetura do trabalho)*

### 6.1 Hierarquia de Conceitos

- **ISystem** (Informatics System): composto por um ou mais CES.
- **CES** (Cooperation Enabled Services): composto por um ou mais Service elements.
- **Service**: entidade computacional autónoma que embede lógica e recursos para operacionalizar uma responsabilidade computacional.
- **ISystem₀** (Meta-ISystem): tem papel de coordenação/gestão; serve como registo ISystem/CES/Service.

([ISoS] slides 8–9; [ZK] slide 26)

### 6.2 Problema da Heterogeneidade

No contexto ISoS, a heterogeneidade levanta questões de interoperabilidade:
- **Cooperação:** como interoperam elementos Serviço heterogéneos?
- **Transparência à localização:** quando um `SerVector` é instanciado, como podem os `SerVectorCli` encontrá-lo?
- **Wire-protocol:** qual o protocolo para cooperação entre Service (client) e Service (provider/TM)?

([XOpen] slide 11)

---

## 7. CONCEITOS ADICIONAIS RELEVANTES PARA DISCUSSÃO TEÓRICA

### 7.1 Outros Standards Transacionais de Referência

- **CORBA/OMG:** Object Transaction Service (OTS)
- **TUXEDO (Oracle):** Portable Transactions Processing Monitor
- **ACMS (HP):** Application Control and Management System
- **Encina (CMU/IBM):** Transaction Processing Monitor académico
- **CICS e IMS (IBM):** Transaction Processing Monitors em ambiente mainframe

*Referência de estudo:* Principles of Transaction Processing, Philip A. Bernstein, Cap. 5. ([XOpen] slide 22)

### 7.2 Tipos de Falha em Sistemas Distribuídos

- **Byzantine Failure:** componente exibe comportamento arbitrário/malicioso, potencialmente em colusão com outros componentes defeituosos (Lamport).
- **Fail-stop Failure:** componente transita para um estado que permite a outros detetar a falha, e para (Schneider).

([ZK] slide 2)

### 7.3 Heuristic Transaction Completion

Uma transação pode ser terminada com base em **heurísticas** (*Heuristic Transaction Completion*) — decisão unilateral de um RM de commitar ou fazer rollback, sem aguardar instrução do TM. Usado quando a comunicação com o TM falha durante o período *in-doubt*. ([XOpen] slide 8)

### 7.4 Commit Protocol — Presumed Rollback

O modelo X/Open DTP sugere o protocolo **"two-phase commit with presumed rollback"**: na ausência de log do TM para uma transação, assume-se que esta foi feita rollback. Isto reduz o número de mensagens em caso de falha do TM. ([XOpen] slide 8)

### 7.5 Exemplos de Uso no Cenário dos Vetores

**TPLM** (Transaction Processing Lock Manager) aparece como componente autónomo entre o AP e os serviços de vetor, responsável pela gestão de locks (Two-Phase Locking) no cenário concorrente. A sequência de operações de cada cliente (read vect01, wait, write vect01, wait, read vect02, wait, write vect02) ilustra a necessidade de manter locks durante toda a transação para preservar o invariante. ([XOpen] slides 4–5; [Locks] slide 4)

### 7.6 Paralelo entre ZAB e X/Open 2PC

O protocolo ZAB do ZooKeeper é explicitamente descrito como análogo ao 2PC do standard X/Open: Leader = TM, Followers = RMs, PROPOSAL = prepare, ACK = ready-to-commit, COMMIT = commit. Esta correspondência é útil para fundamentar a escolha do ZooKeeper como solução de tolerância a falhas no contexto do trabalho. ([ZK] slide 22)

---

*Gerado em 17 de Abril de 2026 — para uso no Trabalho 1 de IESD 2025/26*
