// Fase 1 — teste vivo do turno de missão: o professor entrevista, a tool
// salvar_missao escreve MISSION.md no vault e o evento de domínio é emitido.
// Env: SCHOOL_TEST_VAULT (raiz do vault de teste), SCHOOL_TEST_EVENTS (events.edn).
import WebSocket from 'ws';
import fs from 'node:fs';
import path from 'node:path';

const vaultRoot = process.env.SCHOOL_TEST_VAULT;
const eventsFile = process.env.SCHOOL_TEST_EVENTS;
if (!vaultRoot) { console.error('faltou SCHOOL_TEST_VAULT'); process.exit(1); }

const ws = new WebSocket('ws://localhost:7777');
let tokens = 0, fullText = '';

const fail = (msg) => { console.error('FAIL:', msg); process.exit(1); };
setTimeout(() => fail('timeout de 280s'), 280000);

ws.on('open', () => ws.send(JSON.stringify({type: 'start', subject: 'spike-teste'})));

let started = false;
ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail('erro do servidor: ' + m.text);

  if (m.type === 'info' && !started && m.text?.includes('estágio: missao')) {
    started = true;
    console.log('sessão aberta:', m.text);
    ws.send(JSON.stringify({
      type: 'user_msg',
      text: 'Quero aprender Clojure avançado (transducers, core.async, macros) porque vou dar manutenção num backend Clojure em produção no trabalho a partir do mês que vem. Sucesso pra mim é conseguir revisar PRs e escrever código idiomático sem medo. Pode salvar a missão.',
    }));
    return;
  }

  if (m.type === 'token') { tokens++; fullText += m.text; return; }

  if (m.type === 'done') {
    const missionFile = path.join(vaultRoot, 'spike-teste', 'MISSION.md');
    const missionOk = fs.existsSync(missionFile);
    console.log(`turno de missão: ${tokens} chunks streamados`);
    console.log(`resposta: ${fullText.slice(0, 150)}…`);
    console.log(`MISSION.md escrito pela tool: ${missionOk ? 'PASS' : 'FAIL'} (${missionFile})`);
    if (missionOk) console.log('--- MISSION.md ---\n' + fs.readFileSync(missionFile, 'utf8').slice(0, 400));
    let eventOk = false;
    if (eventsFile && fs.existsSync(eventsFile)) {
      eventOk = fs.readFileSync(eventsFile, 'utf8').includes(':missao-definida');
    }
    console.log(`evento :missao-definida emitido: ${eventOk ? 'PASS' : 'FAIL'}`);
    console.log(missionOk && eventOk ? '\nSCHOOL-TEST PASS' : '\nSCHOOL-TEST FAIL');
    process.exit(missionOk && eventOk ? 0 : 1);
  }
});

ws.on('error', (e) => fail(e.message));
