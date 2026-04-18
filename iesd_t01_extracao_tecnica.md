# Extração Técnica para Relatório IESD — Trabalho 01

> **Fontes analisadas:**
> - `Distributed_Transaction_Processing_-_The_XA_Specification.pdf` — X/Open CAE Specification, 1991 **(XA Spec)**
> - `iesd2526sv-trabalho-01-v1.0.0_enunciado.pdf` — Enunciado oficial, março 2026 **(Enunciado)**
> - `iesd2526sv-trabalho-01-v1.0.0_enunciado_RESUMIDO_JE_pos-aula.docx` — Resumo pós-aula **(Resumo)**
>
> ⚠️ **Nota:** As secções 2–5 são apenas marginalmente cobertas pelo XA Spec. Indico explicitamente o que está nos documentos vs. o que precisa de ser complementado com outras fontes.

---

## 1. MODELO X/OPEN DTP

### 1.1 Os Três Papéis

*(XA Spec, Cap. 1, p.1; Cap. 2, pp.3–7)*

O modelo X/Open DTP define três componentes de software:

**Application Program (AP)**
Define as fronteiras das transações e especifica as ações que as constituem. Acede aos recursos dentro das fronteiras de transação. Usa a interface TX para comunicar com o TM. Um AP thread só pode ter uma transação global ativa de cada vez. O AP não está envolvido no protocolo de commit nem no processo de recuperação.

**Resource Manager (RM)**
Gere uma parte específica dos recursos partilhados do sistema. Exemplos: DBMS (base de dados), ISAM (sistema de ficheiros), print server. Um único RM pode servir múltiplos domínios de recursos independentes (instâncias de RM). O RM deve aceitar XIDs do TM e associar a eles todo o trabalho realizado.

**Transaction Manager (TM)**
Gere transações globais, coordena a decisão de commit/rollback, e coordena a recuperação de falhas. Atribui identificadores (XIDs) às transações. Informa cada RM do XID em nome do qual está a trabalhar. Monitoriza o progresso e é responsável pela conclusão das transações e pela recuperação de falhas.

**Diagrama de interfaces** *(XA Spec, Cap. 2, p.3)*:

```
AP <——TX interface——> TM
AP <——native interface> RM
TM <——XA interface——> RM
```

- Interface (1): AP usa recursos dos RMs (interface nativa)
- Interface (2): AP define fronteiras de transação via TX (TM)
- Interface (3): TM e RMs trocam informação de transação via **XA** ← *objeto desta especificação*

---

### 1.2 Conceitos de Transação e Transação Distribuída

*(XA Spec, Sec. 2.2.1–2.2.6, pp.4–6)*

**Transação:** unidade completa de trabalho, tipicamente modificando recursos partilhados. Pode ser revertida (*rolled back*) a qualquer momento antes do commit. Quando o sistema determina que pode completar sem falha → *commita* (mudanças tornam-se permanentes). Completion = commit **ou** rollback.

**Distributed Transaction Processing (DTP):** sistemas onde o trabalho de uma única transação pode ocorrer em múltiplos RMs. Implica: (a) necessidade de referenciar globalmente a transação; (b) a decisão de commit/rollback deve considerar o estado de todo o trabalho distribuído, com efeito uniforme.

**Transação Global:** quando múltiplos RMs operam em suporte da mesma unidade de trabalho. Cada RM deve deixar o TM coordenar as suas unidades de trabalho recuperáveis. Se qualquer operação falhar em qualquer RM → todos os RMs participantes devem fazer rollback.

**Transaction Branch:** parte do trabalho de uma transação global para a qual o TM e o RM realizam um protocolo de commitment separado mas coordenado. Identificada por um XID único.

---

### 1.3 Two-Phase Commit (2PC) — Protocolo

*(XA Spec, Sec. 2.3, pp.8–9)*

O protocolo usado é **two-phase commit with presumed rollback** (conforme OSI DTP):

**Fase 1 — Prepare:**
- TM pede a todos os RMs para se prepararem para o commit (`xa_prepare()`)
- RM verifica se pode garantir a capacidade de commit do transaction branch
- Se sim: regista de forma estável a informação necessária → responde afirmativamente
- Se não (qualquer razão): responde negativamente, faz rollback → pode descartar conhecimento do branch

**Fase 2 — Commit/Rollback:**
- TM emite pedido efetivo de commit ou rollback a todos os RMs
- Antes do commit: TM regista de forma estável a decisão e a lista de todos os RMs participantes
- Todos os RMs fazem commit/rollback e retornam estado ao TM
- TM descarta o conhecimento da transação global

**Rollback da transação global:** o TM faz rollback se (a) qualquer RM responde negativamente na Fase 1, ou (b) o AP ordena rollback. Uma resposta negativa veta a transação global. O TM não emite pedidos de Fase 2 aos RMs que responderam negativamente na Fase 1.

**Otimizações do protocolo** *(Sec. 2.3.2, p.8)*:

- **Read-only:** RM responde ao prepare que não modificou recursos → o seu envolvimento termina; Fase 2 não ocorre para esse RM. *Nota: pode comprometer serializabilidade global se aplicado antes de todo o trabalho da transação estar preparado.*
- **One-phase commit:** se só existe um RM a fazer alterações → TM faz pedido de Fase 2 diretamente sem Fase 1 (RM decide o outcome e esquece o branch antes de retornar).

---

### 1.4 Interface XA — Serviços

*(XA Spec, Cap. 3, Sec. 3.1, p.12)*

| Rotina | Direção | Descrição |
|--------|---------|-----------|
| `xa_open()` | TM→RM | Inicializar RM para uso pelo AP |
| `xa_close()` | TM→RM | Terminar uso do RM pelo AP |
| `xa_start()` | TM→RM | Iniciar/retomar branch — associar XID ao trabalho futuro |
| `xa_end()` | TM→RM | Dissociar thread do transaction branch |
| `xa_prepare()` | TM→RM | Pedir ao RM para se preparar para o commit |
| `xa_commit()` | TM→RM | Ordenar commit do transaction branch |
| `xa_rollback()` | TM→RM | Ordenar rollback do transaction branch |
| `xa_recover()` | TM→RM | Obter lista de branches preparados/heurísticos |
| `xa_forget()` | TM→RM | Autorizar RM a esquecer branch heurístico |
| `xa_complete()` | TM→RM | Testar operação assíncrona |
| `ax_reg()` | RM→TM | Registo dinâmico de RM no TM |
| `ax_unreg()` | RM→TM | Cancelar registo dinâmico |

**Estrutura XID** *(Sec. 4.2, pp.19–20)*: identifica um transaction branch. Contém `formatID`, `gtrid_length`, `bqual_length`, `data[128]`. O campo `data` contém dois componentes: `gtrid` (global transaction identifier, até 64 bytes) + `bqual` (branch qualifier, até 64 bytes). Juntos devem ser globalmente únicos.

```c
struct xid_t {
    long formatID;        /* format identifier; -1 = null XID */
    long gtrid_length;    /* value 1-64 */
    long bqual_length;    /* value 1-64 */
    char data[128];       /* gtrid followed by bqual */
};
typedef struct xid_t XID;
```

**RM Switch** *(Sec. 4.3, p.21)*: estrutura `xa_switch_t` que o RM publica — contém nome, flags, versão e ponteiros de entrada para todas as rotinas `xa_`. Permite ao TM trabalhar com diferentes RMs sem recompilação.

---

### 1.5 Falhas e Recuperação

*(XA Spec, Sec. 2.3.4, p.9; Sec. 3.6, p.18)*

O modelo X/Open assume:
- TMs e RMs têm acesso a **armazenamento estável**
- **TMs coordenam e controlam a recuperação**
- RMs providenciam o seu próprio restart/recuperação de estado; a pedido, devem fornecer lista de XIDs preparados ou completados heuristicamente

**Cenários de falha:**
- Falhas corrigíveis internamente → podem não afetar a transação global
- Falhas que não perturbam o protocolo → sistema responde com rollback das transações apropriadas (RM que recupera de falha responde negativamente ao prepare, não reconhecendo o XID)
- **Falhas mais graves** que perturbam o protocolo → TM deteta pela ausência de resposta esperada

**Conclusão heurística** *(Sec. 2.3.3, p.9)*: RM preparado pode decidir de forma autónoma fazer commit/rollback. Deve notificar o TM (mesmo que a decisão coincida com o que o TM pediria). Não pode descartar o conhecimento do branch até o TM chamar `xa_forget()`.

**Ação unilateral do RM** *(Sec. 3.6, p.18)*: RM pode marcar branch como rollback-only a qualquer momento exceto após prepare bem-sucedido. Se thread terminar, RM deve dissociar e fazer rollback dos branches associados. Se RM falhar, deve fazer rollback de todos os branches não preparados.

---

### 1.6 Requisitos de Implementação

*(XA Spec, Cap. 7, pp.65–69)*

**Requisitos do AP:** delegar ao TM a responsabilidade de controlar cada transação global. Não usar instruções de commit/rollback nativas do RM (e.g., `EXEC SQL COMMIT WORK`) dentro de uma transação global.

**Requisitos do RM:**
- Implementar todas as rotinas `xa_`
- Aceitar e mapear XIDs; não alterar os bits do campo `data` do XID
- Suportar protocolo de commitment 2PC incluindo one-phase commit
- Rastrear estado de todos os branches; após `xa_prepare()` bem-sucedido, não pode esquecer o branch
- Garantir que o forward progress é possível no rollback (para resolução de deadlocks) *(p.66)*

**Requisitos do TM:**
- Gerar XIDs globalmente únicos (recomendado usar ISO OBJECT IDENTIFIER) *(p.69)*
- Chamar `xa_open()`/`xa_close()` em cada RM local
- Tratar corretamente todos os return codes possíveis
- Garantir conclusão ordenada de todos os branches
- Chamar `xa_recover()` durante recuperação de falha

---

### 1.7 State Tables do XA

*(XA Spec, Cap. 6, pp.57–63)*

Definem as **sequências legais de chamadas XA**. Essenciais para implementação correta do TM.

**Estados de inicialização do RM** *(Tabela 6-1, p.58)*:
- `R0` (Un-initialised) → `xa_open()` → `R1` (Initialised)
- `R1` → `xa_close()` → `R0`
- Usos redundantes de `xa_open()`/`xa_close()` são válidos

**Estados de transaction branch** *(Tabela 6-4, p.62)*:
- `S0` Non-existent → `xa_start()` → `S1` Active
- `S1` Active → `xa_end()` → `S2` Idle
- `S2` Idle → `xa_prepare()` → `S3` Prepared
- `S3` Prepared → `xa_commit()` → `S0` (commit bem-sucedido)
- `S3` Prepared → `xa_rollback()` → `S0` (rollback)
- `S2`/`S3`/`S4` → `xa_rollback()` → `S0`
- Qualquer estado → `xa_*()` → `[XAER_RMFAIL]` → `R0`

O estado `S3` (Prepared) é o estado crítico: o RM **não pode esquecer o branch** até receber `xa_commit()` ou `xa_rollback()`.

---

## 2. COORDENAÇÃO DE CONCORRÊNCIA

> ⚠️ **O XA Spec não cobre 2PL diretamente.** A especificação menciona *serialisability* apenas em footnote (p.8) e refere locks em contexto de threads tightly/loosely-coupled (p.6–7). Os detalhes abaixo são o que a especificação fornece; o resto precisa de ser fundamentado com literatura adicional.

### 2.1 O que o XA Spec diz sobre concorrência

*(XA Spec, Sec. 2.2.9, pp.6–7)*

**Threads tightly-coupled:** par de threads desenhado para partilhar recursos; com respeito às políticas de isolamento do RM, são tratados como uma única entidade. O RM deve **garantir que não ocorre deadlock de recursos dentro do transaction branch** entre threads tightly-coupled.

**Threads loosely-coupled:** sem tal garantia. O RM pode tratá-los como se estivessem em transações globais separadas, mesmo que o trabalho seja completado atomicamente.

**Serialisability** *(footnote 1, p.8)*: propriedade de um conjunto de transações concorrentes — existe pelo menos uma sequência serial das transações que produz resultados idênticos aos da execução concorrente.

**Otimização read-only e serialisabilidade global** *(Sec. 2.3.2, p.8)*: se um RM retorna read-only antes de todo o trabalho da transação global estar preparado, a serialisabilidade global não pode ser garantida — o RM pode libertar locks de leitura antes de toda a atividade da aplicação para aquela transação global terminar.

### 2.2 Contexto do enunciado: TPLM

*(Enunciado, p.1; Resumo, Sec. 3.2)*

- **Elemento de dados** = cada posição individual de cada vetor (e.g., `vect01[0]`, `vect02[3]`)
- O TPLM controla acesso concorrente a estes elementos de dados partilhados por múltiplos clientes
- Garante **Isolamento e Consistência** das transações
- É um elemento crítico → requer tolerância a falhas (ZooKeeper)

> 📚 **Para fundamentar 2PL no relatório, recomendam-se fontes adicionais:**
> - Bernstein & Newcomer, *Principles of Transaction Processing*
> - Gray & Reuter, *Transaction Processing: Concepts and Techniques*
> - Slides de aula de IESD/Computação Distribuída

---

## 3. PROPRIEDADES ACID

### 3.1 ACID no modelo X/Open

*(XA Spec, Sec. 2.2.1, p.4; Sec. 2.3, pp.8–9)*

O XA Spec aborda ACID implicitamente através do protocolo:

| Propriedade | Mecanismo no modelo X/Open |
|-------------|---------------------------|
| **Atomicidade** | 2PC garante que todos os RMs fazem commit ou todos fazem rollback; presumed rollback garante rollback em caso de falha do TM |
| **Consistência** | Responsabilidade do AP (não violar regras de integridade) + RM (retornar `XA_RBINTEGRITY` se detetar violação) |
| **Isolamento** | Gestão de locks pelo RM; separação de threads tightly/loosely-coupled; protocolo de branches |
| **Durabilidade** | Armazenamento estável do TM (regista decisão de commit antes da Fase 2) e do RM (regista estado preparado antes de responder ao prepare) |

*(Enunciado, p.1; Resumo, Sec. 3.1–3.2)*

> "Os dois elementos serviço [TM e TPLM] deverão ser o garante das propriedades ACID referentes a conjuntos de operações de escrita ou leitura no contexto de uma transação."

### 3.2 LLT / Saga

*(Enunciado, p.2; Resumo, Sec. 5)*

O enunciado pede explicitamente a **confrontação das transações ACID com LLT/Saga**:

> "Valoriza a discussão sobre a extensão do contexto X/Open e a conformidade com as propriedades ACID para um quadro de transações LLT/saga (transações de longa duração)."

> 📚 **LLT/Saga não está coberto pelo XA Spec. Fontes recomendadas:**
> - Garcia-Molina & Salem (1987), *Sagas*
> - Richardson, *Microservices Patterns* (cap. Saga pattern)
> - Hohpe & Woolf, *Enterprise Integration Patterns*

---

## 4. MIDDLEWARE E COMUNICAÇÃO ENTRE SERVIÇOS

### 4.1 MOM — Posição do Enunciado

*(Enunciado, p.2; Resumo, Sec. 3.3)*

**Importante:** o MOM **não deve ser implementado** — deve ser **apenas discutido no relatório**:

> "Deverá ser discutida a aplicabilidade de um elemento Serviço de mediação Message Oriented Middleware (MOM) na interação/coordenação entre as principais entidades do desafio (Service.client, Service.vector, Service.tm, Service.tplm)"

Conceitos a discutir *(per Resumo)*: desacoplamento temporal/espacial, comparação com RPC/REST.

### 4.2 Apache ZooKeeper — Posição do Enunciado

*(Enunciado, p.1; Resumo, Sec. 3.2 e 4)*

> "Sendo a responsabilidade de coordenação, um elemento crítico do sistema informático, deverá garantir que o sistema informático implemente alguma estratégia de tolerância a falhas, no que poderá recorrer a um sistema ou elemento de sistema envolvendo a adoção do gestor de nós de uma árvore (z-nodes) desenvolvido pelo projeto Apache Zookeeper."

Posição pós-aula: ZooKeeper deve ser implementado **em todos os serviços onde faça sentido** (não apenas no TPLM). Serve para **guardar estado** e **detetar falhas** dos componentes distribuídos. O uso para manter estado é opcional; outra solução pode ser adotada.

### 4.3 Consul (Service Registry)

*(Enunciado, p.2; Resumo, Sec. 4)*

> "Considerar ainda a discussão da abordagem do trabalho com o exemplo entregue em que a interação entre elementos serviço (transparência à localização) é mediada por elemento serviço de gestão de diretoria (projeto Consul) de entidades serviço (service registry)."

Deve ser discutido como alternativa/complemento ao exemplo entregue em aula.

> 📚 **ZooKeeper e Consul não estão cobertos pelo XA Spec. Fontes recomendadas:**
> - Hunt et al. (2010), *ZooKeeper: Wait-free coordination for Internet-scale systems*
> - docs.zookeeper.apache.org
> - developer.hashicorp.com/consul

---

## 5. ESCALABILIDADE E QUALIDADE DE SERVIÇO

### 5.1 Aspetos de Escala — Per Enunciado

*(Enunciado, p.2; Resumo, Sec. 5)*

> "Deverão ser questionados e validados aspetos de escala (número de elementos serviço a participar como cliente, AP, ou como gestor de recurso, RM) e aspetos de qualidade (reliability), e.g., mecanismos que garantam algum nível de tolerância/recuperação de falhas, resiliência."

Cenário concreto: "instanciar um número elevado de serviços cliente e vetor; escalabilidade."

### 5.2 Avaliação de Desempenho — Per Enunciado

*(Resumo, Sec. 3.5)*

- Medir **número de transações por unidade de tempo**
- Simular criação de número elevado de transações e medir **tempos de resposta**
- Comparar opções tecnológicas (Java/Quarkus vs Python vs .NET) nos quadros distribuídos de execução

### 5.3 Heterogeneidade Tecnológica — Per Enunciado

*(Enunciado, p.2; Resumo, Sec. 5)*

> "Deverá ser ainda questionada a hipótese de os elementos serviço terem sido desenvolvidos sobre quadro tecnológico diferenciado, e.g., ecossistema Java/quarkus, Microsoft/C#/.Net, Python, de entre muitos outros."

A interface XA é relevante aqui *(XA Spec, Sec. 4.3, p.21)*: a sua definição em C com `xa_switch_t` permite que RMs de diferentes fornecedores sejam trocados sem recompilação — analogia direta à interoperabilidade entre serviços de tecnologias distintas via interface padronizada.

> 📚 **CAP theorem, BASE, replicação não estão cobertos pelo XA Spec. Fontes recomendadas:**
> - Brewer (2000), *Towards Robust Distributed Systems*
> - Gilbert & Lynch (2002), *Brewer's Conjecture and the Feasibility of Consistent Available Partition-Tolerant Web Services*

---

## 6. Conceitos Relevantes Adicionais do XA Spec

*(Conceitos não enquadrados nas secções acima mas relevantes para discussão teórica)*

**Modos de chamada XA** *(Sec. 3.5, p.18)*: além do modo síncrono, existem modo *non-blocking* (retorna imediatamente se bloquearia) e modo *assíncrono* (retorna de imediato; TM chama `xa_complete()` para verificar conclusão). Relevante para discussão de performance e throughput.

**Gestão do ciclo de vida dos RMs** *(Sec. 3.2, p.13)*: `xa_open()` pode receber parâmetros via string de configuração (domínio de recurso, opções de operação) — lida de um ficheiro de configuração pelo TM. Permite ao administrador controlar opções de runtime sem recompilação. Relevante para discussão de gestão de infraestrutura.

**Registo dinâmico de RMs** *(Sec. 3.3.1, pp.15–16)*: RMs raramente usados podem registar-se dinamicamente via `ax_reg()` quando têm trabalho a fazer, em vez de serem sempre envolvidos. Reduz overhead. Relevante para escalabilidade.

**Presunção de rollback** *(Sec. 2.3, p.8)*: o TM não precisa de registar de forma estável a decisão de rollback nem os participantes de uma transação com rollback. Apenas o commit é registado de forma estável. Simplifica a recuperação e é um argumento chave para robustez do 2PC.

**Múltiplas instâncias de RM** *(Sec. 2.2.4, p.5; Sec. 3.2, p.13)*: um único RM pode servir múltiplos domínios de recursos independentes (instâncias). O TM chama `xa_open()` várias vezes com strings de identificação distintas. Relevante para discussão de escalabilidade e arquitetura multi-vetor.

---

## 7. Resumo de Lacunas — O que Falta nos Documentos Partilhados

| Tópico necessário | Cobertura nos docs | Fontes sugeridas |
|---|---|---|
| Two-Phase Locking (2PL) | ❌ não coberto | Bernstein et al.; Gray & Reuter |
| Níveis de isolamento SQL | ❌ não coberto | ANSI SQL standard; Berenson et al. 1995 |
| LLT / Saga pattern | ❌ não coberto | Garcia-Molina & Salem 1987; Richardson |
| RabbitMQ / MOM internals | ❌ não coberto | Documentação RabbitMQ; Hohpe & Woolf |
| Apache ZooKeeper | ❌ não coberto | Hunt et al. 2010; docs.zookeeper.apache.org |
| Consul service registry | ❌ não coberto | developer.hashicorp.com/consul |
| CAP theorem / BASE | ❌ não coberto | Brewer 2000; Gilbert & Lynch 2002 |
| Contentor OCI / Podman | ❌ não coberto | docs.podman.io |
