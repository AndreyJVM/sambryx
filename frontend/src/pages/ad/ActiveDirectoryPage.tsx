import { getErrorMessage } from "../../lib/errors";
import React, { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../lib/api';
import { useToast } from '../../components/ui/toast';
import { useConfirm } from '../../components/ui/confirm';
import { Server, Shield, Unlock, Lock, AlertCircle, RefreshCw, Users } from 'lucide-react';
import { useTranslation } from '../../lib/i18n';

interface AdStatus {
  isJoined: boolean;
  statusMessage: string;
  domainName: string;
  dnsReachable: boolean;
  kerberosWorking: boolean;
  winbindWorking: boolean;
}

export default function ActiveDirectoryPage() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { confirm } = useConfirm();
  const [status, setStatus] = useState<AdStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  // Form State
  const [domain, setDomain] = useState('');
  const [username, setUsername] = useState('Administrator');
  const [password, setPassword] = useState('');
  const [isJoining, setIsJoining] = useState(false);

  const fetchStatus = useCallback(async () => {
    try {
      setLoading(true);
      setLoadError('');
      const res = await api.get<AdStatus>('/api/ad/status');
      setStatus(res);
    } catch (err) {
      setLoadError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  const handleJoin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!domain || !username || !password) {
      toast('error', 'Validation Error', 'All fields are required.');
      return;
    }

    const ok = await confirm({
      title: t('ad.joinConfirmTitle'),
      message: t('ad.joinConfirmMsg').replace('{domain}', domain),
      confirmText: t('ad.joinDomain'),
      cancelText: t('common.cancel')
    });

    if (!ok) return;

    try {
      setIsJoining(true);
      const res = await api.post<AdStatus>('/api/ad/join', { domain, username, password });
      setStatus(res);
      toast('success', 'Joined', 'Successfully joined the domain');
      setPassword(''); // clear password
    } catch (err) {
      toast('error', 'Join Failed', getErrorMessage(err));
    } finally {
      setIsJoining(false);
    }
  };

  const handleLeave = async () => {
    const ok = await confirm({
      title: t('ad.leaveConfirmTitle'),
      message: t('ad.leaveConfirmMsg'),
      confirmText: t('ad.leaveDomain'),
      cancelText: t('common.cancel')
    });

    if (!ok) return;

    if (!username || !password) {
      toast('error', 'Validation Error', 'Admin Username and Password are required to leave the domain safely.');
      return;
    }

    try {
      setIsJoining(true);
      const res = await api.post<AdStatus>('/api/ad/leave', { domain, username, password });
      setStatus(res);
      toast('success', 'Left Domain', 'Successfully disconnected from AD');
      setPassword('');
    } catch (err) {
      toast('error', 'Leave Failed', getErrorMessage(err));
    } finally {
      setIsJoining(false);
    }
  };

  if (loading && !status) {
    return <div role="status" aria-label={t('sidebar.wait')} aria-busy="true" className="p-8 text-center"><RefreshCw className="w-8 h-8 animate-spin mx-auto text-accent-text" /></div>;
  }

  return (
    <div className="ad-page space-y-8 animate-fade-in fade-in">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div><h1 className="text-3xl font-bold tracking-tight">{t('ad.title')}</h1><p className="mt-2 text-sm text-text-muted">{t('ad.subtitle')}</p></div>
        <button onClick={fetchStatus} disabled={loading || isJoining} className="shares-refresh">
          <RefreshCw className={`w-4 h-4 text-accent-text ${loading ? 'animate-spin' : ''}`} /> {t('ad.refresh')}
        </button>
      </div>
      {loadError && <div role="alert" className="shares-error"><AlertCircle className="h-5 w-5 shrink-0" /><div><p>{t(status ? 'ad.staleData' : 'ad.statusNotAvailable')}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Status Panel */}
        <div className="bg-surface border border-border rounded-lg">
          <div className="p-5 border-b border-border">
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <Server className="w-5 h-5 text-accent-text" /> {t('ad.healthStatus')}
            </h2>
            <p className="text-sm text-text-muted mt-1">{t('ad.healthDesc')}</p>
          </div>
          
          <div className="p-5">
          {status ? (
            <div className="space-y-4">
              <div className="flex flex-wrap gap-3 items-center justify-between p-4 bg-surface-2 rounded-lg border border-border">
                <span className="font-medium text-foreground text-sm uppercase tracking-wider">{t('ad.domainJoinState')}</span>
                {status.isJoined ? (
                  <span className="flex items-center gap-1.5 px-3 py-1 bg-surface-tint text-accent-text rounded-full text-xs font-bold uppercase tracking-widest border border-border">
                    <Shield className="w-3.5 h-3.5" /> {t('ad.joined')}
                  </span>
                ) : (
                  // TODO: status-error token
                  <span className="flex items-center gap-1.5 px-3 py-1 bg-status-error/10 text-status-error rounded-full text-xs font-bold uppercase tracking-widest border border-status-error/20">
                    <AlertCircle className="w-3.5 h-3.5" /> {t('ad.notJoined')}
                  </span>
                )}
              </div>
              {status.domainName && <p className="ad-domain font-mono text-sm">{status.domainName}</p>}
              
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                 <div className="p-3 bg-surface-2 rounded-md border border-border flex flex-col justify-center">
                   <div className="text-xs text-text-muted uppercase tracking-widest mb-1.5 font-bold">{t('ad.winbindService')}</div>
                   <div className="font-semibold text-sm flex items-center gap-2">
                     {/* TODO: status-error / active token */}
                     <span className={`w-2 h-2 rounded-full ${status.winbindWorking ? 'bg-accent' : 'bg-status-error'}`}></span>
                     {status.winbindWorking ? t('ad.operational') : t('ad.failing')}
                   </div>
                 </div>
                 <div className="p-3 bg-surface-2 rounded-md border border-border flex flex-col justify-center">
                   <div className="text-xs text-text-muted uppercase tracking-widest mb-1.5 font-bold">Kerberos</div>
                   <div className="font-semibold text-sm flex items-center gap-2">
                     {/* TODO: status-error / active token */}
                     <span className={`w-2 h-2 rounded-full ${status.kerberosWorking ? 'bg-accent' : 'bg-status-error'}`}></span>
                     {status.kerberosWorking ? t('ad.ok') : t('ad.errorsDetected')}
                   </div>
                 </div>
                 <div className="p-3 bg-surface-2 rounded-md border border-border flex flex-col justify-center">
                   <div className="text-xs text-text-muted uppercase tracking-widest mb-1.5 font-bold">DNS</div>
                   <div className="font-semibold text-sm flex items-center gap-2"><span className={`w-2 h-2 rounded-full ${status.dnsReachable ? 'bg-status-active' : 'bg-status-error'}`} />{status.dnsReachable ? t('ad.ok') : t('ad.errorsDetected')}</div>
                 </div>
              </div>
            </div>
          ) : (
             <div className="text-center p-8 text-text-muted border border-dashed border-border rounded-lg text-sm">{t('ad.statusNotAvailable')}</div>
          )}
          </div>
        </div>

        {/* Action Panel */}
        <div className="bg-surface border border-border rounded-lg">
          <div className="p-5 border-b border-border">
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <Lock className="w-5 h-5 text-accent-text" /> {t('ad.actionTitle')}
            </h2>
            <p className="text-sm text-text-muted mt-1">{t('ad.actionDesc')}</p>
          </div>

          <form onSubmit={handleJoin} className="p-5 space-y-4">
             <div className="space-y-1.5">
               <label htmlFor="ad-domain" className="text-sm font-medium">{t('ad.targetDomain')}</label>
               <input id="ad-domain" type="text" className="w-full bg-surface border border-border-strong focus:border-accent px-3 py-2 text-sm rounded-md focus:outline-none focus:ring-2 focus:ring-accent/20 transition-all uppercase" placeholder="ad.example.com" value={domain} onChange={e => setDomain(e.target.value.toUpperCase())} required />
             </div>
             <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
               <div className="space-y-1.5">
                 <label htmlFor="ad-username" className="text-sm font-medium">{t('ad.adminUser')}</label>
                 <input id="ad-username" type="text" autoComplete="username" className="w-full bg-surface border border-border-strong focus:border-accent px-3 py-2 text-sm rounded-md focus:outline-none focus:ring-2 focus:ring-accent/20 transition-all" value={username} onChange={e => setUsername(e.target.value)} required />
               </div>
               <div className="space-y-1.5">
                 <label htmlFor="ad-password" className="text-sm font-medium">{t('ad.adminPass')}</label>
                 <input id="ad-password" type="password" autoComplete="current-password" className="w-full bg-surface border border-border-strong focus:border-accent px-3 py-2 text-sm rounded-md focus:outline-none focus:ring-2 focus:ring-accent/20 transition-all" value={password} onChange={e => setPassword(e.target.value)} required />
               </div>
             </div>
             
             <div className="pt-4 flex flex-wrap gap-3">
                <button type="submit" disabled={isJoining || status?.isJoined === true} className="flex-1 bg-accent text-on-accent hover:opacity-90 flex justify-center items-center px-4 py-2 rounded-md text-sm font-bold transition-opacity disabled:opacity-50">
                   {isJoining ? <RefreshCw className="w-4 h-4 mr-2 animate-spin" /> : <Shield className="w-4 h-4 mr-2" />}
                   {t('ad.joinDomain')}
                </button>
                {status?.isJoined && (
                  // TODO: status-error token
                  <button type="button" onClick={handleLeave} disabled={isJoining} className="flex-1 bg-surface hover:bg-status-error/10 border border-status-error/50 text-status-error justify-center flex items-center px-4 py-2 rounded-md text-sm font-bold transition-colors disabled:opacity-50">
                     <Unlock className="w-4 h-4 mr-2" /> {t('ad.leaveDomain')}
                  </button>
                )}
             </div>
          </form>
        </div>
      </div>
      
      {/* ID Mapping Visualization */}
      <div className="bg-surface border border-border rounded-lg p-5 border-l-4 border-l-accent flex items-start gap-4">
        <div className="p-2.5 rounded-lg bg-surface-2 border border-border shrink-0 text-accent-text">
          <Users className="w-5 h-5" />
        </div>
        <div className="flex-1 min-w-0">
          <h2 className="text-base font-semibold mb-1 text-foreground">
            {t('ad.idmapTitle')}
          </h2>
          <p className="text-sm text-text-muted leading-relaxed">
            {t('ad.idmapDesc')}{' '}
            <Link
              to="/config"
              className="inline-flex items-center text-foreground font-semibold px-2 py-0.5 bg-surface-2 hover:bg-surface-tint border border-border hover:border-accent/40 rounded text-xs transition-colors align-middle mx-1"
            >
              {t('ad.idmapTab')}
            </Link>
            {t('ad.idmapDescPart2').startsWith('.') ? t('ad.idmapDescPart2') : ' ' + t('ad.idmapDescPart2')}
          </p>
        </div>
      </div>

    </div>
  );
}
