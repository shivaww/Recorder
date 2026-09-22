// checks/detect_block_durations.js — sanity: the SAME tag/DUR regexes the
// patched MainActivity.readBlockDuration() uses, applied to the real block
// files. Expect block1=40, block2=34, block15=69 (declared #duration tags).
const fs = require('fs');
const files = ['block1.html', 'block2.html', 'block15.html'];
let bad = 0;
for (const f of files) {
  const html = fs.readFileSync(f, 'utf8');
  const tag = html.match(/<script[^>]*id=["']duration["'][^>]*>([\s\S]*?)<\/script>/);
  const fromTag = tag ? ((tag[1].match(/[" ](?:seconds|duration)[" ]\s*:\s*([0-9.]+)/) || [])[1]) : null;
  const fromVar = (html.match(/(?:var|let|const)\s+DUR\s*=\s*([0-9.]+)/) || [])[1];
  const dur = Number(fromTag || fromVar || 0);
  const ok = dur > 0;
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${f}: tag=${fromTag} var=${fromVar} -> ${dur}s`);
}
process.exit(bad ? 1 : 0);