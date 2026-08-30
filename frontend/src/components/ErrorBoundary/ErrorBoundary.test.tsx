/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { describe, expect, it, vi } from 'vitest';
import ErrorBoundary from './ErrorBoundary';

// This project has no jsdom/happy-dom or @testing-library dependency (see
// src/lib/resolveWindow.test.ts for the plain-vitest convention this repo
// uses), and adding one wasn't part of this task's scope. React's own class
// component contract, though, is designed to be testable without a renderer:
// `getDerivedStateFromError` is a pure static function, and `render()` is a
// pure method that returns a plain element tree — both can be exercised by
// instantiating the class directly, which is what these tests do instead of
// mounting into a DOM.
describe('ErrorBoundary', () => {
  it('passes children through unchanged when nothing has thrown', () => {
    const children = 'safe content';
    const instance = new ErrorBoundary({ children });
    expect(instance.render()).toBe(children);
  });

  it('getDerivedStateFromError captures the thrown error into state', () => {
    const error = new Error('boom');
    expect(ErrorBoundary.getDerivedStateFromError(error)).toEqual({ error });
  });

  it('renders the fallback instead of the crashed children once state.error is set', () => {
    const instance = new ErrorBoundary({ children: 'safe content' });
    instance.state = { error: new Error('boom') };

    const fallback = instance.render();

    expect(fallback).not.toBe('safe content');
    const serialized = JSON.stringify(fallback);
    expect(serialized).toContain('Something went wrong');
    expect(serialized).toContain('Reload page');
  });

  it('resets to the non-error state when resetKeys change while the fallback is showing', () => {
    const instance = new ErrorBoundary({ children: 'safe content', resetKeys: ['/logs'] });
    instance.state = { error: new Error('boom') };
    instance.setState = vi.fn();

    instance.componentDidUpdate({ children: 'safe content', resetKeys: ['/traces'] });

    expect(instance.setState).toHaveBeenCalledWith({ error: null });
  });

  it('does not reset when resetKeys are unchanged (e.g. no navigation happened)', () => {
    const instance = new ErrorBoundary({ children: 'safe content', resetKeys: ['/logs'] });
    instance.state = { error: new Error('boom') };
    instance.setState = vi.fn();

    instance.componentDidUpdate({ children: 'safe content', resetKeys: ['/logs'] });

    expect(instance.setState).not.toHaveBeenCalled();
  });
});
