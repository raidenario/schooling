# Spike — reasoning do GLM 5.2 pela stack embabel/Spring AI (2026-07-20)

Pergunta do gate ([ADR-0011](adr/0011-reasoning-seletivo-por-chamada.md)): dá para ligar/desligar o
thinking do GLM 5.2 (NVIDIA NIM, `chat_template_kwargs`) POR REQUEST através da
stack do School, com o raciocínio visível no streaming e sem quebrar a
interrupção? **Veredito: APROVADO (3/3), com wiring identificado e um caveat
crítico de token budget.**

## Critérios

**1. `chat_template_kwargs` por request — PASS (camada Spring AI, ao vivo).**
`OpenAiChatOptions.builder().extraBody({"chat_template_kwargs" {...}})` chega ao
NIM e muda o comportamento de fato, no bloqueante e no streaming
(`scratchpad/spike-live*.clj`, um run de cada):

| chamada | enable_thinking | completion tokens | conteúdo |
|---|---|---|---|
| bloqueante | true | **2048 (estourou o teto)** | `nil` — pensou o teto inteiro |
| bloqueante | false | 4 | `15h10` (correta) |
| streaming | true | 212 chunks | texto ok + **1906 chars de reasoning** |

**2. `reasoning_content` no streaming — PASS.** O Spring AI 1.1.7 entrega o
raciocínio POR CHUNK em `metadata["reasoningContent"]`, separado do texto — dá
para renderizar o "pensando" esmaecido no TUI sem poluir o conteúdo. (No
bloqueante o Spring AI não expõe o campo — irrelevante: nos pontos ON
bloqueantes queremos o efeito na qualidade, não o texto do raciocínio.)

**3. ESC interrompe — PASS por construção.** O `stream!` já opera
subscribe/dispose sobre um Flux; nenhuma das rotas de wiring muda esse
mecanismo. Re-validar ao vivo na implementação.

## O wiring pelo embabel (bytecode + reflexão)

- O `StandardOpenAiOptionsConverter` do embabel 0.5.0-SNAPSHOT (e do 1.0.0) só
  repassa temperature/topP/maxTokens/penalties — `LlmOptions.withThinking` e
  `withExtension` **morrem no conversor**. O yml de modelos não tem gancho
  (`specialHandling` = só `supportsTemperature`).
- **O seam existe**: `OpenAiCompatibleModelFactory.openAiCompatibleLlm(name,
  pricing, provider, date, OptionsConverter)` aceita conversor CUSTOMIZADO.
  Registrar o LlmService do School por aí (via `:sources` do platform.clj —
  mesma técnica de gen-class anotada da boot class do embabel-clj) e o
  conversor lê `LlmOptions.getThinking()`/extensions → `extraBody`.
- O embabel 0.5.0 já tem API de streaming com pensamento
  (`createObjectStreamWithThinking` → `Flux<StreamingEvent<T>>`, com
  `StreamingEvent.Thinking`); o `generateStream` (String) usado hoje descarta o
  reasoning. Para a prosa com pensando visível: Flux do Spring AI direto no
  `stream!` (prosa não usa tools) ou a API de StreamingEvent.

## Caveat crítico — reasoning come o max_tokens

Com `enable_thinking true` e effort default ("max"), o modelo gastou os 2048
tokens INTEIROS pensando e não respondeu. Implementação DEVE, nos pontos ON:
subir `max-tokens` com folga E capar o pensamento (`reasoning_effort` /
budget nos kwargs) — em especial na prosa, onde pensamento longo atrasa o
primeiro token visível.

## Fallback (não acionado)

HTTP direto ao NIM (http-kit + cheshire, já no deps) nos pontos ON. Fica
documentado; os 3 critérios passaram sem ele.
