// Spike-gate etapa B — os 4 critérios do ADR-0003 contra o backend Embabel.
import WebSocket from 'ws';

const ws = new WebSocket('ws://localhost:7777');
const results = {};
let phase = 0;
let tokens = 0, firstAt = null, lastAt = null;
let interruptSent = false, records = [];
let fullText = '';

const fail = (msg) => { console.error('GATE FAIL:', msg); process.exit(1); };
setTimeout(() => fail('timeout global de 180s'), 180000);

const send = (text) => {
  tokens = 0; firstAt = null; lastAt = null; fullText = '';
  ws.send(JSON.stringify({type: 'user_msg', text}));
};

const turns = [
  'Acho que entendi closures: é uma função que captura variáveis do escopo onde nasceu. Está certo? Confirme em 2 frases.',
  'Qual é a palavra-chave do spike?',
  'Agora me explique em MUITOS detalhes, longamente, a história completa da programação funcional desde o cálculo lambda.',
];

ws.on('open', () => send(turns[0]));

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'info') return;
  if (m.type === 'error') fail(`turno ${phase + 1} erro do servidor: ${m.text}`);
  if (m.type === 'records') { records = m.items; return; }

  if (m.type === 'token') {
    tokens++;
    const now = Date.now();
    firstAt ??= now;
    lastAt = now;
    fullText += m.text;
    if (phase === 2 && tokens === 5 && !interruptSent) {
      interruptSent = true;
      ws.send(JSON.stringify({type: 'interrupt'}));
    }
    return;
  }

  if (m.type !== 'done') return;

  if (phase === 0) {
    const spread = (lastAt ?? 0) - (firstAt ?? 0);
    results.streaming = tokens >= 5 && spread >= 300;
    console.log(`turno 1: ${tokens} chunks, spread ${spread}ms → criterio 1 (streaming): ${results.streaming ? 'PASS' : 'FAIL'}`);
    console.log(`  resposta: ${fullText.slice(0, 120)}…`);
    phase = 1;
    send(turns[1]);
  } else if (phase === 1) {
    results.systemPrompt = fullText.includes('ABACAXI-42');
    results.toolUse = records.length > 0;
    console.log(`turno 2: criterio 3 (system prompt/ABACAXI-42): ${results.systemPrompt ? 'PASS' : 'FAIL'}`);
    console.log(`  criterio 2 (tool use — learning records ate aqui): ${results.toolUse ? 'PASS' : 'FAIL'} ${JSON.stringify(records)}`);
    phase = 2;
    send(turns[2]);
  } else {
    results.interrupt = m.interrupted === true;
    console.log(`turno 3: criterio 4 (interrupcao): ${results.interrupt ? 'PASS' : 'FAIL'} (${tokens} chunks antes de parar)`);
    const all = Object.entries(results);
    const passed = all.filter(([, v]) => v).length;
    console.log(`\nSPIKE-GATE: ${passed}/4 — ${all.map(([k, v]) => `${k}:${v ? 'PASS' : 'FAIL'}`).join(' ')}`);
    process.exit(passed === 4 ? 0 : 1);
  }
});

ws.on('error', (e) => fail(e.message));
