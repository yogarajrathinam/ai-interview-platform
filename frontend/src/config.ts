/**
 * Development-slice configuration.
 *
 * The candidate id is supplied by the app because M5A has no authentication.
 * It matches the id seeded by the backend's DevDataSeeder so a fresh checkout
 * works with no setup. In M6 this disappears entirely: the candidate becomes
 * the authenticated subject and the client stops asserting who it is.
 *
 * Nothing secret belongs here, and nothing here is a credential — the values
 * are an identifier and a content key, both meaningless without a backend that
 * already trusts the caller, which only local and test backends do.
 */

export const DEV_CANDIDATE_ID =
  import.meta.env.VITE_CANDIDATE_ID ?? '00000000-0000-4000-8000-00000000d0c1'

export const TEMPLATE_KEY = import.meta.env.VITE_TEMPLATE_KEY ?? 'backend-engineer'
