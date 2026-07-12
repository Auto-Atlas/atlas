import { describe, it, expect } from 'vitest';
import { toolVisual, prettifyToolName } from './toolVisuals';

describe('toolVisual', () => {
  it('maps known Atlas tools to a friendly running phrase + icon', () => {
    const email = toolVisual('check_email');
    expect(email.title).toBe('Email');
    expect(email.running.toLowerCase()).toContain('email');
    expect(email.Icon).toBeTruthy();

    expect(toolVisual('create_invoice').running.toLowerCase()).toContain('invoice');
    expect(toolVisual('get_weather').running.toLowerCase()).toContain('weather');
    expect(toolVisual('jarvis_agent').running.toLowerCase()).toContain('agent');
  });

  it('maps the vision tools (phone camera + surfaced visuals)', () => {
    expect(toolVisual('look_via_phone').running.toLowerCase()).toContain('camera');
    expect(toolVisual('surface_visual').running.toLowerCase()).toContain('screen');
  });

  it('is case-insensitive on the tool id', () => {
    expect(toolVisual('CHECK_EMAIL').title).toBe('Email');
  });

  it('falls back to a prettified name + wrench-style default for unknown tools', () => {
    const v = toolVisual('frobnicate_widget');
    expect(v.title).toBe('Frobnicate Widget');
    expect(v.running).toBe('Running Frobnicate Widget…');
    expect(v.Icon).toBeTruthy();
  });

  it('handles empty/garbage tool ids without throwing', () => {
    expect(() => toolVisual('')).not.toThrow();
    expect(toolVisual('').Icon).toBeTruthy();
  });
});

describe('prettifyToolName', () => {
  it('title-cases underscored ids', () => {
    expect(prettifyToolName('get_calendar')).toBe('Get Calendar');
    expect(prettifyToolName('open_on_pc')).toBe('Open On Pc');
  });
});
