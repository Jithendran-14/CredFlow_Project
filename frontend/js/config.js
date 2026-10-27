// Only public browser configuration. Never put Oracle or machine credentials here.
export const config = Object.freeze({
  eligibilityBase: '/api/proxy/eligibility',
  auditBase: '/api/proxy/audit',
  authBase: '/api/proxy/auth',
  applicationBase: '/api/proxy/application',
  consentBase: '/api/proxy/consent',
  orchestrationBase: '/api/proxy/orchestration',
  integrationBase: '/api/proxy/integration',
  pollIntervalMs: 5000,
  // Enable upstream capabilities only after the teammates' contracts are verified.
  capabilities: { applications: true, consent: true, eligibility: true, audit: true },
  pageSize: 20,
});
export const terminalStates = new Set(['CHECK_FAILED','NOT_ELIGIBLE','OFFER_ACCEPTED','OFFER_DECLINED','OFFER_EXPIRED','REJECTED','CANCELLED']);
