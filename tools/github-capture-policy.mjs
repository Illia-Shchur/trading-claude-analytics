export const REQUIRED_GITHUB_ENDPOINTS = Object.freeze([
  'repository',
  'branch_protection',
  'branch_head',
  'environment_protection',
  'writer_environment_protection',
  'rulesets',
  'ruleset_details',
  'installation',
  'settings_token_identity',
  'settings_token_secret',
  'evidence_writer_secret',
  'oidc_subject_restriction',
  'actions_permissions',
  'actions_selected_permissions',
  'actions_workflow_permissions',
])

export function firstNon200Endpoint(endpointStatuses, order = REQUIRED_GITHUB_ENDPOINTS) {
  for (const endpoint of order) {
    const status = Number(endpointStatuses?.[endpoint])
    if (status !== 200) return { endpoint, status: Number.isInteger(status) ? status : 0 }
  }
  return null
}

export function captureFailureReason(failure) {
  return failure ? `GITHUB_API_ENDPOINT_FAILED:${failure.endpoint}:${failure.status}` : null
}

export function selectCaptureStatus({ allVerified, endpointStatuses } = {}) {
  if (allVerified === true) return 200
  return firstNon200Endpoint(endpointStatuses)?.status || 0
}
