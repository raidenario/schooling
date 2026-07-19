// Critério 4 isolado: interromper a aula no meio do stream.
import WebSocket from 'ws';

const ws = new WebSocket('ws://localhost:7777');
let tokens = 0, interruptSent = false;

const fail = (msg) => { console.error('FAIL:', msg); process.exit(1); };
setTimeout(() => fail('timeout de 300s'), 300000);

const interrupt = () => {
  if (interruptSent) return;
  interruptSent = true;
  ws.send(JSON.stringify({type: 'interrupt'}));
  console.log(`interrupt enviado (apos ${tokens} chunks)`);
};

ws.on('open', () => {
  ws.send(JSON.stringify({
    type: 'user_msg',
    text: 'Explique em MUITOS detalhes, longamente, a historia completa da programacao funcional desde o calculo lambda.',
  }));
  // interrompe no 2º chunk, ou aos 45s se nada chegar (interrupção pré-token)
  setTimeout(interrupt, 45000);
});

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'info' || m.type === 'records') return;
  if (m.type === 'error') fail('erro do servidor: ' + m.text);
  if (m.type === 'token') {
    tokens++;
    if (tokens === 2) interrupt();
  } else if (m.type === 'done') {
    if (!interruptSent) fail('done sem interrupt enviado?');
    if (m.interrupted !== true) fail('done nao veio marcado como interrompido');
    console.log(`criterio 4 (interrupcao): PASS (${tokens} chunks antes de parar)`);
    process.exit(0);
  }
});

ws.on('error', (e) => fail(e.message));
