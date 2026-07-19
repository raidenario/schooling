// Teste do cano WS do School: streaming progressivo + interrupção.
import WebSocket from 'ws';

const ws = new WebSocket('ws://localhost:7777');
let phase = 'stream'; // depois 'interrupt'
let tokens = 0;
let firstTokenAt = null;
let lastTokenAt = null;
let interruptSent = false;
let tokensAfterInterrupt = 0;

const fail = (msg) => { console.error('FAIL:', msg); process.exit(1); };
setTimeout(() => fail('timeout de 30s'), 30000);

ws.on('open', () => ws.send(JSON.stringify({type: 'user_msg', text: 'oi professor'})));

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'info') return;
  if (m.type === 'error') fail('erro do servidor: ' + m.text);

  if (phase === 'stream') {
    if (m.type === 'token') {
      tokens++;
      const now = Date.now();
      firstTokenAt ??= now;
      lastTokenAt = now;
    } else if (m.type === 'done') {
      if (m.interrupted) fail('turno 1 nao deveria ser interrompido');
      if (tokens < 10) fail('poucos tokens: ' + tokens);
      const spreadMs = lastTokenAt - firstTokenAt;
      if (spreadMs < 200) fail('tokens chegaram de uma vez (spread ' + spreadMs + 'ms) — nao e streaming');
      console.log(`turno 1 OK: ${tokens} tokens, spread ${spreadMs}ms (progressivo)`);
      phase = 'interrupt';
      tokens = 0;
      ws.send(JSON.stringify({type: 'user_msg', text: 'segunda aula'}));
    }
  } else {
    if (m.type === 'token') {
      tokens++;
      if (interruptSent) tokensAfterInterrupt++;
      if (tokens === 5 && !interruptSent) {
        interruptSent = true;
        ws.send(JSON.stringify({type: 'interrupt'}));
      }
    } else if (m.type === 'done') {
      if (!m.interrupted) fail('turno 2 deveria vir marcado como interrompido');
      if (tokensAfterInterrupt > 5) fail('servidor continuou streamando apos interrupt: ' + tokensAfterInterrupt);
      console.log(`turno 2 OK: interrompido no token ~5 (${tokensAfterInterrupt} tokens vazaram depois do interrupt)`);
      console.log('PIPE-TEST PASS');
      process.exit(0);
    }
  }
});

ws.on('error', (e) => fail(e.message));
