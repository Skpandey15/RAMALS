import { RamalsApiError } from '../platform/apiClient';

/**
 * Surfaces a failed request. The support code is the request's interactionId, which support can use
 * to find the correlated logs and decision records. It carries no token or business data.
 */
export function ErrorBanner({ error }: { error: unknown }) {
  const message =
    error instanceof RamalsApiError ? error.message : 'Something went wrong. Please try again.';
  const supportCode = error instanceof RamalsApiError ? error.supportCode : undefined;

  return (
    <div role="alert" className="error-banner">
      <p>{message}</p>
      {supportCode && (
        <p className="support-code">
          Support code: <code>{supportCode}</code>
        </p>
      )}
    </div>
  );
}
