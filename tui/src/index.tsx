import React, {useEffect, useRef, useState} from 'react';
import {render, Box, Text, useInput} from 'ink';
import TextInput from 'ink-text-input';
import WebSocket from 'ws';

const URL = process.env.SCHOOL_WS ?? 'ws://localhost:7777';
const SUBJECT = process.argv[2]; // pnpm run dev -- <matéria>; sem arg = modo spike

type Entry = {role: 'aprendiz' | 'professor' | 'sistema'; text: string; interrupted?: boolean};
type Server =
  | {type: 'token'; text: string}
  | {type: 'done'; interrupted?: boolean}
  | {type: 'info'; text: string}
  | {type: 'error'; text: string};

function App() {
  const [log, setLog] = useState<Entry[]>([]);
  const [streaming, setStreaming] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [status, setStatus] = useState('conectando…');
  const ws = useRef<WebSocket | null>(null);
  // useState de `streaming` não serve dentro do onmessage (closure velha): ref espelho
  const streamRef = useRef<string | null>(null);

  useEffect(() => {
    let unmounted = false;
    let sock: WebSocket;
    const connect = () => {
      sock = new WebSocket(URL);
      ws.current = sock;
      sock.on('open', () => {
        if (SUBJECT) sock.send(JSON.stringify({type: 'start', subject: SUBJECT}));
        setStatus('pronto');
      });
      sock.on('close', () => {
        if (unmounted) return;
        streamRef.current = null;
        setStreaming(null);
        setStatus('desconectado — reconectando em 2s…');
        setTimeout(() => { if (!unmounted) connect(); }, 2000);
      });
      sock.on('error', (e: Error) => setStatus(`erro: ${e.message}`));
      sock.on('message', (raw: Buffer) => {
      const msg = JSON.parse(raw.toString()) as Server;
      if (msg.type === 'token') {
        streamRef.current = (streamRef.current ?? '') + msg.text;
        setStreaming(streamRef.current);
      } else if (msg.type === 'done') {
        const text = streamRef.current ?? '';
        streamRef.current = null;
        setStreaming(null);
        if (text)
          setLog(l => [...l, {role: 'professor', text, interrupted: msg.interrupted}]);
        setStatus('pronto');
      } else if (msg.type === 'info') {
        setStatus(msg.text);
      } else if ('text' in msg && msg.text) {
        setLog(l => [...l, {role: 'sistema', text: msg.text}]);
      }
      });
    };
    connect();
    return () => { unmounted = true; sock?.close(); };
  }, []);

  useInput((_input, key) => {
    if (key.escape && streamRef.current !== null) {
      ws.current?.send(JSON.stringify({type: 'interrupt'}));
      setStatus('interrompendo…');
    }
  });

  const submit = (text: string) => {
    if (!text.trim() || streamRef.current !== null) return;
    ws.current?.send(JSON.stringify({type: 'user_msg', text}));
    setLog(l => [...l, {role: 'aprendiz', text}]);
    setInput('');
    setStatus('professor pensando…');
  };

  return (
    <Box flexDirection="column" padding={1}>
      <Text bold color="cyan">
        School{SUBJECT ? ` · ${SUBJECT}` : ' · Fase 0 (spike)'} · ESC interrompe
      </Text>
      {log.map((e, i) => (
        <Box key={i} marginTop={1}>
          <Text color={e.role === 'aprendiz' ? 'green' : e.role === 'sistema' ? 'red' : 'white'}>
            <Text bold>{e.role}: </Text>
            {e.text}
            {e.interrupted ? <Text color="yellow"> [interrompido]</Text> : null}
          </Text>
        </Box>
      ))}
      {streaming !== null && (
        <Box marginTop={1}>
          <Text>
            <Text bold>professor: </Text>
            {streaming}
            <Text color="cyan">▌</Text>
          </Text>
        </Box>
      )}
      <Box marginTop={1}>
        <Text color="green">{'> '}</Text>
        <TextInput value={input} onChange={setInput} onSubmit={submit} />
      </Box>
      <Text dimColor>{status}</Text>
    </Box>
  );
}

render(<App />);
