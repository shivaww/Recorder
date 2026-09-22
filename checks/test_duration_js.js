// checks/test_duration_js.js — regression test for kaggle/validator.py DURATION_JS.
// Extracts the JS probe from the Python source and runs it against mocked
// DOMs, so the priority chain (broll.duration -> #duration tag -> animation
// endTime scan) is verified against the real shipped code, not a copy.
const fs = require('fs');
const path = require('path');
const src = fs.readFileSync(path.join(__dirname, '..', 'kaggle', 'validator.py'), 'utf8');
const m = src.match(/DURATION_JS = """([\s\S]*?)"""/);
if (!m) {
  console.error('FAIL: DURATION_JS block not found in kaggle/validator.py');
  process.exit(1);
}
const fn = eval('(' + m[1] + ')');

let failures = 0;
function run(name, opts, expected) {
  const w = {};
  if (opts.brollDur !== undefined) w.__broll = { duration: () => opts.brollDur };
  const tagEl = opts.tagJson !== undefined ? { textContent: opts.tagJson } : null;
  const anims = (opts.anims || []).map((end) => ({
    effect: { getComputedTiming: () => ({ endTime: end }) },
  }));
  const savedW = globalThis.window;
  const savedD = globalThis.document;
  globalThis.window = w;
  globalThis.document = {
    querySelector: (sel) => (sel === 'script#duration' ? tagEl : null),
    getAnimations: () => anims,
  };
  let got;
  try {
    got = fn();
  } catch (e) {
    got = 'THREW: ' + e.message;
  }
  globalThis.window = savedW;
  globalThis.document = savedD;
  const ok = got === expected;
  if (!ok) failures++;
  console.log(
    (ok ? 'PASS ' : 'FAIL ') + name + ' -> ' + JSON.stringify(got) +
      (ok ? '' : ' (expected ' + JSON.stringify(expected) + ')')
  );
}

run('broll.duration wins over tag and anims', { brollDur: 69, tagJson: '{"seconds": 5}', anims: [1000] }, 69);
run('tag seconds key', { tagJson: '{"seconds": 34}' }, 34);
run('tag legacy duration key', { tagJson: '{"duration": 45.5}' }, 45.5);
run('garbage tag falls through to anims', { tagJson: 'not json', anims: [12000] }, 12);
run('animation endTime max', { anims: [40000, 34000, 2500] }, 40);
run('infinite endTime skipped', { anims: [Infinity, 2000] }, 2);
run('nothing declared returns 0', {}, 0);

if (failures) {
  console.error(failures + ' test(s) failed');
  process.exit(1);
}
console.log('ALL PASS');
