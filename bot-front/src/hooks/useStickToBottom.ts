import { useEffect, useRef } from "react";

/**
 * Keeps a scrolling container pinned to its newest content: whenever the content grows (a new
 * message, a word typed into a reply), it scrolls to the bottom.
 */
export function useStickToBottom<T extends HTMLElement, C extends HTMLElement>() {
  const scrollRef = useRef<T | null>(null);
  const contentRef = useRef<C | null>(null);

  useEffect(() => {
    const scroller = scrollRef.current;
    const content = contentRef.current;
    if (!scroller || !content) return;
    const toBottom = () => scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
    const observer = new ResizeObserver(toBottom);
    observer.observe(content);
    toBottom();
    return () => observer.disconnect();
  }, []);

  return { scrollRef, contentRef };
}
