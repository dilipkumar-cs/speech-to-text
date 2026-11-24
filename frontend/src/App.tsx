import { useEffect, useState } from 'react';
import { api } from './api';
import { HistoryDashboard } from './components/HistoryDashboard';
import { LivePanel } from './components/LivePanel';
import type { AppInfo } from './types';

type Tab = 'live' | 'history';

const tabFromPath = (): Tab => (window.location.pathname.startsWith('/history') ? 'history' : 'live');

export default function App() {
  const [tab, setTab] = useState<Tab>(tabFromPath);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    api.info().then(setInfo).catch(() => setInfo({ geminiEnabled: false, geminiModel: '', maxSessionSeconds: 570 }));
    const onPop = () => setTab(tabFromPath());
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const go = (next: Tab) => {
    if (next !== tab) window.history.pushState(null, '', `/${next}`);
    setTab(next);
  };

  return (
    <div className="app">
      <header>
        <div className="brand">
          <span className="logo">🎙️</span>
          <div>
            <h1>Speech to Text</h1>
            <p className="muted">Real-time multilingual transcription · Hindi · English · Hinglish</p>
          </div>
        </div>
        <nav>
          <button className={tab === 'live' ? 'active' : ''} onClick={() => go('live')}>
            Live
          </button>
          <button className={tab === 'history' ? 'active' : ''} onClick={() => go('history')}>
            History
          </button>
        </nav>
      </header>

      <main>
        {/* Keep the live panel mounted so a recording continues while browsing history */}
        <div hidden={tab !== 'live'}>
          <LivePanel info={info} onSaved={() => setRefreshKey((k) => k + 1)} onOpenHistory={() => go('history')} />
        </div>
        {tab === 'history' && <HistoryDashboard refreshKey={refreshKey} />}
      </main>

      <footer className="muted">
        {info?.geminiEnabled ? `Engine: ${info.geminiModel.replace('models/', '')} via Gemini Live API` : 'Gemini engine not configured – using browser speech recognition'}
        {' · '}Spring Boot · React · PostgreSQL
      </footer>
    </div>
  );
}
