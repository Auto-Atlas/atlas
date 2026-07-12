# The Neural Brain (live voice visual)

**How to run:** start the sidecar (`run.bat` in your atlas checkout), then the
app (`npm run dev` in this folder, or the Tauri build) — the brain renders in the
LiveVoicePanel above the chat input and full-screen at `http://localhost:5173/stage`.
It is a force-directed neuron/synapse graph (`src/components/Chat/NeuralBrain.tsx`)
drawn on canvas at 60fps, with five states: disconnected (dim, near-still), idle
(slow breathing), listening (cyan pulses travel INWARD, spawn rate and brightness
scaled by the real mic RMS streamed from the sidecar), thinking (violet core
flicker tracking actual LLM token ticks — it dims between chunks), and speaking
(gold pulses travel OUTWARD, scaled by the real TTS output RMS). High-rate signals
arrive over `ws://127.0.0.1:8765` and live in refs (`useJarvisBridge.signals`), so
the React tree re-renders only on conversational transitions, not per audio frame.

**How to verify it's real:** speak quietly, then loudly — inward pulse density
follows your actual volume, not a loop. Watch the violet flicker stutter exactly
when Ollama's token stream stutters. Then kill the sidecar: within ~2.5 seconds the
brain decays to calm (there is a staleness gate on `lastEventAt` in addition to the
socket-close handler). No WebSocket events, no motion — there are no free-running
fake activity loops anywhere in the component.
