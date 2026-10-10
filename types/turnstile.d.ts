// The slice of Cloudflare Turnstile's explicit-render API that app.js calls.
interface Turnstile {
  render(container: string | HTMLElement, params: Record<string, unknown>): string | undefined;
  reset(widgetId?: string): void;
}

interface Window {
  turnstile: Turnstile;
  onTurnstileLoad: () => void;
}
