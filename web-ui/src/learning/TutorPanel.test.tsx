import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./tutorApi', () => ({ requestTutorExplanation: vi.fn() }));

import { TutorPanel } from './TutorPanel';
import * as tutorApi from './tutorApi';
import type { TutorOutcome } from './tutorApi';

/**
 * The tutor panel exists to make M1-ADR-004's consequence tolerable: the tutor does not stream, so
 * a learner may wait several seconds with nothing to read. What must hold during that wait is a
 * visible pending state, a support code available from the start, and a cancel that abandons the
 * request rather than hiding it.
 *
 * The degraded cases matter as much as the happy one. A learner whose tutor is unavailable must be
 * told plainly, given something to quote, and left in no doubt that the rest of their learning is
 * unaffected.
 */

const SUPPORT_CODE = '01920000-0000-7000-8000-0000000000a1';
const requestTutorExplanation = vi.mocked(tutorApi.requestTutorExplanation);

beforeEach(() => {
  requestTutorExplanation.mockReset();
});

/** The support code is returned synchronously, mirroring the real client. */
function respondWith(outcome: TutorOutcome) {
  requestTutorExplanation.mockReturnValue({
    supportCode: SUPPORT_CODE,
    result: Promise.resolve(outcome),
  });
}

/** A request that never settles, so the pending state can be observed rather than raced. */
function respondNever() {
  requestTutorExplanation.mockImplementation((_skill, _status, signal) => ({
    supportCode: SUPPORT_CODE,
    result: new Promise<TutorOutcome>((_resolve, reject) => {
      signal.addEventListener('abort', () => {
        const aborted = new Error('aborted');
        aborted.name = 'AbortError';
        reject(aborted);
      });
    }),
  }));
}

function proposed(explanation: string, checks: string[] = []): TutorOutcome {
  return { kind: 'proposed', explanation, checksForUnderstanding: checks, supportCode: SUPPORT_CODE };
}

function unavailable(reason: string): TutorOutcome {
  return { kind: 'unavailable', reason, supportCode: SUPPORT_CODE };
}

describe('TutorPanel', () => {
  it('shows the support code while the request is still pending', async () => {
    respondNever();
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));

    // The identifier a learner needs to report a problem exists before the problem does. Producing
    // it only on failure would leave the slowest, most frustrating waits with nothing to quote.
    expect(await screen.findByText(/preparing an explanation/i)).toBeInTheDocument();
    expect(screen.getByText(/reference:/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });

  it('announces the pending state politely rather than assertively', async () => {
    respondNever();
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));

    // Assertive would interrupt a screen reader mid-sentence on every render; polite announces once
    // when it settles, which is the correct urgency for "please wait".
    const status = await screen.findByRole('status');
    expect(status).toHaveAttribute('aria-live', 'polite');
  });

  it('cancelling abandons the request and says nothing changed', async () => {
    respondNever();
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));
    fireEvent.click(await screen.findByRole('button', { name: /cancel/i }));

    expect(await screen.findByText(/cancelled\. nothing was changed/i)).toBeInTheDocument();
    // Offered again rather than left in a dead end: cancelling is a change of mind, not a failure.
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
  });

  it('renders an explanation with its checks for understanding', async () => {
    respondWith(
      proposed('A partition is an ordered, append-only log.', [
        'What happens to ordering across two partitions?',
      ]),
    );
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));

    expect(await screen.findByText(/ordered, append-only log/i)).toBeInTheDocument();
    expect(screen.getByText(/ordering across two partitions/i)).toBeInTheDocument();
  });

  it('always states that an explanation changes nothing', async () => {
    respondWith(proposed('A partition is an ordered, append-only log.'));
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));

    // Shown on every explanation, not only when something looks wrong. A learner should never have
    // to guess whether a tutor changed their record; the answer is always no.
    expect(
      await screen.findByText(/does not change your mastery or progress/i),
    ).toBeInTheDocument();
  });

  it.each([
    ['AI_NOT_CONFIGURED', /not enabled here/i],
    ['AI_CIRCUIT_OPEN', /temporarily unavailable/i],
    ['AI_BULKHEAD_FULL', /temporarily unavailable/i],
    ['AI_TRANSPORT_FAILURE', /temporarily unavailable/i],
    ['AI_DEADLINE_EXCEEDED', /took longer than expected/i],
    ['UNKNOWN_SKILL', /not part of your curriculum/i],
  ])('explains %s in plain language', async (reason, expected) => {
    respondWith(unavailable(reason));
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(expected);
  });

  it('never shows a learner a circuit, bulkhead or deadline', async () => {
    respondWith(unavailable('AI_BULKHEAD_FULL'));
    const { container } = render(
      <TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />,
    );

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));
    await screen.findByRole('alert');

    // All true, none of it theirs to act on. The distinction that matters to an operator lives in
    // the metrics; the learner gets the actionable half.
    for (const jargon of ['bulkhead', 'circuit', 'deadline', 'BULKHEAD_FULL']) {
      expect(container.textContent?.toLowerCase()).not.toContain(jargon.toLowerCase());
    }
  });

  it('shows a support code on failure so the learner can report it', async () => {
    respondWith(unavailable('AI_TRANSPORT_FAILURE'));
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));
    await screen.findByRole('alert');

    // The code is rendered inside a <code> element next to a label, so match the element that
    // carries it rather than the sentence around it.
    expect(screen.getByText(SUPPORT_CODE)).toBeInTheDocument();
  });

  it('treats a rejected request as an outcome rather than a crash', async () => {
    requestTutorExplanation.mockReturnValue({
      supportCode: SUPPORT_CODE,
      result: Promise.reject(new Error('network down')),
    });
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    fireEvent.click(screen.getByRole('button', { name: /explain/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
  });

  it('uses a labelled region and a heading', async () => {
    render(<TutorPanel skillCode="KAFKA_PARTITIONING" masteryStatus="NEEDS_PRACTICE" />);

    const region = screen.getByRole('region', { name: /explanation/i });
    expect(region).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /explanation/i })).toBeInTheDocument();
  });
});
