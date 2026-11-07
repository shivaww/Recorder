'''VibeVoice voice server - same HTTP API as qwen3tts/server.py.'''
import os
import subprocess
import threading
import traceback
import uuid

import soundfile as sf
from flask import Flask, jsonify, request, send_file
from flask_cors import CORS

import vv_engine

MAX_REF_SECONDS = 30
OUT_DIR = '/kaggle/working/vv_out' if os.path.isdir('/kaggle/working') else '/tmp/vv_out'
os.makedirs(OUT_DIR, exist_ok=True)

app = Flask(__name__)
CORS(app)
JOBS = {}


def save_ref(upload):
    '''Save the uploaded voice sample as 24 kHz mono WAV (ffmpeg handles any format).'''
    stem = os.path.join(OUT_DIR, f'ref_{uuid.uuid4().hex[:8]}')
    upload.save(stem + '.in')
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', stem + '.in',
                    '-ac', '1', '-ar', '24000', stem + '.wav'], check=True, capture_output=True)
    os.remove(stem + '.in')
    if sf.info(stem + '.wav').duration > MAX_REF_SECONDS:
        raise ValueError(f'reference audio is longer than {MAX_REF_SECONDS}s: trim the clip and retry')
    return stem + '.wav'


@app.route('/health')
def health():
    return jsonify(status='ok', engine='vibevoice', device=vv_engine.DEVICE,
                   model=vv_engine.MODEL_PATH, loaded=vv_engine.loaded())


@app.route('/generate', methods=['POST'])
def generate():
    text = request.form.get('text', '')
    if 'file' in request.files and request.files['file'].filename:
        text = request.files['file'].read().decode('utf-8', errors='ignore')
    if not text.strip():
        return jsonify(error='no text or file provided'), 400
    upload = request.files.get('ref_audio')
    if not upload or not upload.filename:
        return jsonify(error='ref_audio is required: upload the voice sample to clone'), 400
    try:
        ref_path = save_ref(upload)
    except Exception as e:
        return jsonify(error=f'cannot use reference audio ({e})'), 400

    job_id = uuid.uuid4().hex[:12]
    JOBS[job_id] = {'status': 'processing', 'path': None, 'duration': None, 'error': None}
    out_path = os.path.join(OUT_DIR, f'{job_id}.wav')

    def worker():
        try:
            with vv_engine.lock:
                duration = vv_engine.synthesize(text, ref_path, out_path, job_id)
            JOBS[job_id].update(status='done', path=out_path, duration=round(duration, 2))
            print(f'[{job_id}] done ({duration:.1f}s)')
        except Exception as e:
            traceback.print_exc()
            JOBS[job_id].update(status='error', error=str(e))
            print(f'[{job_id}] failed: {e}')

    threading.Thread(target=worker, daemon=True).start()
    return jsonify(id=job_id, status='processing', mode='vibevoice'), 202


@app.route('/status/<job_id>')
def status(job_id):
    job = JOBS.get(job_id)
    if not job:
        return jsonify(error='not found'), 404
    return jsonify(id=job_id, **job)


@app.route('/download/<job_id>')
def download(job_id):
    job = JOBS.get(job_id)
    if not job:
        return jsonify(error='not found'), 404
    if job['status'] == 'processing':
        return jsonify(error='still processing'), 425
    if job['status'] == 'error':
        return jsonify(error=job['error']), 500
    if not job['path'] or not os.path.isfile(job['path']):
        return jsonify(error='file missing'), 404
    return send_file(job['path'], mimetype='audio/wav', as_attachment=True,
                     download_name=f'vibevoice_{job_id}.wav')


if __name__ == '__main__':
    PORT = int(os.environ.get('PORT', '5000'))
    print(f'starting flask on port {PORT} ...')
    app.run(host='0.0.0.0', port=PORT)
