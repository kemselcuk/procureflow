import { FormControl } from '@angular/forms';
import { formatMoneyInput, moneyValidator, parseMoneyInput } from './request-validation';

describe('Request amount validation', () => {
  it.each([
    ['1', '1.00', 1],
    [' 12.5 ', '12.50', 12.5],
    ['0.01', '0.01', 0.01],
    ['00012.30', '12.30', 12.3],
    ['999999999999.99', '999999999999.99', 999999999999.99],
  ])('normalizes %s to %s without rounding', (raw, text, value) => {
    expect(parseMoneyInput(raw)).toEqual({ text, value });
  });

  it.each([
    [''],
    ['0'],
    ['0.00'],
    ['-1'],
    ['+1'],
    ['1,00'],
    ['1.001'],
    ['.50'],
    ['1.'],
    ['1e3'],
    ['1000000000000'],
    ['9999999999999.99'],
  ])('rejects %s', (raw) => {
    expect(parseMoneyInput(raw)).toBeNull();
  });

  it('feeds the same exactness rules through the reactive validator', () => {
    const control = new FormControl('1.001');
    expect(moneyValidator()(control)).toEqual({ money: true });
    expect(moneyValidator()(new FormControl('12.50'))).toBeNull();
  });

  it('formats a supported number with two fraction digits for editing', () => {
    expect(formatMoneyInput(12.5)).toBe('12.50');
    expect(formatMoneyInput(0.01)).toBe('0.01');
    expect(formatMoneyInput(999999999999.99)).toBe('999999999999.99');
  });
});
