import { useEffect, useRef, useState } from 'react';
import { ChatMessage } from '../types';

interface Props {
  activePeer: string;
  messages: ChatMessage[];
  onSend: (text: string) => void;
}

export default function ChatWindow({ activePeer, messages, onSend }: Props) {
  const [text, setText] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  const submit = () => {
    const trimmed = text.trim();
    if (!trimmed) return;
    onSend(trimmed);
    setText('');
  };

  return (
    <div className="chat-window">
      <div className="chat-header">{activePeer === 'broadcast' ? '# Group Chat' : activePeer}</div>

      <div className="messages">
        {messages.map((m) => (
          <div key={m.id} className={`message ${m.self ? 'self' : 'other'}`}>
            {!m.self && <div className="sender">{m.senderId}</div>}
            <div className="bubble">{m.text}</div>
          </div>
        ))}
        {messages.length === 0 && <div className="empty-hint">Abhi koi message nahi — pehla message bhejein</div>}
        <div ref={bottomRef} />
      </div>

      <div className="composer">
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="Message likhein..."
        />
        <button onClick={submit}>Bhejein</button>
      </div>
    </div>
  );
}
