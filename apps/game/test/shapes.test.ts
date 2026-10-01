import { describe, expect, it } from 'vitest';
import { SHAPES } from '@stage/shared';
import { SHAPE_POINTS, SHAPE_NAMES_TR, placeShape } from '../src/game/shapes';

describe('silhouettes', () => {
  it('every gameplay shape has a polygon and a Turkish name', () => {
    for (const s of SHAPES) {
      expect(SHAPE_POINTS[s].length).toBeGreaterThanOrEqual(8);
      expect(SHAPE_NAMES_TR[s]).toBeTruthy();
      for (const [x, y] of SHAPE_POINTS[s]) {
        expect(Math.abs(x)).toBeLessThanOrEqual(1.01);
        expect(Math.abs(y)).toBeLessThanOrEqual(1.01);
      }
    }
  });

  it('places and scales shapes in screen space (y down)', () => {
    const pts = placeShape('star', 100, 200, 10);
    expect(pts[0]!.x).toBeCloseTo(100);
    expect(pts[0]!.y).toBeCloseTo(190);
  });
});
