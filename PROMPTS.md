# Registo de Prompts — IESD Trabalho 01

## Sobre este ficheiro

Este ficheiro documenta os prompts utilizados ao longo do projeto, o fluxo de trabalho adotado, e a necessidade que levou à criação de cada prompt. O objetivo é duplo: suportar a execução deste projeto e servir de referência para projetos futuros com uma metodologia semelhante.

---

## Fluxo de Trabalho

O projeto é desenvolvido com dois agentes distintos, cada um com um papel diferente:

**Cowork (Claude)** — planeamento, decisões, preparação de prompts.
Usado para discutir arquitetura, tomar decisões tecnológicas, clarificar requisitos do enunciado, manter os ficheiros `plano.md` e `TODO.md` atualizados, e preparar os prompts a dar ao Claude Code antes de cada fase de implementação. Não escreve código diretamente no projeto.

**Claude Code (CLI)** — implementação.
Recebe prompts preparados no Cowork e escreve o código. Não toma decisões de arquitetura — essas chegam já tomadas no prompt. O ficheiro `CLAUDE.md` na raiz do projeto fornece contexto global persistente entre sessões do Claude Code.

**Princípio:** nenhuma fase de implementação começa no Claude Code sem o prompt ter sido preparado e revisto no Cowork primeiro.

---

## Estrutura de uma Task

Cada fase do projeto corresponde a uma task separada no Cowork. Cada task começa com um prompt inicial padronizado que aponta para os ficheiros de contexto do projeto, evitando repetição e garantindo que o agente parte sempre do estado atual do projeto.

**Padrão do prompt inicial de cada task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase X — [Nome da Fase]**, conforme descrito no plano.

**Porquê este padrão:** a informação de contexto vive nos ficheiros do projeto, não nos prompts. Isto significa que qualquer atualização ao plano ou às decisões fica automaticamente disponível na próxima task sem precisar de reescrever o prompt.

---

## Prompts de Início de Task por Fase

### Task: Planeamento e Decisões (esta task)

**Necessidade:** Antes de qualquer código, era necessário compreender o enunciado, clarificar decisões do professor, definir a stack tecnológica, estruturar o plano de trabalho e identificar o que podia ser reutilizado dos exemplos das aulas práticas.

**Prompt utilizado:** conversa exploratória livre — não havia ainda um prompt estruturado porque o projeto estava a ser definido. O resultado desta task é precisamente o `plano.md`, o `TODO.md` e este ficheiro `PROMPTS.md`.

**O que produziu:** `plano.md` (plano completo com fases, tarefas, ficheiros de suporte), `TODO.md` (decisões, observações para o relatório, to-dos por fase), `PROMPTS.md` (este ficheiro).

---

### Task: Fase 3 — Service.Vector

**Necessidade:** Implementar o Resource Manager base do sistema. É o primeiro serviço a escrever e serve de base para todas as fases seguintes. Existe um exemplo quase completo nas aulas práticas que deve ser estendido em vez de escrito do zero, para que o projeto pareça incremental.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase 3 — Service.Vector**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 4 — Service.TPLM

**Necessidade:** Implementar o coordenador de concorrência com 2PL. É o componente mais crítico do sistema e o único com requisito explícito de tolerância a falhas via ZooKeeper. Requer atenção especial à lógica de locks por `(vecId, index)` e à eleição de líder com z-nodes efémeros.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase 4 — Service.TPLM**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 5 — Service.TM

**Necessidade:** Implementar o Transaction Manager que coordena o 2PC entre os RMs. É o coração do modelo X/Open — mapeia as interfaces TX (para os clientes) e XA (para os RMs) para gRPC. Nenhum exemplo das aulas práticas cobre este componente: é implementado de raiz com base nas especificações TX e XA da X/Open.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase 5 — Service.TM**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 6 — Service.Client, Service.Validator e Integração

**Necessidade:** Ligar todas as peças e demonstrar o sistema a funcionar de ponta a ponta. O `Service.Validator` tem uma lógica específica e não óbvia: opera como um AP transacional que adquire read locks em todos os elementos antes de calcular o somatório, garantindo uma leitura consistente mesmo com outros clientes a executar em paralelo.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase 6 — Service.Client, Service.Validator e Integração**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 7 — ZooKeeper (failover)

**Necessidade:** Validar a tolerância a falhas do TPLM em condições reais — matar o líder durante uma transação ativa e verificar que o sistema recupera. Esta fase é maioritariamente de teste e observação, não de nova implementação.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase 7 — ZooKeeper (failover)**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 8 — Benchmarks

**Necessidade:** Produzir dados de desempenho para o relatório. O script de benchmark deve variar K (clientes) e M (vetores) e medir throughput, latência e taxa de aborts. Os resultados alimentam diretamente o Cap. 3 do relatório.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para implementar a **Fase 8 — Benchmarks**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 10 — Relatório

**Necessidade:** Redigir o relatório final. O Claude Code redige rascunhos com base no `plano.md` (estrutura de capítulos), no `TODO.md` (observações OBS-01 a OBS-06 que devem ser incluídas), e nos resultados dos benchmarks. Mike revê, complementa com reflexões pessoais, e finaliza.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para redigir o **relatório (Fase 10)**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

### Task: Fase 11 — Entrega

**Necessidade:** Organizar e empacotar o projeto para submissão. README com instruções de execução, estrutura do zip, verificação final.

**Prompt inicial da task:**
> Estou a trabalhar no IESD Trabalho 01, um demonstrador distribuído de transações com Java/Quarkus + gRPC. O projeto está em `C:\Isel\iesd_lab\Trabalho1`.
>
> Lê os ficheiros `plano.md` e `TODO.md` na raiz do projeto para teres contexto completo antes de fazermos qualquer coisa.
>
> Quando tiveres lido, quero que me ajudes a preparar os prompts para usar no Claude Code para a **Fase 11 — Entrega**, conforme descrito no plano.

**Prompts para o Claude Code:** *(a preencher durante a execução desta task)*

---

## Notas para Projetos Futuros

- O padrão de ter um ficheiro `plano.md` + `TODO.md` como fonte de verdade do projeto funciona bem: o prompt inicial de cada task é sempre curto porque o contexto vive nos ficheiros, não nos prompts.
- Separar as tasks por fase evita que conversas longas degradem a qualidade das respostas — cada task começa "fresca" mas com contexto completo via leitura de ficheiros.
- Preparar os prompts do Claude Code no Cowork antes de abrir o Claude Code evita que o agente tome decisões de arquitetura que ainda não foram discutidas.
- O `CLAUDE.md` na raiz do projeto é o mecanismo que dá ao Claude Code contexto global persistente entre sessões — vale a pena investir tempo a escrever um bom `CLAUDE.md` no início.
- Este ficheiro `PROMPTS.md` deve ser atualizado à medida que os prompts do Claude Code são escritos e refinados — o registo do que funcionou e o que não funcionou é tão valioso como o código produzido.
