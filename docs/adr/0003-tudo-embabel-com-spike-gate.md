---
status: amended by ADR-0006 — Embabel de ponta a ponta e o spike-gate permanecem; o acesso ao framework agora é via embabel-clj (Clojure), e o plano B do spike mudou (ver 0006)
---

# Embabel de ponta a ponta, validado por spike com gate objetivo

Toda a camada agêntica do backend — orquestração macro (GOAP: diagnosticar, planejar, ensinar, examinar, adaptar) **e** a conversa de ensino — roda sobre Embabel/Spring AI, em vez da costura híbrida (Embabel no macro, SDK Java da Anthropic no micro) que era a alternativa. Escolhemos maximizar a aposta no framework e a coerência da stack, aceitando lock-in num framework 0.3.x.

A decisão é condicionada a um **spike de retirada na semana 1**: uma aula mínima streamada de ponta a ponta (Embabel → SSE → TUI Ink) com quatro critérios binários — (1) streaming token-a-token fluido, (2) tool use no meio da conversa, (3) controle integral do system prompt do professor, (4) interrupção pelo usuário. Falha em qualquer critério → recuo imediato para a costura híbrida, sem renegociação.

## Consequences

- O domínio (currículo, diagnóstico, provas) continua dependendo apenas de interfaces próprias — o recuo, se houver, fica confinado à camada de integração LLM.
- A migração do Spring AI 2.0 para GA é custo já orçado; versões do Embabel ficam pinadas.
