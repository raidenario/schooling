// Follow-up na sessão viva: ordena explicitamente a chamada da tool.
import WebSocket from 'ws';
import fs from 'node:fs';
import path from 'node:path';

const vaultRoot = process.env.SCHOOL_TEST_VAULT;
const ws = new WebSocket('ws://localhost:7777');
const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
setTimeout(() => fail('timeout de 280s'), 280000);

ws.on('open', () => {
  ws.send(JSON.stringify({type: 'start', subject: 'spike-teste'}));
  setTimeout(() => ws.send(JSON.stringify({
    type: 'user_msg',
    text: 'Sim, é isso. Salve a missão AGORA chamando a tool salvar_missao.',
  })), 500);
});

let text = '';
ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail(m.text);
  if (m.type === 'token') text += m.text;
  if (m.type === 'done') {
    const f = path.join(vaultRoot, 'spike-teste', 'MISSION.md');
    const ok = fs.existsSync(f);
    console.log('resposta:', text.slice(0, 120) + '…');
    console.log('MISSION.md:', ok ? 'PASS' : 'FAIL');
    if (ok) console.log(fs.readFileSync(f, 'utf8').slice(0, 300));
    process.exit(ok ? 0 : 1);
  }
});
ws.on('error', (e) => fail(e.message));
