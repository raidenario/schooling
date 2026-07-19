// Fluxo fluido: abre SEM matéria, diz o que quer aprender, e a matéria nasce
// da conversa (o servidor abre e emenda a entrevista de missão no mesmo turno).
import WebSocket from 'ws';

const ws = new WebSocket('ws://localhost:7777');
const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
setTimeout(() => fail('timeout de 420s'), 420000);

let materia = null, tokens = 0, text = '', boasVindas = null;

ws.on('open', () => {
  ws.send(JSON.stringify({type: 'start', subject: null}));
  setTimeout(() => ws.send(JSON.stringify({
    type: 'user_msg',
    text: 'Quero aprender TypeScript — preciso para um projeto no trabalho.',
  })), 500);
});

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail('erro do servidor: ' + m.text);
  if (m.type === 'info') {
    const mt = /matéria:\s*([^·]+)/.exec(m.text)?.[1]?.trim();
    if (mt && !materia) { materia = mt; console.log('matéria aberta pela conversa:', mt); }
    if (/diga o que|matérias:/.test(m.text)) boasVindas = m.text;
    return;
  }
  if (m.type === 'token') { tokens++; text += m.text; return; }
  if (m.type === 'done') {
    console.log(`boas-vindas sem matéria: ${boasVindas ? 'PASS' : 'FAIL'} (${boasVindas ?? '—'})`);
    console.log(`matéria identificada da mensagem: ${materia === 'typescript' ? 'PASS' : 'FAIL'} (${materia})`);
    console.log(`turno de missão emendado no mesmo turno: ${tokens > 0 ? 'PASS' : 'FAIL'} (${tokens} chunks)`);
    console.log('resposta:', text.slice(0, 140) + '…');
    const ok = boasVindas && materia === 'typescript' && tokens > 0;
    console.log(ok ? '\nONBOARDING-TEST PASS' : '\nONBOARDING-TEST FAIL');
    process.exit(ok ? 0 : 1);
  }
});
ws.on('error', (e) => fail(e.message));
