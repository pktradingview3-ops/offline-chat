import { useState } from 'react';
import { useChat } from './useChat';
import PeerList from './components/PeerList';
import ChatWindow from './components/ChatWindow';

const DEFAULT_SIGNALING_URL = `ws://${window.location.hostname}:8080`;

export default function App() {
  const [signalingUrl, setSignalingUrl] = useState('');
  const [urlInput, setUrlInput] = useState(DEFAULT_SIGNALING_URL);
  const [activePeer, setActivePeer] = useState<string>('broadcast');

  const { myId, peers, messages, connected, error, sendMessage } = useChat(signalingUrl);

  if (!signalingUrl) {
    return (
      <div className="setup-screen">
        <h1>Offline Chat</h1>
        <p>Signaling node ka LAN address daalein (host device par dikhega):</p>
        <input value={urlInput} onChange={(e) => setUrlInput(e.target.value)} placeholder="ws://192.168.1.5:8080" />
        <button onClick={() => setSignalingUrl(urlInput)}>Connect</button>
        <p className="hint">
          Signaling node alag se chalana hoga (<code>signaling-server</code> folder, <code>npm start</code>).
        </p>
      </div>
    );
  }

  const filteredMessages = messages.filter((m) =>
    activePeer === 'broadcast'
      ? m.recipientId === 'broadcast'
      : m.recipientId !== 'broadcast' && (m.senderId === activePeer || m.recipientId === activePeer)
  );

  return (
    <div className="app-layout">
      <aside className="sidebar">
        <div className="my-id">
          Aap: <strong>{myId}</strong>
        </div>
        <div className={`status ${connected ? 'online' : 'offline'}`}>
          {connected ? 'Signaling node se connected' : 'Connect ho raha hai...'}
        </div>
        {error && <div className="error">{error}</div>}
        <PeerList peers={peers} activePeer={activePeer} onSelect={setActivePeer} />
      </aside>
      <main className="chat-area">
        <ChatWindow activePeer={activePeer} messages={filteredMessages} onSend={(text) => sendMessage(text, activePeer)} />
      </main>
    </div>
  );
}
