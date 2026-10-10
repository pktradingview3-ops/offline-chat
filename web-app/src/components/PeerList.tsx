import { Peer } from '../types';

interface Props {
  peers: Peer[];
  activePeer: string;
  onSelect: (id: string) => void;
}

export default function PeerList({ peers, activePeer, onSelect }: Props) {
  return (
    <div className="peer-list">
      <div
        className={`peer-item ${activePeer === 'broadcast' ? 'active' : ''}`}
        onClick={() => onSelect('broadcast')}
      >
        <span className="dot online" /> # Group (sabhi)
      </div>

      {peers.map((p) => (
        <div
          key={p.id}
          className={`peer-item ${activePeer === p.id ? 'active' : ''}`}
          onClick={() => onSelect(p.id)}
        >
          <span className={`dot ${p.status}`} />
          {p.nickname || p.id}
        </div>
      ))}

      {peers.length === 0 && <div className="empty-hint">Is network par koi aur peer nahi mila abhi</div>}
    </div>
  );
}
