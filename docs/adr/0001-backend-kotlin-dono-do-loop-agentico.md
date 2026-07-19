---
status: amended by ADR-0006 — a decisão de arquitetura (loop no backend, TUI cliente fino) permanece; a linguagem do backend mudou de Kotlin para Clojure
---

# Backend Kotlin é o dono do loop agêntico

O School tem frontend TUI em TypeScript (Ink) e backend Kotlin (Embabel + SDK Java da Anthropic). O loop agêntico — chamadas LLM, tool use, streaming — roda inteiro no backend Kotlin; o TUI é um cliente fino que renderiza stream e captura input via SSE/WebSocket. Decidimos assim para que o produto (orquestração GOAP, diagnóstico, provas) viva na stack principal do autor e os frontends sejam substituíveis (web, Obsidian, mobile futuramente); a API key da Anthropic vive só no backend.

## Considered Options

- **TUI como dono do loop (estilo Claude Code)** — rejeitado: o Kotlin viraria um servidor MCP acessório e o Embabel perderia a orquestração, regredindo à arquitetura TS que foi deliberadamente descartada.
- **Ambas as superfícies desde a v1** — rejeitado por custo de manutenção antes de existir usuário; exposição MCP para rodar dentro de Claude Code/Codex fica para a v2.
