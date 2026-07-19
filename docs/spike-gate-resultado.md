# Spike-gate — resultado (2026-07-19)

Veredito do gate do [ADR-0003](adr/0003-tudo-embabel-com-spike-gate.md)/[ADR-0006](adr/0006-backend-em-clojure-sobre-embabel-clj.md): **APROVADO, 4/4**. O caminho "Embabel de ponta a ponta via embabel-clj" segue sem plano B.

Setup: backend Clojure (`backend/`, embabel-clj + embabel-agent 0.5.0-SNAPSHOT) → http-kit WebSocket → testes `tui/scripts/*.mjs` (o cliente Ink usa o mesmo protocolo). LLM: qwen/qwen3.5-397b-a17b via NVIDIA (free tier).

| # | Critério | Resultado | Evidência |
|---|---|---|---|
| 1 | Streaming token-a-token | **PASS** | `StreamingPromptRunnerBuilder → .streaming() → .withMessages() → generateStream()` → `Flux<String>`; 11 chunks progressivos, spread 68s (spike-test turno 1) |
| 2 | Tool use mid-conversa | **PASS** | `registrar_learning_record` (embabel-clj tools + malli) chamada pelo modelo durante a aula: "O aprendiz compreendeu corretamente que closures…" |
| 3 | System prompt integral | **PASS** | `SystemMessage` nosso na íntegra; a palavra-chave ABACAXI-42 voltou no turno 2 |
| 4 | Interrupção | **PASS** | `future-cancel` → `InterruptedException` no `.await` → `.dispose` do Flux → `done {interrupted: true}`; 2 chunks antes de parar (interrupt-test) |

## Observações e lições

- **A API de streaming existe e é limpa no 0.5.0-SNAPSHOT** (`com.embabel.agent.api.common.streaming`) — não existia no 0.4.0. O `.withMessages` aceita histórico completo (`SystemMessage`/`UserMessage`/`AssistantMessage`), o que já resolve a conversa multi-turno da Fase 1.
- **Granularidade dos chunks**: ~11 chunks/resposta com ~6s entre eles é o free tier da NVIDIA enfileirando, não buffering do Embabel — o mecanismo é progressivo. Validar granularidade fina com provider pago antes de julgar UX.
- **Lição de campo**: parcial vazio de um turno interrompido não pode entrar no histórico — `AssistantMessage` rejeita `""` ("Text content cannot be empty") e quebra todos os turnos seguintes. Corrigido no `record!` do `spike_embabel.clj`.
- A interrupção completa o processo GOAP normalmente (condição satisfeita, flag no blackboard) em vez de deixar a exception subir para o Embabel — evita retry indevido da action e o status FAILED.

## Próximo passo

Fase 1 (núcleo de ensino): evoluir `spike_embabel.clj` para o agente School real — entrevista de missão, prova fria, DIAGNOSIS.md/CURRICULUM.md no vault (formatos do ADR-0004), eventos de domínio desde o dia 1 (ADR-0005).
