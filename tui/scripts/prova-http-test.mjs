// Fluxo interativo da Prova (ADR-0007): GET da página gamificada, POST das
// respostas pelo Concluir, correção AUTOMÁTICA streamada no chat, arquivos
// escritos (resultado com score de código, gabarito, diagnóstico, currículo).
import WebSocket from 'ws';
import fs from 'node:fs';
import path from 'node:path';

const vaultRoot = process.env.SCHOOL_TEST_VAULT;
const dir = path.join(vaultRoot, 'spike-teste');
const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
setTimeout(() => fail('timeout de 540s'), 540000);

const ws = new WebSocket('ws://localhost:7777');
let corrigindo = false, streamed = '';

ws.on('open', async () => {
  ws.send(JSON.stringify({type: 'start', subject: 'spike-teste'}));

  // 1) GET da prova
  const page = await fetch('http://localhost:7777/prova/spike-teste/fria');
  if (!page.ok) fail('GET prova: ' + page.status);
  const html = await page.text();
  const okHtml = html.includes('CONCLUIR') && html.includes('Ainda não sei') && !html.includes('Adição básica');
  console.log(`GET /prova: ${okHtml ? 'PASS' : 'FAIL'} (gamificada, sem gabarito)`);

  // 2) POST das respostas (q1 certa, q2 'não sei') — como o botão Concluir faz
  const post = await fetch('http://localhost:7777/respostas/spike-teste/fria', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({answers: [
      {id: 'q1', alternativa: 'a', justificativa: 'somei nos dedos'},
      {id: 'q2', alternativa: 'ns', justificativa: 'nunca estudei multiplicação'},
    ]}),
  });
  if (!post.ok) fail('POST respostas: ' + post.status + ' ' + (await post.text()));
  console.log('POST /respostas: PASS (ok)');
});

ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail('erro do servidor: ' + m.text);
  if (m.type === 'info' && /corrigindo/.test(m.text)) {
    corrigindo = true;
    console.log('correção automática disparada pelo POST: PASS');
  }
  if (m.type === 'token') streamed += m.text;
  if (m.type === 'done' && corrigindo) {
    console.log('correção streamada no chat:', streamed.slice(0, 160).replace(/\n/g, ' '));
    const checks = {
      'resultado (score em código)': () =>
        fs.readFileSync(path.join(dir, 'prova-fria-resultado.md'), 'utf8').includes('score: 50%'),
      'gabarito com explicação': () =>
        fs.readFileSync(path.join(dir, 'gabarito-fria.html'), 'utf8').includes('Adição básica'),
      'DIAGNOSIS.md escrito': () => fs.existsSync(path.join(dir, 'DIAGNOSIS.md')),
      'CURRICULUM.md escrito': () => fs.existsSync(path.join(dir, 'CURRICULUM.md')),
    };
    let all = true;
    for (const [nome, f] of Object.entries(checks)) {
      let ok = false;
      try { ok = f(); } catch {}
      console.log(`${nome}: ${ok ? 'PASS' : 'FAIL'}`);
      all = all && ok;
    }
    console.log(all ? '\nPROVA-HTTP-TEST PASS' : '\nPROVA-HTTP-TEST FAIL');
    process.exit(all ? 0 : 1);
  }
});
ws.on('error', (e) => fail(e.message));
