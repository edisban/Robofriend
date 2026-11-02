import { useEffect, useRef } from "react";
import { Box } from "@mui/material";

export type WavePhase = "listening" | "thinking" | "speaking" | "muted";

type SiriWaveProps = {
  phase: WavePhase;
  /** Live mic analyser; when absent the wave runs on its own rhythm. */
  analyser: AnalyserNode | null;
  height?: number;
};

type Lobe = { pos: number; drift: number; width: number; freq: number; speed: number; seed: number };
type Layer = { color: [number, number, number]; lobes: Lobe[]; gain: number };

const LAYERS: Layer[] = [
  { color: [34, 211, 238], gain: 1.0, lobes: [] },  // cyan
  { color: [139, 92, 246], gain: 0.95, lobes: [] }, // violet
  { color: [236, 72, 153], gain: 0.9, lobes: [] },  // pink
  { color: [59, 130, 246], gain: 0.8, lobes: [] },  // blue
];

// Deterministic pseudo-random so the wave looks the same on every open.
function rand(n: number) {
  const x = Math.sin(n * 12.9898) * 43758.5453;
  return x - Math.floor(x);
}

LAYERS.forEach((layer, li) => {
  layer.lobes = [0, 1, 2].map((j) => {
    const s = li * 10 + j;
    return {
      pos: -0.6 + rand(s + 1) * 1.2,
      drift: 0.25 + rand(s + 2) * 0.35,
      width: 0.18 + rand(s + 3) * 0.22,
      freq: 5 + rand(s + 4) * 7,
      speed: 0.6 + rand(s + 5) * 1.4,
      seed: rand(s + 6) * Math.PI * 2,
    };
  });
});

/** Target loudness (0..1) for the current phase. */
function targetLevel(phase: WavePhase, analyser: AnalyserNode | null, buf: Uint8Array<ArrayBuffer> | null, t: number) {
  if (phase === "muted") return 0.03;
  if (phase === "thinking") return 0.16 + 0.06 * Math.sin(t * 3.2);
  if (phase === "speaking") {
    // Speech-like envelope: syllable pulses riding on slower phrase swells.
    const syllables = Math.abs(Math.sin(t * 9.3)) * 0.55 + Math.abs(Math.sin(t * 5.1 + 1.3)) * 0.35;
    const phrase = 0.55 + 0.45 * Math.sin(t * 1.1);
    return Math.min(1, 0.18 + syllables * phrase);
  }
  if (!analyser || !buf) return 0.06;
  analyser.getByteTimeDomainData(buf);
  let sum = 0;
  for (let i = 0; i < buf.length; i++) {
    const v = (buf[i] - 128) / 128;
    sum += v * v;
  }
  const rms = Math.sqrt(sum / buf.length);
  return Math.min(1, 0.05 + rms * 5.5);
}

export default function SiriWave({ phase, analyser, height = 220 }: SiriWaveProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const phaseRef = useRef(phase);
  const analyserRef = useRef(analyser);

  useEffect(() => {
    phaseRef.current = phase;
  }, [phase]);

  useEffect(() => {
    analyserRef.current = analyser;
  }, [analyser]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let frame = 0;
    let level = 0.05;
    let buf: Uint8Array<ArrayBuffer> | null = null;
    const start = performance.now();

    const resize = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const rect = canvas.getBoundingClientRect();
      canvas.width = Math.round(rect.width * dpr);
      canvas.height = Math.round(rect.height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);

    const draw = () => {
      const t = (performance.now() - start) / 1000;
      const w = canvas.clientWidth;
      const h = canvas.clientHeight;
      const cy = h / 2;
      const a = analyserRef.current;
      if (a && (!buf || buf.length !== a.fftSize)) buf = new Uint8Array(a.fftSize);

      const target = targetLevel(phaseRef.current, a, buf, t);
      // Rise fast, fall slower — like a VU meter.
      level += (target - level) * (target > level ? 0.25 : 0.08);

      ctx.clearRect(0, 0, w, h);
      ctx.globalCompositeOperation = "lighter";

      const maxAmp = h * 0.42;
      const step = 2;

      LAYERS.forEach((layer, li) => {
        const [r, g, b] = layer.color;
        const ys: number[] = [];
        for (let px = 0; px <= w; px += step) {
          const x = (px / w) * 2 - 1; // -1..1
          // Pin both ends to the centre line.
          const edge = Math.pow(2 / (2 + Math.pow(x * 1.6, 4)), 2);
          let y = 0;
          for (const lobe of layer.lobes) {
            const pos = lobe.pos + Math.sin(t * lobe.drift + lobe.seed) * 0.35;
            const env = Math.exp(-((x - pos) ** 2) / (2 * lobe.width ** 2));
            const breathe = 0.6 + 0.4 * Math.sin(t * lobe.speed * 0.7 + lobe.seed + li);
            y += env * breathe * Math.sin(x * lobe.freq - t * lobe.speed * 2.2 + lobe.seed);
          }
          ys.push(y * edge * level * layer.gain * maxAmp);
        }

        const grad = ctx.createLinearGradient(0, cy - maxAmp, 0, cy + maxAmp);
        grad.addColorStop(0, `rgba(${r},${g},${b},0.05)`);
        grad.addColorStop(0.5, `rgba(${r},${g},${b},0.55)`);
        grad.addColorStop(1, `rgba(${r},${g},${b},0.05)`);

        // Filled ribbon mirrored around the centre line.
        ctx.beginPath();
        ys.forEach((y, i) => (i === 0 ? ctx.moveTo(0, cy - y) : ctx.lineTo(i * step, cy - y)));
        for (let i = ys.length - 1; i >= 0; i--) ctx.lineTo(i * step, cy + ys[i]);
        ctx.closePath();
        ctx.fillStyle = grad;
        ctx.fill();

        // Bright edge strokes.
        ctx.lineWidth = 1.4;
        ctx.strokeStyle = `rgba(${r},${g},${b},0.9)`;
        ctx.shadowColor = `rgba(${r},${g},${b},0.9)`;
        ctx.shadowBlur = 14;
        ctx.beginPath();
        ys.forEach((y, i) => (i === 0 ? ctx.moveTo(0, cy - y) : ctx.lineTo(i * step, cy - y)));
        ctx.stroke();
        ctx.beginPath();
        ys.forEach((y, i) => (i === 0 ? ctx.moveTo(0, cy + y) : ctx.lineTo(i * step, cy + y)));
        ctx.stroke();
        ctx.shadowBlur = 0;
      });

      // Thin white core line, like the resting state of Siri.
      const core = ctx.createLinearGradient(0, 0, w, 0);
      core.addColorStop(0, "rgba(255,255,255,0)");
      core.addColorStop(0.5, `rgba(255,255,255,${0.45 + level * 0.4})`);
      core.addColorStop(1, "rgba(255,255,255,0)");
      ctx.fillStyle = core;
      ctx.fillRect(0, cy - 0.75, w, 1.5);

      ctx.globalCompositeOperation = "source-over";
      frame = requestAnimationFrame(draw);
    };
    frame = requestAnimationFrame(draw);

    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", resize);
    };
  }, []);

  return (
    <Box
      component="canvas"
      ref={canvasRef}
      aria-hidden
      sx={{
        width: "100%",
        height,
        display: "block",
        maskImage: "linear-gradient(90deg, transparent, #000 18%, #000 82%, transparent)",
        WebkitMaskImage: "linear-gradient(90deg, transparent, #000 18%, #000 82%, transparent)",
      }}
    />
  );
}
