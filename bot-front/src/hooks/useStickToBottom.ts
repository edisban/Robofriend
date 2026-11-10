import { useCallback, useEffect, useRef } from "react";

/** How close to the bottom (px) still counts as "at the bottom". */
const PINNED_THRESHOLD = 80;

/**
 * Keeps a scrolling container pinned to its newest content: when the content grows (a new
 * message, a word typed into a reply) and the user is at the bottom, it scrolls down. When they
 * have scrolled up to read, it leaves them where they are.
 *
 * Call `keepPositionDuringPrepend()` right before older messages are added above, so the
 * messages on screen stay put instead of jumping.
 */
export function useStickToBottom<T extends HTMLElement, C extends HTMLElement>() {
  const scrollRef = useRef<T | null>(null);
  const contentRef = useRef<C | null>(null);
  const pinned = useRef(true);
  /** Distance from the bottom to restore after a prepend, if one is pending. */
  const anchor = useRef<number | null>(null);

  useEffect(() => {
    const scroller = scrollRef.current;
    const content = contentRef.current;
    if (!scroller || !content) return;
    // We restore the position ourselves; the browser's own scroll anchoring would do it twice.
    scroller.style.overflowAnchor = "none";

    const onScroll = () => {
      pinned.current = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < PINNED_THRESHOLD;
    };
    const onResize = () => {
      if (anchor.current !== null) {
        scroller.scrollTop = scroller.scrollHeight - anchor.current;
        anchor.current = null;
      } else if (pinned.current) {
        scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
      }
    };

    scroller.addEventListener("scroll", onScroll, { passive: true });
    const observer = new ResizeObserver(onResize);
    observer.observe(content);
    scroller.scrollTo({ top: scroller.scrollHeight });
    return () => {
      scroller.removeEventListener("scroll", onScroll);
      observer.disconnect();
    };
  }, []);

  const keepPositionDuringPrepend = useCallback(() => {
    const scroller = scrollRef.current;
    if (scroller) anchor.current = scroller.scrollHeight - scroller.scrollTop;
  }, []);

  /** Jump back to the newest message and follow it again (e.g. after the user sends one). */
  const scrollToBottom = useCallback(() => {
    pinned.current = true;
    const scroller = scrollRef.current;
    scroller?.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
  }, []);

  return { scrollRef, contentRef, keepPositionDuringPrepend, scrollToBottom };
}
