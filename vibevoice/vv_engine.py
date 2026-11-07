'''VibeVoice engine: one continuous take per script, cloned from a reference clip.'''
import os
import re
import threading

import torch

import pronounce
import text_norm

MODEL_PATH = os.environ.get('VV_MODEL', 'vibevoice/VibeVoice-7B')
CFG_SCALE = float(os.environ.get('VV_CFG', '1.3'))
DDPM_STEPS = int(os.environ.get('VV_STEPS', '20'))
# A script up to this many characters is generated in ONE pass (no seams).
# Longer scripts are split at sentence ends so memory stays bounded.
MAX_PASS_CHARS = int(os.environ.get('VV_MAX_CHARS', '20000'))
VO_MARKER = '{here complete voice over}'
SAMPLE_RATE = 24000
DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

_state = {}
lock = threading.Lock()


def _dtype():
    forced = os.environ.get('VV_DTYPE')
    if forced:
        return getattr(torch, forced)
    if DEVICE == 'cuda':
        # bf16 needs compute capability 8.0+; T4/P100 run fp16 instead.
        return torch.bfloat16 if torch.cuda.get_device_capability()[0] >= 8 else torch.float16
    return torch.float32


def _no_split(meta):
    # Layer classes that must stay whole on one GPU, gathered from every sub-model.
    names = set()
    for m in meta.modules():
        names |= set(getattr(m, '_no_split_modules', None) or [])
    print(f'no-split layer classes: {sorted(names)}')
    return sorted(names)


def _device_map(cls):
    # The 7B model does not fit one T4, so shard layers across every GPU.
    # device_map='auto' leaves loose tensors (model.speech_bias_factor) with no
    # device, so build the map here and pin every uncovered tensor to GPU 0.
    n = torch.cuda.device_count() if DEVICE == 'cuda' else 0
    if n < 2:
        return DEVICE
    from accelerate import init_empty_weights, infer_auto_device_map
    cfg = cls.config_class.from_pretrained(MODEL_PATH)
    with init_empty_weights():
        meta = cls._from_config(cfg, torch_dtype=_dtype())
    gib = float(os.environ.get('VV_GPU_GIB', '12'))
    # The fork mixes embeddings, tokenizer, connector and diffusion-head tensors
    # with no device moves, so all of those stay on GPU 0. Only LM layers split.
    def is_lm(name):
        return name == 'lm_head' or name.startswith('model.language_model')
    other = sum(p.numel() * p.element_size() for k, p in meta.named_parameters() if not is_lm(k))
    other_gib = other / 2 ** 30
    caps = {i: f'{gib}GiB' for i in range(n)}
    caps[0] = f'{max(1.0, gib - other_gib - 1.0):.2f}GiB'
    print(f'non-LM params {other_gib:.2f} GiB pinned to GPU 0, GPU 0 LM cap {caps[0]}')
    dmap = infer_auto_device_map(
        meta, max_memory=caps,
        no_split_module_classes=_no_split(meta), dtype=_dtype())
    for k in list(dmap):
        if k != '' and not is_lm(k):
            dmap[k] = 0
    names = [k for k, _ in meta.named_parameters()] + [k for k, _ in meta.named_buffers()]
    for name in names:
        if not any(k == '' or name == k or name.startswith(k + '.') for k in dmap):
            dmap[name] = 0
            print(f'pinned {name} to GPU 0')
    print(f'device map: {len(dmap)} entries over {n} GPUs')
    return dmap


def _align_speech(model):
    # forward() writes speech embeds into the text embeds, which live on the
    # embedding GPU. The connectors can sit on another GPU, so move outputs over.
    orig = model._process_speech_inputs

    def wrapped(*a, **k):
        dev = model.model.get_input_embeddings().weight.device
        return tuple(t.to(dev) if torch.is_tensor(t) else t for t in orig(*a, **k))

    model._process_speech_inputs = wrapped


def loaded():
    return 'model' in _state


def get_model():
    if 'model' not in _state:
        from vibevoice.modular.modeling_vibevoice_inference import VibeVoiceForConditionalGenerationInference
        from vibevoice.processor.vibevoice_processor import VibeVoiceProcessor
        print(f'Loading {MODEL_PATH} ...')
        proc = VibeVoiceProcessor.from_pretrained(MODEL_PATH)
        attn = 'sdpa'
        try:
            import flash_attn  # noqa: F401
            attn = 'flash_attention_2'
        except Exception:
            pass
        model = VibeVoiceForConditionalGenerationInference.from_pretrained(
            MODEL_PATH, torch_dtype=_dtype(), device_map=_device_map(VibeVoiceForConditionalGenerationInference), attn_implementation=attn)
        model.eval()
        _align_speech(model)
        model.set_ddpm_inference_steps(num_steps=DDPM_STEPS)
        _state.update(model=model, proc=proc)
        print(f'model ready (attn={attn}, dtype={_dtype()})')
    return _state['model'], _state['proc']


def extract_vo(text):
    if VO_MARKER not in text:
        return text.strip()
    return ' '.join(p.strip() for p in text.split(VO_MARKER)[1:] if p.strip())


def prepare(text):
    # Respell tricky words first, then expand numbers/abbreviations like the Qwen server.
    return text_norm.normalize_text(pronounce.apply(text))


def split_passes(text, limit=MAX_PASS_CHARS):
    # prepare() leaves single spaces only, so a space after . ! ? ends a sentence.
    if len(text) <= limit:
        return [text]
    out, cur = [], ''
    for s in re.split(r'(?<=[.!?]) ', text):
        if cur and len(cur) + len(s) + 1 > limit:
            out.append(cur)
            cur = s
        else:
            cur = f'{cur} {s}'.strip()
    return out + ([cur] if cur else [])


def run_pass(model, proc, text, ref_path):
    inputs = proc(text=[f'Speaker 1: {text}'], voice_samples=[[ref_path]],
                  padding=True, return_tensors='pt', return_attention_mask=True)
    for k, v in inputs.items():
        if torch.is_tensor(v):
            inputs[k] = v.to(DEVICE)
    out = model.generate(**inputs, max_new_tokens=None, cfg_scale=CFG_SCALE,
                         tokenizer=proc.tokenizer, generation_config={'do_sample': False},
                         verbose=True, is_prefill=True)
    return out.speech_outputs[0]


def synthesize(text, ref_path, out_path, job_id):
    model, proc = get_model()
    passes = split_passes(prepare(extract_vo(text)))
    parts = []
    for i, p in enumerate(passes):
        print(f'[{job_id}] pass {i + 1}/{len(passes)} ({len(p)} chars)')
        parts.append(run_pass(model, proc, p, ref_path))
    audio = parts[0] if len(parts) == 1 else torch.cat(parts, dim=-1)
    proc.save_audio(audio, output_path=out_path)
    return audio.shape[-1] / SAMPLE_RATE
