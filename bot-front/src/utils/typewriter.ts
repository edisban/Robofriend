/**
 * Reveals streamed text word by word at a natural, slightly uneven pace, so a reply that
 * arrives in a burst still reads like someone typing. Speeds up when far behind so long
 * answers don't drag.
 */
export function createTypewriter(onReveal: (shown: string) => void) {
  let target = "";
  let shown = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let finished = false;
  let resolveFinish: (() => void) | null = null;

  const nextWordEnd = (from: number) => {
    const match = /^\s*\S+\s*/.exec(target.slice(from));
    return from + (match ? match[0].length : target.length - from);
  };

  const tick = () => {
    if (shown >= target.length) {
      timer = null;
      if (finished) resolveFinish?.();
      return;
    }
    const backlog = target.length - shown;
    const words = backlog > 400 ? 4 : backlog > 150 ? 2 : 1;
    for (let i = 0; i < words && shown < target.length; i++) {
      shown = nextWordEnd(shown);
    }
    onReveal(target.slice(0, shown));
    timer = setTimeout(tick, 30 + Math.random() * 35);
  };

  return {
    push(text: string) {
      target += text;
      if (timer === null) tick();
    },
    /** Resolves once everything pushed so far is on screen. */
    finish(): Promise<void> {
      finished = true;
      return new Promise((resolve) => {
        if (timer === null && shown >= target.length) resolve();
        else resolveFinish = resolve;
      });
    },
    cancel() {
      if (timer !== null) clearTimeout(timer);
      timer = null;
      resolveFinish?.();
    },
  };
}
