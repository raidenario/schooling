import React, {useEffect, useRef, useState} from 'react';
import {render, Box, Text, useInput} from 'ink';
import TextInput from 'ink-text-input';
import Spinner from 'ink-spinner';
import WebSocket from 'ws';

const URL = process.env.SCHOOL_WS ?? 'ws://localhost:7777';
const SUBJECT = process.argv[2]; // pnpm run dev -- <matéria>; sem arg = modo spike

// paleta inspirada no Claude Code
const ACCENT = '#D97757'; // coral
const DIM = 'gray';

type Entry = {role: 'aprendiz' | 'professor' | 'sistema'; text: string; interrupted?: boolean};
type Server =
  | {type: 'token'; text: string}
  | {type: 'done'; interrupted?: boolean}
  | {type: 'info'; text: string}
  | {type: 'error'; text: string}
  | {type: 'records'; items: string[]};

const HELP = [
  '/help   — esta ajuda',
  '/clear  — limpa a tela (não apaga o histórico do servidor)',
  '/stage  — estágio e matéria atuais',
  '/vault  — caminho dos arquivos desta matéria',
  'ESC     — interrompe a aula no meio do stream',
].join('\n');

function Marker({role}: {role: Entry['role']}) {
  if (role === 'aprendiz') return <Text color={DIM}>{'❯ '}</Text>;
  if (role === 'professor') return <Text color={ACCENT}>{'⏺ '}</Text>;
  return <Text color={DIM}>{'⎿  '}</Text>;
}

function Message({e}: {e: Entry}) {
  return (
    <Box marginTop={1}>
      <Marker role={e.role} />
      <Box flexGrow={1}>
        <Text color={e.role === 'sistema' ? DIM : undefined} dimColor={e.role === 'aprendiz'}>
          {e.text}
          {e.interrupted ? <Text color="yellow"> [interrompido]</Text> : null}
        </Text>
      </Box>
    </Box>
  );
}

function Thinking({elapsed, streaming}: {elapsed: number; streaming: boolean}) {
  return (
    <Box marginTop={1}>
      <Text color={ACCENT}>
        <Spinner type="dots" />
      </Text>
      <Text color={DIM}>
        {' '}
        {streaming ? 'ensinando' : 'professor pensando'}… ({elapsed}s · esc para interromper)
      </Text>
    </Box>
  );
}

function App() {
  const [log, setLog] = useState<Entry[]>([]);
  const [streaming, setStreaming] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [stage, setStage] = useState<string | null>(null);
  const [vaultDir, setVaultDir] = useState<string | null>(null);
  const ws = useRef<WebSocket | null>(null);
  const streamRef = useRef<string | null>(null);
  const busyRef = useRef(false);

  // cronômetro do turno
  useEffect(() => {
    if (!busy) return;
    setElapsed(0);
    const t = setInterval(() => setElapsed(s => s + 1), 1000);
    return () => clearInterval(t);
  }, [busy]);

  useEffect(() => {
    let unmounted = false;
    let sock: WebSocket;
    const connect = () => {
      sock = new WebSocket(URL);
      ws.current = sock;
      sock.on('open', () => {
        if (SUBJECT) sock.send(JSON.stringify({type: 'start', subject: SUBJECT}));
        setConnected(true);
      });
      sock.on('close', () => {
        if (unmounted) return;
        streamRef.current = null;
        setStreaming(null);
        busyRef.current = false;
        setBusy(false);
        setConnected(false);
        setTimeout(() => { if (!unmounted) connect(); }, 2000);
      });
      sock.on('error', () => {});
      sock.on('message', (raw: Buffer) => {
        const msg = JSON.parse(raw.toString()) as Server;
        if (msg.type === 'token') {
          streamRef.current = (streamRef.current ?? '') + msg.text;
          setStreaming(streamRef.current);
        } else if (msg.type === 'done') {
          const text = streamRef.current ?? '';
          streamRef.current = null;
          setStreaming(null);
          busyRef.current = false;
          setBusy(false);
          if (text)
            setLog(l => [...l, {role: 'professor', text, interrupted: msg.interrupted}]);
        } else if (msg.type === 'info') {
          // o servidor manda "matéria: X · estágio: Y · vault: Z" e "estágio: Y"
          const st = /estágio:\s*([\w-]+)/.exec(msg.text)?.[1];
          if (st) setStage(st);
          const v = /vault:\s*(.+)$/.exec(msg.text)?.[1];
          if (v) setVaultDir(v.trim());
          if (!st && !v) setLog(l => [...l, {role: 'sistema', text: msg.text}]);
        } else if (msg.type === 'error') {
          busyRef.current = false;
          setBusy(false);
          setLog(l => [...l, {role: 'sistema', text: `erro: ${msg.text}`}]);
        }
      });
    };
    connect();
    return () => { unmounted = true; sock?.close(); };
  }, []);

  useInput((_input, key) => {
    if (key.escape && busyRef.current) {
      ws.current?.send(JSON.stringify({type: 'interrupt'}));
    }
  });

  const slash = (cmd: string): boolean => {
    const sys = (text: string) => setLog(l => [...l, {role: 'sistema', text}]);
    switch (cmd) {
      case '/help': sys(HELP); return true;
      case '/clear': setLog([]); return true;
      case '/stage':
        sys(`matéria: ${SUBJECT ?? '(modo spike)'} · estágio: ${stage ?? '?'}`);
        return true;
      case '/vault': sys(vaultDir ?? 'vault ainda desconhecido — mande uma mensagem primeiro'); return true;
      default:
        if (cmd.startsWith('/')) { sys(`comando desconhecido: ${cmd} (/help)`); return true; }
        return false;
    }
  };

  const submit = (text: string) => {
    const t = text.trim();
    if (!t) return;
    setInput('');
    if (slash(t)) return;
    if (busyRef.current || !connected) return;
    ws.current?.send(JSON.stringify({type: 'user_msg', text: t}));
    setLog(l => [...l, {role: 'aprendiz', text: t}]);
    busyRef.current = true;
    setBusy(true);
  };

  return (
    <Box flexDirection="column" paddingX={1}>
      <Box
        borderStyle="round"
        borderColor={ACCENT}
        flexDirection="column"
        paddingX={2}
        marginTop={1}
      >
        <Text>
          <Text color={ACCENT} bold>✳ School</Text>
          <Text color={DIM}> — professor particular adaptativo</Text>
        </Text>
        <Text color={DIM}>
          {SUBJECT ? `matéria: ${SUBJECT}` : 'modo spike (sem matéria)'}
          {stage ? ` · estágio: ${stage}` : ''}
        </Text>
        <Text color={DIM}>/help para comandos · os arquivos aparecem no Obsidian</Text>
      </Box>

      {log.map((e, i) => <Message key={i} e={e} />)}

      {streaming !== null && (
        <Box marginTop={1}>
          <Text color={ACCENT}>{'⏺ '}</Text>
          <Box flexGrow={1}>
            <Text>
              {streaming}
              <Text color={ACCENT}>▌</Text>
            </Text>
          </Box>
        </Box>
      )}

      {busy && <Thinking elapsed={elapsed} streaming={streaming !== null} />}

      <Box borderStyle="round" borderColor={busy ? DIM : ACCENT} paddingX={1} marginTop={1}>
        <Text color={busy ? DIM : ACCENT}>{'❯ '}</Text>
        <TextInput
          value={input}
          onChange={setInput}
          onSubmit={submit}
          placeholder={busy ? 'aguarde o professor…' : 'converse com o professor'}
        />
      </Box>

      <Text color={DIM}>
        {connected ? '● conectado' : '○ reconectando…'}
        {` · ${URL}`}
        {busy ? ' · esc interrompe' : ''}
      </Text>
    </Box>
  );
}

render(<App />);
