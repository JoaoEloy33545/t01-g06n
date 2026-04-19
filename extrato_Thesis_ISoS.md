# Extrato da Tese ISoS — Base Teórica para Trabalho 1 IESD 2025/26

> **Tese:** *Collaborative Networks as Open Informatics System of Systems (ISoS)*
> **Autor:** António Luís Freixo Guedes Osório (Prof. Luís Osório), UvA, 2020
> **245 páginas** | ISBN 978-989-33-1118-9

---

## ⚠️ NOTA CRÍTICA — LER ANTES DE USAR ESTE DOCUMENTO

A tese é um trabalho de investigação **arquitetural e conceptual** sobre o modelo ISoS no contexto de redes colaborativas (tolling, logística, enforcement). **Não é um livro de sistemas distribuídos.** As seguintes tecnologias/conceitos do enunciado do Trabalho 1 **não aparecem na tese com detalhe técnico**:

| Tecnologia/Conceito no Trabalho 1 | Cobertura na Tese |
|---|---|
| X/Open DTP (AP, TM, RM, 2PC) | ❌ Não mencionado |
| Interfaces TX / XA | ❌ Não mencionado |
| Two-Phase Locking (2PL), deadlock | ❌ Não mencionado |
| ACID (detalhe por propriedade) | ❌ Não mencionado |
| Saga / LLT | ❌ Apenas referência indireta a processos de longa duração |
| RabbitMQ / MOM (detalhe técnico) | ⚠️ Mencionado como opção de comunicação, sem detalhe |
| ZooKeeper / eleição de líder | ❌ Não mencionado |
| Consul / Service Registry | ❌ Não mencionado (conceito equivalente existe via CES SelfAwareness) |
| Scalability (detalhe técnico) | ⚠️ Mencionado como requisito, sem mecanismos concretos |
| CAP theorem | ✅ Mencionado e referenciado [20],[19] — sem aprofundamento |

**O valor desta tese para o teu trabalho é diferente:** fornece o **enquadramento teórico ISoS** que justifica *porquê* precisamos de coordenação distribuída, tolerância a falhas, e composição de serviços autónomos — o "chapéu conceptual" que cobre a implementação X/Open DTP + ZooKeeper + RabbitMQ. As secções 2–5 deste extrato precisarão de ser complementadas com outras fontes (slides/apontamentos da UC, X/Open DTP spec, literatura técnica).

---

## 1. CONCEITO ISoS

### 1.1 Definição e Princípios Fundamentais

O ISoS é um framework aberto para a gestão do panorama tecnológico informático de uma organização, composto por três abstrações principais (p. 82, 90–91):

**Definição 1 — CES (Cooperation Enabled System):** Entidade computacional autónoma com ciclo de vida independente, definida como tuplo:

> `CES = (I0, SA, CS)` onde:
> - `I0` — interface standard de entrada; ponto de acesso a metadados e serviços;
> - `SA` — *Self-Awareness*: metadados embebidos que tornam o CES consciente das capacidades dos peers;
> - `CS` — conjunto de serviços implementados `{I0, I1, …, IN}`, N ≥ 1.

**(p. 84)**

**Definição 2 — Isystem (Informatics System):** Composto lógico de um ou mais elementos CES:

> `Isystem = (I0, SA, MC)` onde:
> - `MC` — composto modular de CES: `CESc = {CES0, CES1, …, CESN}`, N ≥ 0;
> - `CES0` — meta-CES, responsável pela coordenação do composto.

**(p. 88)**

**Definição 3 — ISoS (Isystem of systems):**

> `ISoS = (I0, SA, ISC)` onde:
> - `ISC = {Isystem0, Isystem1, …, IsystemM}`, M ≥ 0;
> - `Isystem0` — meta-Isystem, coordena o panorama tecnológico completo da organização.

**(p. 90)**

**Princípios fundamentais do ISoS** (pp. 20–21, 107–108):
- **Independência/autonomia:** cada Isystem é instanciado e operado independentemente; falhas num peer devem seguir um plano de recuperação predefinido;
- **Liberdade tecnológica:** o ISoS não impõe tecnologia de implementação interna — apenas a interface `I0`/`SelfAwareness()` é normalizada;
- **Substituibilidade:** um CES ou Isystem pode ser substituído por implementação concorrente equivalente (*external modularity*) — ver Definições 4 e 5 (pp. 94–95);
- **Acoplamento adaptativo (*endogenous integration*):** ao contrário do ESB que requer mediadores externos, o CES adapta-se dinamicamente às capacidades do peer via `SelfAwareness()` (pp. 80–81);
- **Modelo de referência + certificação de conformidade:** a substituibilidade requer um modelo de referência e processo de certificação (p. 106).

### 1.2 Relação com o Modelo X/Open DTP

A tese **não discute X/Open DTP diretamente**. O enquadramento conceptual relevante é o seguinte:

O ISoS reconhece que Isystems que cooperam para processos críticos (p. 22, 1.5) necessitam de "mecanismos de coordenação e recuperação (tolerância a falhas)". A noção de **responsabilidade computacional delimitada** do CES — com fronteiras claras de serviço e dados de estado — é conceptualmente análoga ao papel do **Resource Manager (RM)** no modelo X/Open DTP: cada CES encapsula um domínio de estado, expondo operações de serviço que um coordenador externo pode invocar. O `CES0`/`Isystem0`, enquanto coordenador do composto, é análogo ao **Transaction Manager (TM)**.

Esta correspondência não é explicitada na tese — é uma inferência útil para contextualização teórica no relatório.

### 1.3 Elemento Serviço e sua Composição

O **elemento serviço** no ISoS é operacionalizado através do conceito de **GME (Generic Modeling Entity)** (p. 85–86):

- Um GME embebe dados identificados por MIME-Type (`contentType`), ocultando especificidades tecnológicas;
- O método `SelfAwareness()` — disponível via `I0`/`Service0` — é o único mecanismo de convenção para que um peer cliente se acople aos serviços implementados;
- Os serviços de um CES podem ser implementados em tecnologias heterogéneas (FTP, RMI, .NET, MOM, web services, etc.) — o `contentType` MIME-Type determina o mecanismo de acoplamento concreto (p. 85).

A composição de Isystems segue uma hierarquia: `CES atómico → Isystem (composto de CES) → ISoS (composto de Isystems)`, com o `Isystem0` como ponto de entrada e coordenação (p. 91).

---

## 2. MODELO X/OPEN DTP

> **⚠️ Não coberto na tese.** Esta secção não pode ser preenchida com base neste documento. Fontes recomendadas: especificação X/Open DTP (CAE, 1991/1994), slides IESD, Bernstein & Newcomer *Principles of Transaction Processing*.

O único ponto de contacto é a menção genérica a "mechanisms addressing security, **transactions**, and reliability" como requisitos de integração (p. 10, §1.2.1), e a referência a "billions of transactions, performance, fault tolerance, (re)configurability" como desafios dos sistemas distribuídos modernos (p. 48, §2.5).

---

## 3. COORDENAÇÃO DE CONCORRÊNCIA

> **⚠️ Não coberto na tese.** 2PL, tipos de lock, deadlock — nenhum destes mecanismos é discutido. A tese menciona "concurrency" uma vez num contexto de microserviços (p. 48) sem detalhe técnico.

---

## 4. PROPRIEDADES ACID E ALTERNATIVAS

### 4.1 O que a Tese Diz sobre Consistência e Alternativas

O ISoS aborda indiretamente o problema das transações longas e da consistência distribuída:

**CAP Theorem** (p. 22, §1.5):
> "The discussion motivated by the Consistency Availability Partitions (CAP) theorem [20],[19] emphasises that in principle it is not possible to develop an ideal distributed Isystem of systems, that is an Isystem that does not fail under any circumstances."

A tese reconhece o CAP como constraint fundamental e posiciona a fiabilidade como requisito crítico sem detalhar mecanismos concretos.

**Processos de longa duração** (p. 181, §7.3.1):
A tese identifica como investigação futura o problema de:
> "long-lived processes (e.g. for hours, days, months, years), where process instances need to be maintained consistently."

Esta passagem é o ponto mais próximo do conceito de **LLT (Long-Lived Transaction) / Saga** na tese — reconhecendo o problema mas não propondo solução técnica. A referência [97] menciona "Strangler Pattern" como abordagem para modernização incremental de sistemas transacionais (p. 48).

**Consistência e Federação Distribuída** (p. 21, §1.5):
> "In such complex environments [...] the global consistency is difficult to maintain. The detection/identification of such inconsistencies needs to support upper layer decisions and to provoke controlled failures and subsequent recovery procedures."

---

## 5. MIDDLEWARE E COMUNICAÇÃO ENTRE SERVIÇOS

### 5.1 MOM: Menção no Contexto ISoS

O **MOM (Message-Oriented Middleware)** aparece na tese como uma das tecnologias de comunicação inter-sistemas possíveis para um CES (p. 85):

> "[...] when reestablishing the connection with the lane management service (LMS), there is a potential need for exchange of a considerable amount of stored data. While a file transfer mechanism can be selected, other implementations can be available through web services or **another inter-systems communication mechanism (FTP, RMI, .NET, MOM, etc.)**."

O MOM é também mencionado como alternativa ao RBI-OSGi no contexto de frameworks de comunicação distribuída (p. 48, §2.5):
> "The proposal of a remote batch invocation (RBI) [...] having advantages over **message-oriented middleware (MOM)**, sockets and synchronous and asynchronous OSGi remote services (ROSGi) evaluated against performance, expressiveness, reliability and cost-performance ratio."

**O ISoS é intencionalmente agnóstico relativamente à tecnologia de comunicação** — o `contentType` MIME-Type do GME é o mecanismo de seleção dinâmica do protocolo de comunicação adequado. MOM (ex: RabbitMQ) seria um valor possível de `contentType`, configurável por peer sem alterar a estrutura do CES.

### 5.2 Service Registry — Mecanismo Equivalente no ISoS

O ISoS não usa Consul nem um service registry externo. O mecanismo equivalente é o próprio `Isystem0` + `SelfAwareness()`:

- `Isystem0` age como **infraestrutura de acoplamento aberto adaptativo (OACI)** — "a generic logical bus connecting the enterprise Isystems" (p. 91);
- Qualquer Isystem pode fazer *lookup* de outro Isystem via `Isystem0` e obter credenciais/capabilities (p. 91–92);
- O `SelfAwareness()` é o único protocolo normalizado — tecnologia interna é opaca.

Esta arquitetura é comparada explicitamente ao ESB (Enterprise Service Bus), sendo apresentada como superior por evitar mediadores dedicados (pp. 80–81, 91).

### 5.3 Tolerância a Falhas — Posicionamento ISoS

O ISoS **não implementa diretamente** mecanismos de tolerância a falhas (p. 179, §7.2):

> "The ISoS framework does not directly introduce or cope with Fault tolerance mechanisms, namely providing transparency towards high availability of service implementation."

No entanto, a **autonomia de cada CES/Isystem** é o princípio arquitetural que *habilita* tolerância a falhas: se um Isystem falha, os seus peers mantêm o seu estado interno consistente (princípio de independência, p. 21). Mecanismos concretos (ex: ZooKeeper para eleição de líder) seriam implementados dentro de um CES especializado.

A distribuição é descrita como suporte à fiabilidade via protocolo Paxos para replicação de estado (p. 11, §1.2.2):
> "distribution can be used to improve reliability, e.g. by adopting **Paxos protocol** (consensus) for coordinating state replication [107]."

---

## 6. ESCALABILIDADE E QUALIDADE DE SERVIÇO

### 6.1 Escalabilidade no ISoS

A tese menciona escalabilidade como requisito e vantagem da distribuição (pp. 11, 21) mas sem definir mecanismos técnicos concretos:

> "The cloud computing and the elasticity mechanism to answer **scalability** requirement is another potential advantage for distribution beyond autonomy." (p. 11, §1.2.2)

> "ECoM [...] contributes to fulfilling the needs for a **scalable and cheaper** computational infrastructure." (p. 20, §1.4)

O ISoS é comparado aos microserviços no que toca a escalabilidade (p. 93, §4.2.2.4):
> "The CES elements are like microservices that can be instantiated on-premises or in the cloud computing infrastructure."

No entanto, o ISoS critica a abordagem microserviços por centrar-se excessivamente no cloud/escalabilidade sem um framework de responsabilidades computacionais bem definido (p. 93–94, §4.2.2.5).

### 6.2 QoS — Limitações Explícitas do ISoS

A tese é explícita sobre o que o ISoS **não garante** (p. 179, §7.2):

> "The ISoS framework does not manage predefined Elasticity mechanisms aiming at guaranteeing the quality of services (QoS) at the Isystem or CES level. For instance, if there are analytic Isystems with services requiring some Mexec maximum execution time [...] some load balancing mechanism can be (implicitly) activated."

> "The ISoS framework does not directly address Authentication and authorization. It can be defined as the responsibility of a traversal (common) specific Isystem."

### 6.3 Ciclo de Vida e Resiliência

O **ciclo de vida** de Isystems e CES é um dos princípios de design (p. 21, §1.5):

> "**Life-cycle management** — integrated life-cycle management considering procurement/development, deployment, operations, monitoring, and maintenance (including evolution, an update of new releases) needs to be established, related and coordinated with the adopted governance model and management tools."

A resiliência é tratada ao nível arquitetural: um CES que falha deve garantir que o estado (state data + historical data) pode ser exportado e importado por um CES substituto durante o processo de substituição (pp. 104–105, §4.6.1).

---

## 7. CONCEITOS ADICIONAIS RELEVANTES PARA DISCUSSÃO TEÓRICA

Os seguintes conceitos da tese **não se encaixam diretamente nas secções acima** mas são relevantes para enquadramento teórico no relatório:

1. **Heterogeneidade, Distribuição, Autonomia** (pp. 9–13, §1.2) — as três dimensões-chave que motivam o ISoS, rooted em [76] (Sheth & Larson, 1990). São o ponto de partida conceptual para justificar qualquer sistema distribuído como o do Trabalho 1.

2. **Vendor lock-in** (pp. 9, 37–38, §2.4.1) — problema central que o ISoS resolve via *external modularity* e substituibilidade. Relevante para discutir as vantagens de usar standards abertos como X/Open DTP.

3. **ESB vs. ISoS / integração endógena vs. exógena** (pp. 80–81, §4.1) — distinção entre adaptadores externos (ESB) e acoplamento adaptativo integrado (CES). Útil para posicionar RabbitMQ e ZooKeeper como CES especializados dentro do Isystem.

4. **Isystem0 como meta-coordenador** (pp. 90–92, §4.2.2.2–3) — análogo conceptual a um TM ou orquestrador de nível superior. Pode ser usado para justificar a necessidade de um componente central de coordenação como o TPLM.

5. **OACI — Open Adaptive Coupling Infrastructure** (p. 91) — conceito de bus lógico peer-to-peer sem mediador dedicado. Contrasta com abordagens de hub central; relevante para discutir trade-offs de arquitetura.

6. **CEDE — Collaborative Enterprise Development Environment** (pp. 131–139, §5.4) — plataforma de desenvolvimento unificado para Isystems. Relevante como analogia para a containerização com Podman: cada container é um CES com ciclo de vida independente.

7. **ECoM — Enterprise Collaboration Manager** (pp. 116–128, §5.2.1–5.3) — Isystem especializado na gestão de colaboração inter-organizacional. Pode ser usado para contextualizar a arquitetura do sistema de vetores como um ISoS intra-organização simplificado.

8. **Conformity Certification Process** (pp. 105–107, §4.6.2) — processo de certificação de conformidade de CES/Isystems contra modelos de referência. Analogia para os testes de invariante de somatório constante no contexto do Trabalho 1.

9. **GME — Generic Modeling Entity** (p. 85–86) — abstração de dados agnóstica de tecnologia via MIME-Type. Pode ser usada como analogia para o protocolo de serialização de mensagens em RabbitMQ (ex: JSON, Protobuf).

10. **Paxos / consenso distribuído** (p. 11, referência [107]) — mencionado como mecanismo de replicação de estado. Conceptualmente relacionado com ZooKeeper (que usa ZAB, protocolo baseado em Paxos).

---

*Extrato preparado para apoio ao Trabalho 1 de IESD 2025/26 — Mike, Abril 2026*
