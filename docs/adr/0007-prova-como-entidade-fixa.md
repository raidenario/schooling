# Prova é uma entidade fixa: o LLM gera dados, o renderer gera a experiência

Provas deixam de ser HTML livre gerado pelo LLM. A Prova vira entidade estruturada: o LLM produz **dados validados por malli** (questões de alternativa com correta + explicação), e um **renderer fixo em código** gera o HTML — gamificado e didático (vibe Duolingo), com alternativa "🤷 Não sei" obrigatória em toda questão, campo de justificativa por resposta, e botão **Concluir** que envia as respostas por POST ao próprio backend (localhost:7777). O gabarito vive só no servidor (`prova.edn`); o HTML nunca o contém. A correção de alternativas é **código** (comparação determinística — fim de score por regex em prosa de LLM); o LLM entra só onde julga: diagnóstico a partir dos erros e das justificativas.

Dois comportamentos do professor completam a entidade:

- **Calibragem pré-prova**: antes de gerar a prova fria, o professor pergunta conceitos-chave da matéria ("você conhece ownership? borrowing? GC?") e grava `calibragem.md`; a prova é dosada por isso — o aprendiz nunca cai num "isso está grego para mim".
- **Modo consulta**: enquanto uma prova está aberta, o chat vira consultoria socrática — o professor NUNCA dá a resposta nem elimina alternativas; orienta até o aprendiz chegar sozinho. As respostas chegam pelo Concluir, não pelo chat; a correção dispara automaticamente do POST.

## Considered Options

- **HTML livre por prova (status quo)** — rejeitado: qualidade/estrutura variam por geração, respostas viajam pelo chat (frágil de parsear), gabarito dependia de prosa do LLM, e "concluir" não existia.
- **Gabarito embutido no HTML (oculto via JS)** — rejeitado: trivialmente vazável com F12; no servidor é estruturalmente honesto.

## Consequences

- Justificativa + "Não sei" viram sinal rico de diagnóstico (erro confiante ≠ dúvida honesta).
- O mesmo molde serve prova fria, provas de consolidação e capstone-defesa.
- O backend ganha superfície HTTP além do WebSocket (GET da prova, POST das respostas) — primeiro passo natural para a Fase 3.
