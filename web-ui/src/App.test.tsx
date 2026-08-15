import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('App', () => {
  it('identifies the MVP-0 foundation', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'RAMALS' })).toBeInTheDocument();
    expect(screen.getByText(/deterministic adaptive learning/i)).toBeInTheDocument();
  });
});

