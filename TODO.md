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

## ✅ To-Do's

### Transversais
- [ ] Começar a escrever o relatório **em paralelo** com a implementação — as secções teóricas (Cap. 2: fundamentos, X/Open DTP, 2PC, 2PL, ZooKeeper, MOM, Consul) podem ser redigidas antes do código estar terminado. Não deixar o relatório para o fim.
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
