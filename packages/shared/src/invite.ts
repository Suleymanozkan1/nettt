/** Colyseus room ids used as invite codes: short, URL-safe. Anything else is rejected. */
export const INVITE_CODE = /^[A-Za-z0-9_-]{4,16}$/;

export const INVITE_SCHEME = 'golgekuklaci';

/**
 * Extracts a duel invite code from a deep link or web link:
 *   golgekuklaci://duel/<code>   (native deep link)
 *   https://<host>/?duel=<code>  (web link; host is not trusted for anything but the code)
 * Returns null for anything malformed, so untrusted URLs can never inject other actions.
 */
export function parseInviteUrl(raw: string): string | null {
  let url: URL;
  try { url = new URL(raw); } catch { return null; }
  let code: string | null = null;
  if (url.protocol === `${INVITE_SCHEME}:`) {
    // golgekuklaci://duel/CODE → host "duel", pathname "/CODE"
    if (url.hostname !== 'duel') return null;
    try { code = decodeURIComponent(url.pathname.replace(/^\//, '')); } catch { return null; }
  } else if (url.protocol === 'https:' || url.protocol === 'http:') {
    code = url.searchParams.get('duel');
  } else {
    return null;
  }
  return code && INVITE_CODE.test(code) ? code : null;
}

export function inviteLinks(code: string, webOrigin: string): { deepLink: string; webLink: string } {
  return { deepLink: `${INVITE_SCHEME}://duel/${code}`, webLink: `${webOrigin}/?duel=${encodeURIComponent(code)}` };
}
