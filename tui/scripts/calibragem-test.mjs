// Calibragem que não perde sinal: respostas interinas atualizam o
// calibragem.md e a prova só é gerada com "pode gerar" explícito.
// Env: SCHOOL_TEST_VAULT, PORT (default 7777), GAP_MS (default 15000).
// Vault com MISSION.md e SEM calibragem.md/DIAGNOSIS.md (estágio prova-fria).
import WebSocket from 'ws';
import fs from 'node:fs';
import path from 'node:path';

const vaultRoot = process.env.SCHOOL_TEST_VAULT;
if (!vaultRoot) { console.error('faltou SCHOOL_TEST_VAULT'); process.exit(1); }
const SUBJECT = 'java';
const dir = path.join(vaultRoot, SUBJECT);
const calibFile = path.join(dir, 'calibragem.md');
const provaFile = path.join(dir, 'prova-fria.edn');
const GAP_MS = Number(process.env.GAP_MS || 15000);

const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
setTimeout(() => fail('timeout de 540s'), 540000);
const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null);

const msgs = [
  // 0: nível inicial → calibragem.md nasce, prova NÃO gerada
  'Tenho 3 anos de Spring Boot e APIs REST. Mando bem na prática mas travo na teoria dos fundamentos.',
  // 1: RESPONDE algo específico (o sinal que o bug descartava) → deve entrar no calibragem.md
  'Sobre == vs equals: acho que == compara referência e equals compara conteúdo. Mas não faço ideia do que é string pool.',
  // 2: aval explícito → prova gerada
  'beleza, pode gerar a prova',
];

const ws = new WebSocket(`ws://localhost:${process.env.PORT || 7777}`);
let started = false, turn = -1;

function sendMsg(i, delay) {
  setTimeout(() => ws.send(JSON.stringify({ type: 'user_msg', text: msgs[i] })), delay);
}

ws.on('error', (e) => fail(e.message));
ws.on('open', () => ws.send(JSON.stringify({ type: 'start', subject: SUBJECT })));

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail('erro do servidor: ' + m.text);

  // dispara o turno 0 UMA vez (o servidor repete o info de estágio a cada turno)
  if (!started && m.type === 'info' && m.text?.includes('estágio: prova-fria')) {
    started = true; turn = 0; sendMsg(0, 0); return;
  }
  if (m.type !== 'done') return;

  if (turn === 0) {
    if (!read(calibFile)) fail('turno 1: calibragem.md não foi escrito');
    if (fs.existsSync(provaFile)) fail('turno 1: prova gerada cedo demais');
    console.log('turno 1: calibragem.md criado, prova ainda não — OK');
    turn = 1; sendMsg(1, GAP_MS);
  } else if (turn === 1) {
    if (fs.existsSync(provaFile)) fail('BUG: prova gerada no turno 2 sem "pode gerar" — sinal descartado');
    const c = (read(calibFile) || '').toLowerCase();
    if (!/string pool|equals|referência|referencia|conteúdo|conteudo/.test(c))
      fail('turno 2: calibragem.md NÃO incorporou a resposta interina\n---\n' + read(calibFile));
    console.log('turno 2: resposta interina incorporada, prova ainda NÃO gerada — OK (bug corrigido)');
    turn = 2; sendMsg(2, GAP_MS);
  } else if (turn === 2) {
    if (!fs.existsSync(provaFile)) fail('turno 3: "pode gerar" não gerou a prova');
    console.log('turno 3: "pode gerar" → prova-fria.edn criado — OK');
    console.log('\n--- calibragem.md final ---\n' + read(calibFile).slice(0, 600));
    console.log('\nCALIBRAGEM-TEST PASS');
    process.exit(0);
  }
});
