# TODO — IESD Trabalho 01
## Repositório de Observações e Tarefas Futuras

---

## 📌 Observações

### OBS-01 — MOM: apenas discussão, NÃO implementar

Confirmado pelo professor em aula: o MOM **não deve ser implementado**. A comunicação entre serviços será feita diretamente via **gRPC**. O MOM deve apenas ser discutido no relatório (Cap. 2/3) como alternativa de desacoplamento temporal e espacial face ao RPC/REST direto — discutindo trade-offs de latência, resiliência e complexidade.

Tópico obrigatório no relatório: *"Aplicabilidade do MOM na interação entre Service.client, Service.vector, Service.tm, Service.tplm"*.

---

### OBS-02 — Stack tecnológica: Java/Quarkus + gRPC

Decisão tomada:
- **Linguagem/framework principal:** Java com Quarkus
- **Protocolo de comunicação:** gRPC (comunicação direta entre serviços)
- **Consul:** apenas discussão no relatório (transparência à localização), sem implementação
- A comparação de desempenho entre linguagens (Java vs Python vs .NET) é **opcional** (Fase 9.3 do plano) — pode ser abordada teoricamente no relatório; benchmarks serão feitos variando K (clientes) e M (vetores) em vez de linguagens

---

### OBS-03 — ZooKeeper: implementar em todos os serviços onde faça sentido

Confirmado pelo professor: *"Implementar ZooKeeper em todos os serviços onde faça sentido"*. Não é apenas para deteção de falhas — também guarda estado. O uso principal é na tolerância a falhas do **TPLM** via eleição de líder. Nota do professor: ZooKeeper guarda estado e deteta falhas, mas isso não implica que deva ser usado para manter o estado de todos os serviços — avaliar caso a caso.

---

### OBS-04 — Granularidade dos locks no TPLM

Confirmado pelo professor: os "elementos de dados" no contexto do TPLM são **cada posição de cada vetor** (i.e., lock granularity = (vectorId, index)).

---

### OBS-05 — Especificações TX e XA (X/Open)

O professor partilhou os documentos oficiais da X/Open:
- **TX Spec** (90 páginas): define a interface AP ↔ TM — funções `tx_begin()`, `tx_commit()`, `tx_rollback()`, `tx_open()`, `tx_close()`, `tx_info()`, etc.
- **XA Spec** (94 páginas): define a interface TM ↔ RM — funções `xa_start()`, `xa_end()`, `xa_prepare()`, `xa_commit()`, `xa_rollback()`, `xa_recover()`, etc.

Estas specs definem os contratos que o TM e os RMs devem respeitar. A implementação deve inspirar-se nestas interfaces (adaptadas para gRPC).

---

### OBS-06 — Service.Validator: verificação transacional do invariante (incluir no relatório)

O `Service.Validator` não é um leitor simples — é um **AP transacional** que adquire read locks via TPLM antes de calcular o somatório. Isto é necessário porque, em execução concorrente, uma leitura não protegida por locks poderia coincidir com uma transferência a meio: o valor já foi debitado na origem mas ainda não foi creditado no destino, dando uma leitura inconsistente.

Ao executar a verificação dentro de uma transação com read locks em todos os `(vecId, index)`, o 2PL garante que nenhuma transação de escrita está em progresso nesse momento — a leitura é serializada.

**Para o relatório (Cap. 2/3):** este é um exemplo concreto e próprio do trabalho que ilustra a interação entre 2PL e consistência de leituras. Merece um parágrafo dedicado, eventualmente com um diagrama de sequência mostrando o contraste entre leitura não protegida vs. leitura transacional.

---

### OBS-08 — Implementação feita pelo David num repositório separado

A implementação do código está a ser desenvolvida pelo David Marcelino num repositório separado:
`https://github.com/DavidmMar/IESD-2526SV` (pasta `tp1/`).

Serviços já implementados: `CSerDvector`, `TransactionManager`, `TwoPhaseLockManager`, `CSerDvectorCli`.
Em falta: integração do ZooKeeper no TPLM, `Service.Validator`, `podman-compose.yml` unificado, benchmarks.
Desvio ao plano: locks por vetor inteiro em vez de por `(vecId, index)`.

**Se a implementação do David não satisfizer todos os requisitos do enunciado**, será feita uma nova iteração sobre essa implementação com o Claude Code. Para já, o foco é o relatório.

---

### OBS-07 — Estratégia para o relatório: seguir as diretivas do professor à letra

O professor Luís Osório avalia o relatório **"na base da discussão e da qualidade do relatório"**. A experiência mostra que este professor valoriza fortemente o trabalho que vai de encontro àquilo que ele instrui — a opinião dos alunos, mesmo que válida, tem pouco peso. O objetivo pragmático é passar à UC.

**Regra de trabalho:** ao produzir conteúdo para o relatório, o critério principal é cobrir cada tópico que o professor listou explicitamente no enunciado — sem omissões, sem reordenações. A estrutura de capítulos e a lista de tópicos do enunciado são a lei. A discussão deve ser orientada para o que o professor quer ouvir, não para aquilo que seria a melhor análise técnica independente.

Tópicos obrigatórios conforme o enunciado (pág. 2):
- Discussão do problema: coordenação centralizada/distribuída, modelo X/Open, coordenação da concorrência
- Dificuldade de sistemas com múltiplos elementos Serviço (modelos de programação IESD e Comp. Distribuída)
- Aplicabilidade do MOM na coordenação entre serviços
- Possibilidade de serviços em diferentes tecnologias (heterogeneidade)
- Escalabilidade (número elevado de clientes e vetores)
- Confrontação ACID com LLT/Saga
- Consul (service registry, transparência à localização)
- Validação de desempenho (transações por unidade de tempo, tempos de resposta)
- Tolerância a falhas / resiliência / qualidade
- Gestão do ciclo de vida dos elementos de infraestrutura
- Hipótese de quadro tecnológico diferenciado (Java, .NET, Python, etc.)

---

## ✅ To-Do's

### 🔴 EM CURSO — Relatório (Cap. 2 primeiro)

O fluxo decidido: avançar com o **Capítulo 2 antes de qualquer outro** — é o único capítulo que não depende da implementação do David nem de código a analisar. Tudo o que precisa é de fundamentação teórica e das decisões arquiteturais já tomadas.

**Sequência de trabalho para o relatório:**

1. **Cap. 2 — Fundamentação** ← a fazer agora, com o Claude no Cowork
   - [ ] 2.1 Coordenação de Transações Distribuídas — Modelo X/Open (2PC, 2PL, TX/XA)
   - [ ] 2.2 Dificuldade com múltiplos elementos Serviço
   - [ ] 2.3 Aplicabilidade do MOM
   - [ ] 2.4 Heterogeneidade tecnológica
   - [ ] 2.5 Escalabilidade
   - [ ] 2.6 ACID vs. LLT/Saga
   - [ ] 2.7 Consul / Service Registry

2. **Cap. 1 — Introdução** ← escrever após Cap. 2 (a intro resume o que foi escrito)
   - [ ] Síntese do documento
   - [ ] Estrutura do documento

3. **Cap. 3 — Arquitetura e Demonstrador** ← depende de analisar o código do David
   - [ ] Analisar implementação do David (ficheiros Java + protos)
   - [ ] 3.1 Visão geral + diagrama de arquitetura
   - [ ] 3.2 Estratégia e implementação (descrever cada serviço com excertos de código)
   - [ ] 3.3 Tolerância a falhas (ZooKeeper)
   - [ ] 3.4 Validação de desempenho
   - [ ] 3.5 Ciclo de vida / podman-compose
   - [ ] 3.6 Heterogeneidade tecnológica (quadro diferenciado)
   - [ ] 3.7 Escalabilidade

4. **Cap. 4 — Conclusões** ← escrever no fim, após Cap. 2 e 3 completos
   - [ ] Síntese
   - [ ] Dificuldades
   - [ ] Melhorias e trabalho futuro
   - [ ] Referências

5. **Revisão final e entrega**
   - [ ] Verificar cobertura de todos os tópicos do enunciado (OBS-07)
   - [ ] Compilar PDF no Overleaf
   - [ ] Empacotar `iesd2526-g06.zip` (código do David + relatório PDF)
   - [ ] Submeter no Moodle até 19 de abril de 2026

---

### Transversais
- [ ] Criar `CLAUDE.md` na raiz do projeto com contexto global (stack, decisões, estrutura) para que o Claude Code não precise de ser recontextualizado em cada sessão.

### Fase 3 — Service.Vector
- [ ] **Preparar prompts para o Claude Code** (fazer aqui no Cowork antes de abrir o Claude Code): contexto do projeto, ficheiros a reutilizar (`DvectorGrpcService.java`, `dvector.proto`), o que implementar (staging area por txId, operações XA), o que não fazer (sem MOM, sem Consul).
- [ ] Implementar Fase 3 no Claude Code.

### Fase 4 — Service.TPLM
- [ ] **Preparar prompts para o Claude Code**: contexto, ficheiros ZooKeeper de suporte (`ZkClientService.java`, `ZkQuorumOrchestrator.java`), lógica de locks por `(vecId, index)`, eleição de líder com z-nodes efémeros, estratégia de deadlock por timeout.
- [ ] Implementar Fase 4 no Claude Code.

### Fase 5 — Service.TM
- [ ] **Preparar prompts para o Claude Code**: contexto, mapeamento das interfaces TX/XA para gRPC, fluxo 2PC (prepare → commit/abort), log persistente para recuperação, coordenação com TPLM para `releaseLocks`.
- [ ] Implementar Fase 5 no Claude Code.

### Fase 6 — Service.Client + Service.Validator + Integração
- [ ] **Preparar prompts para o Claude Code**: ciclo transacional do cliente, lógica do Validator como AP transacional (read locks em todos os elementos antes de somar), 3 momentos de verificação do invariante, `podman-compose.yml` unificado.
- [ ] Implementar Fase 6 no Claude Code.

### Fase 7 — ZooKeeper (failover)
- [ ] **Preparar prompts para o Claude Code**: testar failover do TPLM, reutilizar configuração do cluster da AP3, documentar comportamento observado para o relatório.
- [ ] Implementar Fase 7 no Claude Code.

### Fase 8 — Benchmarks
- [ ] **Preparar prompts para o Claude Code**: script de benchmark variando K e M, métricas a recolher (throughput, latência, taxa de aborts), formato de output para tabelas/gráficos no relatório.
- [ ] Implementar Fase 8 no Claude Code.

### Fase 10 — Relatório
- [ ] **Preparar prompts para o Claude Code**: estrutura do relatório (ver plano.md Fase 10), tópicos obrigatórios, observações do TODO.md a incluir (OBS-01 a OBS-06), resultados dos benchmarks.
- [ ] Redigir relatório com Claude Code; Mike revê e complementa.

### Fase 11 — Entrega
- [ ] **Preparar prompts para o Claude Code**: README com instruções de execução, organização do zip.
- [ ] Empacotar e submeter no Moodle.

---
