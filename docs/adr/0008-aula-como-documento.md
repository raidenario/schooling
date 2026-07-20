# Aula é um documento com ciclo de entendimento, não despejo no chat

Explicação detalhada ("me explica do zero") deixa de ser streamada no TUI — markdown com `$$...$$` e ASCII art num terminal é ilegível. A Aula vira entidade, na mesma divisão de trabalho do ADR-0007: o LLM gera o **corpo** (fragmento HTML rico — SVG inline, callouts, tabelas, código; sem script/LaTeX), e a **casca é código fixo** (`school.aula`): tipografia de leitura, tema, e o formulário "✍️ Agora é com você" que POSTa o **Entendimento** — o que o aprendiz escreve com as próprias palavras — de volta ao backend.

O ciclo fecha com avaliação + decisão:

- O professor julga o Entendimento (LLM, estruturado): **sólido | parcial | confuso**, com lacunas e feedback.
- A decisão do próximo passo é **código**, pelos pesos: aula tem `peso` de complexidade 1-5 (estimado na geração). `confuso` → re-explica (documento novo, ângulo obrigatoriamente diferente; máx. 2 documentos, depois destrava no chat). Compreendido com `peso >= 4` → prova de consolidação imediata (assunto grande). Senão acumula: soma de pesos compreendidos `>= 8` no módulo → prova; abaixo disso, segue para o próximo conteúdo. A prova zera o acúmulo.

Detecção é pré-stream: um check estruturado barato antes de streamar decide se o turno pede documento (pedido explícito de explicação do zero/detalhada) — evita o despejo acontecer antes de qualquer correção. Enquanto uma aula está aberta, o chat entra em **modo leitura** (tira-dúvidas pontual, sem re-explicar o documento); o Entendimento chega pela página, como as respostas de prova.

## Considered Options

- **Continuar streamando explicações no chat (status quo)** — rejeitado: TUI não renderiza matemática/diagramas; explicação longa não tem checkpoint de compreensão — o professor só descobre que não colou na prova.
- **LLM gera a página inteira** — rejeitado pelas mesmas razões do ADR-0007: o formulário/POST é contrato e não pode variar por geração; a casca fixa garante o ciclo.
- **Prova após toda aula** — rejeitado: para tópico leve é atrito puro; o peso acumulado dosa a frequência de provas pelo tamanho real do conteúdo coberto.
- **Entendimento pelo chat** — rejeitado: a página é o contexto da leitura (o aprendiz escreve olhando o próprio documento) e o POST estruturado dispara a avaliação sem parsing de prosa.

## Consequences

- Estado novo no vault: `aula-aberta.edn` (matéria) + registros `modules/<m>/aulas/NN-<topico>.edn`/`html` e `aulas-acumulado.edn` — o histórico de aulas e entendimentos vira insumo de diagnóstico.
- Rotas novas: `GET /aula/<slug>`, `POST /entendimento/<slug>`.
- Eventos novos: `:aula-gerada`, `:entendimento-recebido`, `:aula-avaliada`.
- Um check estruturado extra por turno de ensino (pré-stream) — latência pequena a mais em troca de nunca despejar.
- Teste vivo: `tui/scripts/aula-test.mjs` (ciclo completo contra vault descartável).
