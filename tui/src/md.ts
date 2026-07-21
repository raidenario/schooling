// Mini-renderer de markdown → ANSI para o chat do professor.
// Determinístico e tolerante a streaming: marcação ainda ABERTA no fim do
// buffer (** ou ` sem par) fica crua até o par chegar; cerca ``` aberta é
// estilizada como código até fechar (é código chegando, afinal).
// Cobre o que o professor realmente emite: **negrito**, `código`, cercas,
// títulos e listas. Itálico de asterisco único fica de fora de propósito
// (conflita com bullets e quase não aparece).

const BOLD = '\x1b[1m';
const BOLD_OFF = '\x1b[22m';
const CODE = '\x1b[36m'; // ciano
const FG_OFF = '\x1b[39m';

function inline(line: string): string {
  return line
    .replace(/`([^`\n]+)`/g, `${CODE}$1${FG_OFF}`)
    .replace(/\*\*(.+?)\*\*/g, `${BOLD}$1${BOLD_OFF}`);
}

function bloco(texto: string): string {
  return texto
    .split('\n')
    .map(linha => {
      const h = /^#{1,4}\s+(.*)$/.exec(linha);
      if (h) return BOLD + h[1] + BOLD_OFF;
      return inline(linha.replace(/^(\s*)[-*]\s+/, '$1• '));
    })
    .join('\n');
}

export function mdAnsi(texto: string): string {
  // segmentos ímpares são cercas de código (uma cerca aberta no fim do
  // stream deixa o último segmento ímpar — estilizado como código, correto)
  return texto
    .split('```')
    .map((parte, i) => {
      if (i % 2 === 1) {
        // remove a linha de linguagem da cerca (```clojure); o \n que segue a
        // cerca de fechamento vive no PRÓXIMO segmento — nada a acrescentar
        const corpo = parte.replace(/^[a-z0-9-]*\n/, '');
        return CODE + corpo.replace(/\n$/, '') + FG_OFF;
      }
      return bloco(parte);
    })
    .join('');
}
