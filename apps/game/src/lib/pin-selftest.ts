/**
 * CI only (builds with VITE_PIN_SELFTEST="<pinned url>,<wrong-pin url>"): probes both hosts through the native
 * HTTP stack and logs the outcome, so the iOS simulator job can read it from the app's console output.
 */
export async function runPinSelftest(urls: string[]): Promise<void> {
  for (const url of urls) {
    let result: string;
    try {
      // POST goes straight through the native bridge (CapacitorHttp → URLSession, which enforces the pins).
      const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
      result = `ok ${r.status}`;
    } catch (e) {
      result = `error ${e instanceof Error ? e.message : String(e)}`;
    }
    // eslint-disable-next-line no-console -- the CI job reads this line from the app's stdout
    console.log(`PIN_SELFTEST ${url} -> ${result}`);
  }
  // eslint-disable-next-line no-console -- see above
  console.log('PIN_SELFTEST DONE');
}
