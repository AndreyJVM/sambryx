import { test as base, expect } from '@playwright/test';

const users = [
  { username: 'alice', fullName: 'Alice Example' },
  { username: 'bob', fullName: 'Bob Example' },
];

export const test = base.extend({
  appLanguage: ['en', { option: true }],
  app: [async ({ context, page, baseURL, appLanguage }, use, testInfo) => {
    const origin = new URL(baseURL).origin;
    const state = {
      authenticated: true,
      sessionRemaining: 15 * 60_000,
      renewalDenied: false,
      loginFailure: false,
      failShares: false,
      failDashboard: false,
      failConfig: false,
      requests: [],
      backups: [],
      workgroup: 'TEAM',
      restore: null,
      shares: [
        { name: 'team', path: '/srv/samba/team', comment: 'Project documents', readOnly: false, guestOk: false, browseable: true },
        { name: 'archive', path: '/srv/samba/archive', comment: 'Past reports', readOnly: true, guestOk: false, browseable: true },
      ],
    };
    const unexpected = [];
    const browserErrors = [];
    const onPageError = error => browserErrors.push(error.message);
    page.on('pageerror', onPageError);
    await context.addInitScript(({ theme, language }) => {
      localStorage.setItem('samba_lang', language);
      if (!localStorage.getItem('theme')) localStorage.setItem('theme', theme);
    }, { theme: testInfo.project.use.colorScheme, language: appLanguage });

    await context.route('**/*', async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin !== origin) {
        unexpected.push(`External request: ${url.origin}`);
        return route.abort('blockedbyclient');
      }
      if (!url.pathname.startsWith('/api/')) return route.continue();
      const method = request.method();
      const path = url.pathname;
      state.requests.push({ method, path, body: request.postDataJSON(), headers: request.headers() });
      const reply = (data, status = 200, message = '', headers = {}) => route.fulfill({
        status, contentType: 'application/json', headers,
        body: JSON.stringify({ success: status < 400, message, data }),
      });
      if (path === '/api/auth/environment' && method === 'GET') {
        return reply({}, 200, '', { 'set-cookie': 'XSRF-TOKEN=browser-fixture; Path=/; SameSite=Lax' });
      }
      if (path === '/api/auth/login' && method === 'POST') {
        if (state.loginFailure) return reply({ code: 'SSH_HOST_KEY_CHANGED', fingerprint: 'SHA256:synthetic-browser-fixture' }, 409, 'Server identity changed');
        state.authenticated = true;
        return reply(null);
      }
      if (path === '/api/auth/me' && method === 'GET') return state.authenticated
        ? reply({ host: 'files.example.test', user: 'operator' }) : reply(null, 401, 'Not authenticated');
      if (path === '/api/auth/session' && method === 'GET') return state.authenticated
        ? reply({ serverTime: Date.now(), deadline: Date.now() + state.sessionRemaining }) : reply(null, 401);
      if (path === '/api/auth/activity' && method === 'POST') {
        if (!state.authenticated || state.renewalDenied) return reply(null, 401);
        state.sessionRemaining = 15 * 60_000;
        return reply({ serverTime: Date.now(), deadline: Date.now() + state.sessionRemaining });
      }
      if (path === '/api/auth/logout' && method === 'POST') {
        state.authenticated = false;
        return reply(null);
      }
      if (method === 'GET') {
        if (path === '/api/shares') return state.failShares ? reply(null, 503, 'Fixture unavailable') : reply(state.shares);
        if (path === '/api/users') return reply(users);
        if (path === '/api/users/system') return reply(users.map(user => user.username));
        if (path === '/api/groups') return reply([{ name: 'engineering', members: ['alice', 'bob'] }]);
        if (path === '/api/monitoring/dashboard') return state.failDashboard ? reply(null, 503, 'Fixture unavailable')
          : reply({ isRunning: true, connections: [{ pid: '100', user: 'alice', machine: '192.0.2.10' }], openFiles: [] });
        if (path === '/api/fs/disk-usage') return reply({ total: '200 GB', used: '84 GB', available: '116 GB', usePercent: 42, mountPoint: '/' });
        if (path === '/api/fs/browse') {
          const currentPath = url.searchParams.get('path') || '/';
          return reply({ currentPath, parentPath: currentPath === '/' ? null : '/', directories: [{ name: 'team', fullPath: '/srv/samba/team', type: 'dir' }] });
        }
        if (path === '/api/config/global') return state.failConfig ? reply(null, 503, 'synthetic-sensitive-refresh-output') : reply({ workgroup: state.workgroup, netbiosName: 'FILES', serverString: 'Fixture', security: 'user', mapToGuest: 'never', interfaces: '', bindInterfacesOnly: false, minProtocol: 'SMB2', maxProtocol: 'SMB3', disableNetbios: true, loadPrinters: false });
        if (path === '/api/config/backups') return reply(state.backups);
        if (path === '/api/ad/status') return reply({ isJoined: false, domainName: '', dnsReachable: true, kerberosWorking: false, winbindWorking: true });
        if (path === '/api/fstec/check') return reply({ checkedAt: '2026-01-01T12:00:00Z', compliancePercent: 100, okCount: 0, failedCount: 0, manualCount: 0, notApplicableCount: 0, rules: [] });
      }
      if (method === 'POST' && state.restore && path === `/api/config/backups/${state.restore.filename}/restore`) {
        await state.restore.wait;
        if (state.restore.abort) return route.abort('failed');
        if (state.restore.failRefresh) state.failConfig = true;
        if (state.restore.status) return reply(null, state.restore.status, state.restore.message);
        state.workgroup = 'RESTORED';
        state.backups = [...state.backups, { filename: 'smb.conf.backup_20261025_140000', createdAt: '2026-10-25 14:00:00', size: '2 KB' }];
        return reply(null);
      }
      unexpected.push(`${method} ${path}`);
      return reply(null, 501, 'Unexpected API request in browser fixture');
    });
    await use(state);
    page.off('pageerror', onPageError);
    expect(unexpected, 'No unconfigured endpoints or external services may be used').toEqual([]);
    expect(browserErrors, 'No uncaught browser errors').toEqual([]);
  }, { auto: true }],
});

export { expect };
