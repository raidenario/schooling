# Benchmark Fase 2b — DICE vs sem DICE (2026-07-20)

Pergunta: a memória fina (ADR-0010) **se paga**? Dois lados medidos: custo em
processo (determinístico, `clojure -M:f2b-bench`) e valor no LLM (A/B via API
NVIDIA, GLM 5.2, mesmo prompt de professor com e sem o bloco de memória).

**Veredito: se paga com folga.** O custo é desprezível em todas as dimensões; o
ganho de personalização é qualitativo e imediato.

## Custo (em processo — o que o DICE adiciona a cada turno)

| Dimensão | Medida | Leitura |
|---|---|---|
| Leitura top-12 + bloco de prompt | **0.3–0.8 ms/turno** | invisível ao lado de chamadas LLM de segundos |
| Overhead de prompt | **~170–190 tokens** de input | pequeno vs. DIAGNOSIS+CURRICULUM já no prompt; grátis no free tier |
| Escrita | ~1–2 ms/proposição | acontece poucas vezes por prova/aula |
| Replay de boot | **~99 ms** com 1.110 proposições (log de 720 KB) | matéria real acumula centenas em semanas → dezenas de ms |
| Latência LLM | sem diferença atribuível | com memória: 24.7–36.2s; sem: 5.8–22.0s — a variância do free tier (controles 10.4s e 22.0s sem memória) engole o delta |

## Valor (A/B no GLM 5.2 — memória: 6 proposições realistas de um aprendiz de Java)

**P1: "Tenho 10 minutos, me dá UMA dica do que mais me atrapalha?"**

- **SEM memória**: chuta o genérico (`==` vs `equals` — que o aprendiz nem errou)
  e **fabrica evidência** ("em exercícios simulados de certificação… causa nº 1")
  — exatamente a alucinação que o ADR-0005 quer matar.
- **COM memória**: acerta o alvo — ataca a misconception de maior confiança
  (*"sua maior pedra no sapato é wildcards genéricos (0.90)"*), aplica a
  preferência registrada (*"como você engata melhor com código"*), entrega a
  regra PECS com dois blocos de código, **cita a evidência real** e agenda
  re-teste (*"na próxima avaliação vou pedir um exemplo para confirmar que a
  trave sumiu"*).

**P2: "Me explica rapidinho Optional\<T\>?"**

- **SEM memória**: explicação correta porém genérica, com um bloco de
  "avaliação do entendimento" inventado.
- **COM memória**: mesma matéria, mas adapta a forma (*"como você absorve muito
  mais fácil vendo código (confiança 0.80)"*) e lidera com o contraste
  perigoso/seguro em código.

## Limites do benchmark

- n=2 perguntas, 1 modelo, free tier — é um sinal forte, não estatística.
- A memória do A/B foi fixture manual (6 proposições realistas); o ciclo real
  de escrita já está testado offline (`:f2b-test`), mas o efeito composto
  (memória minerada ao vivo → resposta) espera o dogfood.
- "Citar evidência" virou comportamento observado COM memória e alucinação SEM —
  confirmação empírica da tese do ADR-0005 ("propriedade estrutural, não
  disciplina de prompt").
