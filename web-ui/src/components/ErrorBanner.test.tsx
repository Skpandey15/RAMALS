import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RamalsApiError } from '../platform/apiClient';
import { ErrorBanner } from './ErrorBanner';

function apiError(message: string, supportCode: string) {
  return new RamalsApiError('ATTEMPT_CONFLICT', supportCode, 409, message);
}

describe('ErrorBanner', () => {
  it('announces itself to assistive technology', () => {
    render(<ErrorBanner error={apiError('Attempt already submitted', 'int-1')} />);

    // role="alert" rather than plain text: a learner using a screen reader must be told the request
    // failed without having to go looking for the message.
    expect(screen.getByRole('alert')).toHaveTextContent('Attempt already submitted');
  });

  it('shows the support code so a learner has something to quote', () => {
    render(<ErrorBanner error={apiError('Scoring is unavailable', 'int-42')} />);

    expect(screen.getByText('int-42')).toBeInTheDocument();
  });

  it('falls back to a generic message for a non-API error', () => {
    // The banner is typed `unknown`, so it must survive anything a catch block can hand it.
    render(<ErrorBanner error={new TypeError('fetch failed')} />);

    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong. Please try again.');
    // A raw TypeError message is an implementation detail and must not reach the learner.
    expect(screen.queryByText(/fetch failed/)).not.toBeInTheDocument();
  });

  it('renders no support code when there is none to show', () => {
    render(<ErrorBanner error={'a thrown string'} />);

    expect(screen.getByRole('alert')).toBeInTheDocument();
    // An empty "Support code:" label would send a learner to support with nothing to quote.
    expect(screen.queryByText(/Support code/)).not.toBeInTheDocument();
  });
});
