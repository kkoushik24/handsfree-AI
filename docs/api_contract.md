# Backend API contract

Base URL: `http://localhost:8000`. Start the backend from the repository root with:

```powershell
uvicorn backend.app:app --reload --host 0.0.0.0 --port 8000
```

The current Android `MainActivity` is a starter screen and does not configure a backend URL or make API calls yet. When adding the Android HTTP client, use `http://localhost:8000` and run `adb reverse tcp:8000 tcp:8000` over USB. A Wi-Fi alternative is `http://<laptop-LAN-IP>:8000` when both devices share a network and the laptop firewall allows the connection.

## `POST /plan`

Accepts JSON describing the user goal and current sanitized screen observation. `goal` is required; other fields are optional.

```json
{
  "task_id": "task-42",
  "goal": "Open Settings",
  "app_package": "com.android.launcher",
  "elements": [
    {"id": "settings-button", "text": "Settings", "clickable": true}
  ],
  "screen_hash": "screen-abc123",
  "history": [],
  "step": 0
}
```

Each element accepts `id` (required), nullable `text` and `desc`, nullable integer `bounds`, and booleans `clickable` (default `true`), `editable` (default `false`), and `sensitive` (default `false`). `step` must be nonnegative. `history` is an array of strings.

Success returns a `PlanResponse`:

```json
{
  "task_id": "task-42",
  "step": 0,
  "screen_hash": "screen-abc123",
  "action": {
    "type": "tap",
    "target_id": "settings-button",
    "text": null,
    "reason": "Open Settings",
    "needs_approval": true
  },
  "steps": [
    {"action": "tap", "target": "settings-button", "text": null, "reason": "Open Settings", "needs_approval": true},
    {"action": "done", "target": null, "text": null, "reason": "Task complete", "needs_approval": false}
  ],
  "error": null
}
```

Only `action` is executable against the current observation. `steps` is a preview; re-observe and request a new plan before executing a later step. The response echoes `screen_hash`; the Android client should compare it with its current screen before acting.

Supported `steps[].action` values are `open_app`, `tap`, `type_text`, `scroll`, `back`, `ask_user`, and `done`. The executable `action.type` uses those values except `type_text` is serialized as `type`. Example executable action objects:

| Action | Example `action` |
|---|---|
| `open_app` | `{"type":"open_app","target_id":"com.android.settings","text":null,"reason":"Open Settings","needs_approval":true}` |
| `tap` | `{"type":"tap","target_id":"settings-button","text":null,"reason":"Open Settings","needs_approval":true}` |
| `type_text` / `type` | `{"type":"type","target_id":"search-box","text":"Wi-Fi","reason":"Search settings","needs_approval":true}` |
| `scroll` | `{"type":"scroll","target_id":"settings-list","text":null,"reason":"Find the next setting","needs_approval":true}` |
| `back` | `{"type":"back","target_id":null,"text":null,"reason":"Return to the previous screen","needs_approval":true}` |
| `ask_user` | `{"type":"ask_user","target_id":"pin-field","text":null,"reason":"Sensitive action detected. Please complete this yourself.","needs_approval":true}` |
| `done` | `{"type":"done","target_id":null,"text":null,"reason":"Task complete","needs_approval":false}` |

Sensitive or ungrounded actions are converted to `ask_user`; sensitive text is removed. The `/plan?mock=true` query selects the mock planner, and the demo goal `send message to rahul saying hello` also selects it. Both still use the same safety validation.

The offline sample response at [`mock_plan_response.json`](mock_plan_response.json) can be loaded by Android clients without a backend connection.

## Other endpoints

- `GET /health` returns `status`, `model`, `ollama_reachable`, and `model_ready`.
- `POST /speak?text=Hello` returns WAV audio. If `SARVAM_API_KEY` is missing, it returns HTTP 503 with `{"error":"voice disabled: SARVAM_API_KEY not set"}`.
- `POST /transcribe` accepts multipart form field `file` and returns `{"text":"recognized words"}`. If the key is missing, it returns the same HTTP 503 JSON error.
