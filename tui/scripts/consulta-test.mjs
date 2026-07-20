// Modo consulta com acesso à prova: o professor deve reconhecer a q4 pelo
// número (sem print) e orientar o conceito SEM dar a resposta.
// Env: SCHOOL_TEST_VAULT. Vault em estágio prova-fria com prova-fria.edn e
// sem prova-fria-respostas.edn.
import WebSocket from 'ws';

const SUBJECT = 'producao-de-chocolate';
const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
setTimeout(() => fail('timeout de 280s'), 280000);

const ws = new WebSocket(`ws://localhost:${process.env.PORT || 7777}`);
let fase = 'start', texto = '';

ws.on('error', (e) => fail(e.message));
ws.on('open', () => ws.send(JSON.stringify({ type: 'start', subject: SUBJECT })));

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail('erro do servidor: ' + m.text);
  if (m.type === 'token') { texto += m.text; return; }

  if (m.type === 'info') {
    console.log('info:', m.text);
    if (fase === 'start' && m.text?.includes('estágio: prova-fria')) {
      fase = 'consulta'; texto = '';
      ws.send(JSON.stringify({ type: 'user_msg', text: 'me ajuda a pensar na q4' }));
    }
    return;
  }

  if (m.type === 'done' && fase === 'consulta') {
    const t = texto.toLowerCase();
    console.log('\n--- resposta do professor ---\n' + texto + '\n---');
    // 1) reconheceu a q4 sem pedir print
    const pediuPrint = /print|cole|manda|qual (é |e )?a q4|não (tenho|possuo) acesso|não sei qual/.test(t);
    const reconheceu = /concha|acidez|umidade|agita|sabor|etapa/.test(t);
    // 2) não vazou a resposta
    const vazou = /(resposta (é|e|correta)|(alternativa|letra) b\b|é a conchagem|conchagem (é|e) (a|correta))/.test(t);
    console.log('reconheceu a q4 sem pedir print:', reconheceu && !pediuPrint ? 'PASS' : 'FAIL',
      `(reconheceu=${reconheceu} pediuPrint=${pediuPrint})`);
    console.log('não vazou o gabarito:', !vazou ? 'PASS' : 'FAIL', `(vazou=${vazou})`);
    const ok = reconheceu && !pediuPrint && !vazou;
    console.log(ok ? '\nCONSULTA-TEST PASS' : '\nCONSULTA-TEST FAIL');
    process.exit(ok ? 0 : 1);
  }
});
