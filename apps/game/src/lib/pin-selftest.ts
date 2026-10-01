/**
 * CI only (builds with VITE_PIN_SELFTEST="<pinned url>,<wrong-pin url>"): probes both hosts through the native
 * HTTP stack and logs the outcome. The results are also reported to the first (pinned) host, whose CI proxy logs
 * request lines, because the app's stdout is buffered on the simulator.
 */
export async function runPinSelftest(urls: string[]): Promise<void> {
  const results: string[] = [];
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
    results.push(`${new URL(url).hostname} -> ${result}`);
  }
  // eslint-disable-next-line no-console -- see above
  console.log('PIN_SELFTEST DONE');
  const report = new URL('/__pin_selftest', urls[0]);
  results.forEach((r, i) => report.searchParams.set(`r${i}`, r));
  await fetch(report.toString(), { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }).catch(() => undefined);
}
