import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles.css';

// Note: StrictMode jaan-bujhkar use nahi kiya — WebRTC/WebSocket connections
// dev mode ke double-effect-invoke se duplicate ho sakte hain.
ReactDOM.createRoot(document.getElementById('root')!).render(<App />);
