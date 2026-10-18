import { RotateCw } from 'lucide-react';
import { useEffect } from 'react';
import { isRouteErrorResponse, Link, useRouteError } from 'react-router';
import { Wordmark } from './AppShell';
import { Button, Eyebrow } from './ui';

/** The page's code changed under it (a new deploy, a restarted dev server): its screens no longer load. */
const STALE = /dynamically imported module|Importing a module script failed|Outdated Optimize Dep|Unable to preload CSS/i;
/** one automatic reload at a time, so a page that keeps failing shows this screen instead of looping */
const RELOADED = 'rydex.reloadedAt';

function isStaleCode(error: unknown): boolean {
  return error instanceof Error && STALE.test(error.message);
}

function reloadedRecently(): boolean {
  try {
    return Date.now() - Number(sessionStorage.getItem(RELOADED) ?? 0) < 30_000;
  } catch {
    return true;
  }
}

function reload() {
  try {
    sessionStorage.setItem(RELOADED, String(Date.now()));
  } catch {
    // private mode: reload anyway
  }
  window.location.reload();
}

/**
 * What a screen shows when it fails to draw (instead of React Router's developer page): what
 * happened in a sentence, a reload, a way home, and the details for a bug report. An app updated
 * under an open tab reloads itself once.
 */
export function RouteError() {
  const error = useRouteError();
  const stale = isStaleCode(error);
  const autoReload = stale && !reloadedRecently();
  useEffect(() => {
    if (autoReload) reload();
  }, [autoReload]);

  const notFound = isRouteErrorResponse(error) && error.status === 404;
  const detail = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : error instanceof Error ? error.message : String(error);

  return (
    <main className="grid min-h-dvh place-items-center bg-chalk p-4">
      <section role="alert" className="w-full max-w-md space-y-4 rounded-[var(--radius-sheet)] bg-paper p-6 shadow-[var(--shadow-float)]">
        <Wordmark />
        <div className="space-y-1">
          <Eyebrow>{notFound ? 'Not found' : stale ? 'Rydex was updated' : 'Something went wrong'}</Eyebrow>
          <h1 className="font-display-wide text-2xl font-black">
            {notFound ? 'There is nothing here.' : autoReload ? 'Loading the new version…' : stale ? 'This tab needs a reload.' : 'This screen hit a snag.'}
          </h1>
          <p className="text-sm text-slate">
            {notFound
              ? 'The link may be old.'
              : stale
                ? 'The app changed while this tab was open. A reload picks up the new version.'
                : 'Your rides and payments are safe: they live on the server. Reloading usually fixes this.'}
          </p>
        </div>
        <div className="flex gap-2">
          <Button onClick={reload} className="flex-1">
            <RotateCw className="size-4" aria-hidden /> Reload
          </Button>
          <Link
            to="/"
            reloadDocument
            className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl bg-paper px-5 font-display-wide text-[0.95rem] font-bold text-ink ring-1 ring-mist hover:ring-ink/40"
          >
            Home
          </Link>
        </div>
        {!notFound && (
          <details className="text-xs text-slate">
            <summary className="cursor-pointer font-bold">Details</summary>
            <p className="mt-2 font-mono break-words">{detail}</p>
          </details>
        )}
      </section>
    </main>
  );
}
