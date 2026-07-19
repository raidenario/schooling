# Prosa no vault, agenda no SQLite

O estado do aprendiz é dividido por natureza do dado, não por feature. O vault Obsidian (markdown) é a fonte de verdade de todo o conhecimento em prosa: diagnóstico, currículo, notas, provas, gabaritos e o conteúdo dos flashcards. Um SQLite local embutido no backend — deliberadamente fora de pastas sincronizadas (OneDrive) — guarda apenas o agendamento FSRS e o log de reviews. Regras duras: nenhuma prosa no SQLite, nenhum scheduling no markdown; o backend relê o vault antes de qualquer decisão, então edições manuais no Obsidian sempre vencem.

## Consequences

- Perder o SQLite é recuperável (re-agendar cards); perder prosa não seria — por isso a prosa fica no vault versionado/sincronizado.
- Mantém compatibilidade com as skills existentes (/teach, agent-schools), que tratam o vault como verdade.
- Writes de alta frequência (ex.: 40 reviews em minutos) nunca tocam o OneDrive, evitando conflitos de sync.
