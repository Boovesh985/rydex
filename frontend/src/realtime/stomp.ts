import { Client, ReconnectionTimeMode, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { freshToken } from '../api/http';
import { config } from '../config';

type Handler = (body: unknown) => void;
type ConnectionListener = (connected: boolean) => void;

interface Sub {
  destination: string;
  handler: Handler;
  stomp?: StompSubscription;
}

/**
 * One STOMP connection to tracking-service for the whole app (contracts/websocket.md).
 * - Fresh access token on every (re)connect: the server refuses frames once a token expires.
 * - Exponential backoff, 1 s doubling to 30 s.
 * - Subscriptions survive reconnects; listeners hear about each (re)connect so screens can
 *   re-read state over REST (pushes sent while disconnected are not replayed).
 */
class Realtime {
  private client: Client | null = null;
  private readonly subs = new Map<number, Sub>();
  private readonly listeners = new Set<ConnectionListener>();
  private nextId = 1;
  connected = false;

  start() {
    if (this.client) return;
    const client = new Client({
      brokerURL: config.wsUrl,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      reconnectDelay: 1_000,
      maxReconnectDelay: 30_000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      beforeConnect: async (c) => {
        const token = await freshToken();
        if (!token) {
          await c.deactivate();
          return;
        }
        c.connectHeaders = { Authorization: `Bearer ${token}` };
      },
      onConnect: () => {
        for (const sub of this.subs.values()) this.attach(sub);
        this.setConnected(true);
      },
      onWebSocketClose: () => this.setConnected(false),
      onStompError: (frame) => console.warn('[ws] refused:', frame.headers.message),
    });
    this.client = client;
    client.activate();
  }

  async stop() {
    const client = this.client;
    this.client = null;
    for (const sub of this.subs.values()) sub.stomp = undefined;
    this.setConnected(false);
    await client?.deactivate();
  }

  subscribe(destination: string, handler: Handler): () => void {
    const id = this.nextId++;
    const sub: Sub = { destination, handler };
    this.subs.set(id, sub);
    if (this.client?.connected) this.attach(sub);
    return () => {
      sub.stomp?.unsubscribe();
      this.subs.delete(id);
    };
  }

  /** False when not connected: the caller decides whether to fall back to REST. */
  publish(destination: string, body: unknown): boolean {
    if (!this.client?.connected) return false;
    this.client.publish({ destination, body: JSON.stringify(body) });
    return true;
  }

  onConnection(listener: ConnectionListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private attach(sub: Sub) {
    sub.stomp = this.client!.subscribe(sub.destination, (msg: IMessage) => {
      try {
        sub.handler(JSON.parse(msg.body));
      } catch (e) {
        console.warn('[ws] bad message on', sub.destination, e);
      }
    });
  }

  private setConnected(value: boolean) {
    if (this.connected === value) return;
    this.connected = value;
    for (const l of this.listeners) l(value);
  }
}

export const realtime = new Realtime();
