# STT Service

A small cluster-internal speech-to-text service (`stt/`) for voice capture. It
wraps [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (CTranslate2)
behind one HTTP endpoint that returns word-level timestamps. The backend calls
it with the uploaded take and assembles lines from the words (see the voice
takes backend); clients never talk to it.

## Scope

- `POST /v1/transcribe`, `GET /healthz`, `GET /readyz`, exactly as below.
- Model `large-v3-turbo` by default (any faster-whisper alias, Hub repo id with
  CTranslate2 weights, or local directory), CUDA float16 or CPU int8.
- Model loading in the background at startup with retry and backoff; weights
  downloaded once into a persistent cache, offline start afterwards.
- Its own image (`stt/Dockerfile`, CUDA 12 + cuDNN 9 runtime base) and chart
  component (`stt.*`, `templates/stt/`), the backend's `STT_URL`, and the
  homelab deployment on node1's RTX 3090.

## Non-scope

- Authentication, persistence, rate limiting: it is reachable only inside the
  cluster through a ClusterIP Service, and the backend enforces auth and limits.
- Ingress or nginx routing. The STT Service is never exposed (the chart's
  Ingress and the frontend nginx only know the backend and frontend Services).
- Line splitting, transcript assembly, storing audio: backend responsibilities.
- Streaming / partial results, diarisation, translation.
- Horizontal scaling: one replica holds the model on one GPU.

## HTTP contract

`POST /v1/transcribe`, `multipart/form-data`:

| Field | Required | Meaning |
|-------|----------|---------|
| `audio` | yes | File in any container/codec PyAV (FFmpeg) decodes. At most `STT_MAX_UPLOAD_BYTES` (25 MiB). |
| `language` | no | ISO code (`en`, `de`, ...). Omitted or blank: `STT_DEFAULT_LANGUAGE` if set, else auto-detect. Case and surrounding whitespace are ignored. |
| `initial_prompt` | no | Passed to Whisper as the initial prompt; blank = none. |

200:

```json
{"language": "en", "duration_ms": 61234,
 "words": [{"text": "hello", "start_ms": 0, "end_ms": 420, "probability": 0.98}]}
```

- Decoded with `word_timestamps=True`, `vad_filter=True`, beam size
  `STT_BEAM_SIZE`.
- `duration_ms` is the decoded input length (before VAD), rounded.
- Words: `text` stripped of surrounding whitespace, punctuation kept as Whisper
  attaches it, whitespace-only words dropped, times rounded to ms (never
  negative, `end_ms >= start_ms`), `probability` clamped to [0, 1], ordered by
  `start_ms` (stable, so Whisper's order is kept for equal starts).
- Audio that decodes to zero samples returns `words: []`, `duration_ms: 0`,
  and `language` = the requested language or `und`.

Errors are `{"error": "<code>", "message": "..."}`:

| Status | Code | When |
|--------|------|------|
| 400 | `undecodable_audio` | Empty file, or PyAV cannot open/decode it. |
| 413 | `audio_too_large` | Audio file larger than `STT_MAX_UPLOAD_BYTES`; or the request's `Content-Length` exceeds that limit plus 1 MiB of form overhead (rejected before the body is parsed). |
| 422 | `invalid_request` | Missing `audio` field, not multipart, or a `language` the model does not support. |
| 503 | `model_not_ready` | Model still loading, or the last load failed (it is retried). |
| 500 | `transcription_failed` | Anything the recogniser raises while transcribing (CUDA OOM, CTranslate2 errors). |

Validation order: form fields (422), size and emptiness (413/400), readiness
(503), language (422, needs the loaded model's language list), recognition.
404/405 for unknown routes and methods use the same body shape
(`not_found`, `method_not_allowed`).

`GET /healthz` is 200 `{"status":"ok"}` whenever the process serves HTTP.
`GET /readyz` is 200 `{"status":"ok"}` once the model is loaded, otherwise 503
`{"status":"loading","attempt":n}` or
`{"status":"failed","attempt":n,"message":"<error>"}`.

## Flow

```
uvicorn start (stt_service.main)
  Settings.from_env(os.environ)     -- ConfigError -> log config.invalid, exit 2
  configure_logging(level)          -- key=value lines on stderr, uvicorn routed through it
  create_app(settings, whisper_loader(settings))
    lifespan: asyncio.create_task(ModelHolder.run())    -- /healthz is up immediately
      loop:
        state = Loading(attempt)
        asyncio.to_thread(loader):
          resolve_model(spec, cache_dir, offline)
            local dir (absolute or ./ path)?  -> use it
            download_model(local_files_only=True)  -> cache hit: no network at all
            cache miss + offline                    -> ModelFilesUnavailable
            cache miss                              -> download_model(local_files_only=False)
          WhisperModel(path, device, compute_type, cpu_threads, num_workers=1)
        ok   -> state = Ready(transcriber)            -- /readyz 200
        fail -> state = LoadFailed(attempt, error, delay); sleep(delay); attempt += 1
               (delay = initial * 2^(n-1), capped at max)

POST /v1/transcribe
  reject_oversized middleware: Content-Length > limit + 1 MiB -> 413
  FastAPI parses multipart (UploadFile spooled to /tmp past 1 MB)
  size > limit -> 413; size == 0 -> 400
  holder.require()                  -- ModelNotReady -> 503
  options = TranscribeOptions(language normalised/validated, prompt)
  async with lock:                  -- one recognition at a time
    asyncio.to_thread(transcriber.transcribe, file, options)
      decode(): faster_whisper.decode_audio -> 16 kHz mono float32
                (av.FFmpegError / IndexError / ValueError -> UndecodableAudio)
      model.transcribe(samples, word_timestamps, vad_filter, ...) -- lazy generator,
      consumed inside the thread -> RawTranscript(seconds, raw words)
    non-SttError exception -> TranscriptionFailed (500)
  to_transcript(raw) -> Transcript (ms, stripped, ordered)
  log transcribe.done; return TranscriptionResponse
```

The only shared mutable state is the holder's state (written only by its own
task, read by handlers on the same event loop) and the `asyncio.Lock` that
serialises recognition. Blocking work (model load, decode, recognition) runs in
worker threads via `asyncio.to_thread`; nothing else uses threads.

### Kubernetes

```
backend pod --STT_URL=http://<fullname>-stt:<port>--> Service <fullname>-stt (ClusterIP)
                                                       -> Deployment <fullname>-stt (1 replica, Recreate)
                                                            /models  <- emptyDir | hostPath | PVC
                                                            /tmp     <- emptyDir (read-only root fs)
```

- `startupProbe` and `livenessProbe` hit `/healthz`, so a long first download
  never restarts the pod. `readinessProbe` hits `/readyz`, so the Service only
  routes to a pod with a loaded model, and `helm upgrade --wait` waits for it.
  The first download is therefore bounded by the helm timeout (homelab: 10m),
  not by a probe.
- `strategy: Recreate`: the old pod releases the GPU (and the RWO cache) before
  the new one starts, so an upgrade never waits for a second GPU.
- GPUs are added as `stt.gpuResourceName: stt.gpus` to both requests and limits.
  `device: cuda` with `gpus: 0` fails to render.
- `HF_HUB_OFFLINE=1` is set only when `stt.offline: true`. Without it the
  service still never contacts the Hub when the weights are cached (cache-first
  lookup), so pods restart fine with no internet.
- The chart's `helm test` pod also fetches `<stt url>/readyz` when STT is
  enabled. Alert rules cover the pod through the `<fullname>-` name prefix.

### Homelab

`deploy/helm/environments/homelab.yaml` enables STT on node1 (`nodeSelector
kubernetes.io/hostname: node1`) with one `nvidia.com/gpu` (the RTX 3090) and
`runtimeClassName: nvidia`: node1's containerd default runtime is `runc`, and
the GPU operator registers the `nvidia` RuntimeClass.

The model cache is a **hostPath** on node1, `/home/nymph/models/whisper`, not a
PVC. Weights then survive pod restarts, PVC deletion and `helm uninstall`, sit
on the same disk as the other node1 data (backups), and the pod is pinned to
node1 anyway because of the GPU. The volume is `type: Directory`, so the
directory must exist before the first deploy (a typo fails the pod instead of
creating a root-owned directory), and the pod runs as `1000:1000` (nymph) so it
can write there:

```bash
ssh node1 mkdir -p /home/nymph/models/whisper
```

The first deploy downloads large-v3-turbo (about 1.6 GB) inside the 10 minute
`helm --wait` window of `deploy.sh`; on a slow link, warm the cache first or
raise `LYRICS_HELM_TIMEOUT`. After the weights are cached, `stt.offline: true`
can be set to forbid any Hub access.

## Configuration (environment)

| Variable | Default | Meaning |
|----------|---------|---------|
| `STT_MODEL` | `large-v3-turbo` | faster-whisper alias, Hub repo id, or absolute / `./` local directory. |
| `STT_DEVICE` | `cuda` | `cuda` or `cpu` (case-insensitive). |
| `STT_COMPUTE_TYPE` | `float16` (cuda) / `int8` (cpu) | Any CTranslate2 compute type. |
| `STT_MODEL_CACHE_DIR` | Hub default (`$HF_HOME/hub`) | Download/cache dir. The image sets `/models/hub` with `HF_HOME=/models`. |
| `STT_MAX_UPLOAD_BYTES` | `26214400` | Audio size limit. |
| `STT_DEFAULT_LANGUAGE` | unset | 2-3 letter code used when a request has none. |
| `STT_OFFLINE` | `false` | Only use cached weights; never download. |
| `STT_BEAM_SIZE` | `5` | Beam size. |
| `STT_CPU_THREADS` | `0` | CTranslate2 intra-op threads (0 = its default). |
| `STT_HOST` / `STT_PORT` | `0.0.0.0` / `8000` | Bind address. |
| `STT_LOG_LEVEL` | `info` | `debug`, `info`, `warning`, `error`. |
| `STT_LOAD_RETRY_INITIAL_SECONDS` / `STT_LOAD_RETRY_MAX_SECONDS` | `5` / `300` | Load retry backoff. |

Invalid values stop the process with exit code 2 and one `config.invalid`
log line naming the variable.

Chart values map onto these (`stt.model`, `device`, `computeType`,
`defaultLanguage`, `beamSize`, `maxUploadBytes`, `offline`, `logLevel`;
`extraEnv` for the rest).

## Files

| File | Role |
|------|------|
| `stt/pyproject.toml`, `stt/uv.lock` | uv project; direct dependencies pinned exactly, `exclude-newer` keeps every resolved release at least a week old. `huggingface-hub` is held at 0.36.2 and `av` at 18.1.0 because faster-whisper 1.2.1 needs the requests-based hub client and calls `av.open(metadata_errors=...)`, removed in av 19. |
| `stt/src/stt_service/main.py` | `main()` (`stt-service` script): settings, logging, `uvicorn.run` (access log off). |
| `stt/src/stt_service/app.py` | `create_app(settings, loader) -> FastAPI`: routes, size middleware, error handlers, recognition lock, lifespan task. |
| `stt/src/stt_service/schemas.py` | Pydantic wire models: `TranscriptionResponse`, `WordOut`, `ErrorBody`, `StatusBody`. |
| `stt/src/stt_service/domain.py` | Frozen dataclasses: `TranscribeOptions`, `RawWord`/`RawTranscript` (seconds), `Word`/`Transcript` (ms). |
| `stt/src/stt_service/transcriber.py` | `Transcriber` protocol (`languages`, `transcribe(audio, options) -> RawTranscript`), `ModelLoader = Callable[[], Transcriber]`. |
| `stt/src/stt_service/mapping.py` | `to_transcript(RawTranscript) -> Transcript`, `seconds_to_ms`. |
| `stt/src/stt_service/readiness.py` | `ModelState = Loading | Ready | LoadFailed`, `RetryPolicy`, `ModelHolder` (`state`, `require()`, `run()`). |
| `stt/src/stt_service/whisper.py` | faster-whisper backend: `decode()`, `WhisperTranscriber`, `whisper_loader(settings)`. |
| `stt/src/stt_service/model_files.py` | `resolve_model()` cache-first weight lookup, `ModelFilesUnavailable`. |
| `stt/src/stt_service/config.py` | `Settings.from_env(env)`, `Device` enum, validation. |
| `stt/src/stt_service/errors.py` | `SttError` hierarchy (`code`, `status`): `InvalidRequest`, `UndecodableAudio`, `AudioTooLarge`, `ModelNotReady`, `TranscriptionFailed`; `ConfigError`. |
| `stt/src/stt_service/logs.py` | `KeyValueFormatter`, `log_event(logger, level, event, **fields)`, `configure_logging`. |
| `stt/tests/` | pytest suite. `fakes.py` (`FakeTranscriber`, `GatedLoader`) drive the HTTP tests without a model; `test_decode.py` uses real PyAV; `test_smoke_tiny.py` runs the real backend with `tiny` on CPU and is skipped unless the weights are cached (`STT_SMOKE_CACHE_DIR` or the HF cache). |
| `stt/Dockerfile`, `stt/.dockerignore` | Two stages: `ubuntu:24.04` + uv 0.13.5 builds a uv-managed Python 3.12 venv (frozen, no dev deps, non-editable); runtime `nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04` gets only `/opt/python` and `/app/.venv` (no uv, so nothing can resolve packages at runtime), user `10001`, `HF_HOME=/models`, `STT_MODEL_CACHE_DIR=/models/hub`. About 5 GB uncompressed, almost all CUDA/cuDNN. |
| `deploy/helm/lyrics-canvas/templates/stt/deployment.yaml` | Deployment (env, probes, GPU resources, runtime class, volumes, Recreate). |
| `deploy/helm/lyrics-canvas/templates/stt/service.yaml` | ClusterIP Service (type not configurable). |
| `deploy/helm/lyrics-canvas/templates/stt/pvc.yaml` | Optional `<fullname>-stt-models` claim, `helm.sh/resource-policy: keep`. |
| `deploy/helm/lyrics-canvas/templates/_helpers.tpl` | `lyrics-canvas.stt.fullname`, `lyrics-canvas.stt.url`, `lyrics-canvas.stt.claimName`. |
| `deploy/helm/lyrics-canvas/templates/backend/deployment.yaml` | `STT_URL` env when `stt.enabled`. |
| `deploy/helm/lyrics-canvas/templates/tests/test-connection.yaml` | Also checks `/readyz` of STT when enabled. |
| `deploy/helm/tests/test_chart.py`, `deploy/helm/tests/values/stt*.yaml` | `Stt*Test` render/validation classes and `HomelabSttTest`. |
| `deploy/helm/environments/homelab.yaml` | `stt:` block for node1. |
| `deploy/homelab/deploy.sh` | Builds/pushes `lyrics-canvas-stt` and passes `stt.image.tag`. |

Run the service tests with `cd stt && uv run pytest`; with the tiny weights
cached (`uv run python -c "from faster_whisper.utils import download_model;
download_model('tiny')"`) the smoke test runs too.

## Invariants and constraints

- The response and error shapes above are the contract with the backend
  (voice capture contract, section 1); `invalid_request` (422) and the
  `not_found` / `method_not_allowed` bodies are additions for requests the
  contract does not cover.
- The STT Service is ClusterIP only and is never referenced by the Ingress or
  the frontend nginx (asserted by `SttEnabledTest.test_never_routed_by_ingress`).
- `/healthz` never depends on the model; `/readyz` is 200 only in `Ready`.
- At most one recognition runs at a time per process.
- Model state is a tagged union; a transcriber exists only in `Ready`.
- With cached weights, startup makes no network request regardless of
  `STT_OFFLINE` (cache-first lookup). `STT_OFFLINE=true` additionally makes a
  cache miss fatal for that attempt instead of downloading.
- Model aliases and repo ids are never resolved against the working directory;
  only absolute or `./`-relative specs are treated as local paths.
- The runtime image contains no uv or pip; the venv is complete at build time.
- `stt.device=cuda` requires `stt.gpus >= 1`; the Deployment always has one
  replica and the Recreate strategy.
- The homelab hostPath directory must exist and be writable by uid 1000 before
  the first deploy.
- faster-whisper is pinned with `av` 18.1.0 and `huggingface-hub` 0.36.2;
  upgrading either requires re-running `test_decode.py` and the smoke test.
