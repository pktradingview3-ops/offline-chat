import { SignalMessage } from './types';

type Handler = (msg: SignalMessage) => void;

/**
 * Signaling node se sirf discovery + WebRTC handshake ke liye baat karta hai.
 * Ye kabhi chat content nahi bhejta — connection banne ke baad DataChannel hi use hota hai.
 */
export class SignalingClient {
  private ws: WebSocket | null = null;
  private handlers: Handler[] = [];

  connect(url: string): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.ws = new WebSocket(url);
      } catch (e) {
        reject(e);
        return;
      }
      this.ws.onopen = () => resolve();
      this.ws.onerror = (e) => reject(e);
      this.ws.onmessage = (event) => {
        try {
          const msg: SignalMessage = JSON.parse(event.data);
          this.handlers.forEach((h) => h(msg));
        } catch {
          // malformed message, ignore
        }
      };
    });
  }

  onMessage(handler: Handler) {
    this.handlers.push(handler);
  }

  send(msg: SignalMessage) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  close() {
    this.ws?.close();
    this.ws = null;
  }
}
