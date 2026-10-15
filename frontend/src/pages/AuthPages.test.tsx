import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '../test/fixtures';
import { LoginPage, RegisterPage } from './AuthPages';

vi.mock('../api/endpoints', () => ({ authApi: { login: vi.fn(), register: vi.fn() } }));

describe('sign-in and sign-up', () => {
  it('shows and hides the password', async () => {
    renderWithProviders(<LoginPage />);
    const user = userEvent.setup();
    const password = screen.getByLabelText('Password');
    await user.type(password, 'Secret123');

    expect(password).toHaveAttribute('type', 'password');
    await user.click(screen.getByRole('button', { name: 'Show password' }));
    expect(password).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(password).toHaveAttribute('type', 'password');
  });

  it('ticks off the password rules as they are met', async () => {
    renderWithProviders(<RegisterPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Email'), 'asha@example.com');
    const rules = () => screen.getByRole('list', { name: 'Password rules' });

    await user.type(screen.getByLabelText('Password'), 'asha');
    expect(rules()).toHaveTextContent('Not your email address');
    expect(screen.getByText('Not your email address')).not.toHaveClass('text-go');

    await user.clear(screen.getByLabelText('Password'));
    await user.type(screen.getByLabelText('Password'), 'Marina2026');
    for (const rule of ['At least 8 characters', 'A letter and a digit', 'Not your email address']) {
      expect(screen.getByText(rule).closest('li')).toHaveClass('text-go');
    }
  });
});
