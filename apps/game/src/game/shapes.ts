import type { ShapeId } from '@stage/shared';

type Pt = [number, number];

function star(): Pt[] {
  return Array.from({ length: 10 }, (_, i) => {
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    const r = i % 2 === 0 ? 1 : 0.45;
    return [r * Math.cos(a), -r * Math.sin(a)] as Pt;
  });
}

function moon(): Pt[] {
  const outer: Pt[] = [];
  const inner: Pt[] = [];
  for (let i = 0; i <= 16; i++) {
    const a = Math.PI * 0.3 + (i / 16) * Math.PI * 1.4;
    outer.push([Math.cos(a), Math.sin(a)]);
  }
  for (let i = 16; i >= 0; i--) {
    const a = Math.PI * 0.42 + (i / 16) * Math.PI * 1.16;
    inner.push([0.35 + 0.78 * Math.cos(a), 0.78 * Math.sin(a)]);
  }
  return [...outer, ...inner];
}

/** Silhouettes in a [-1, 1] box, y pointing up. Original simple shapes drawn for this game. */
export const SHAPE_POINTS: Record<ShapeId, Pt[]> = {
  cat: [[-0.5, -1], [0.5, -1], [0.6, -0.4], [0.45, 0.2], [0.55, 0.55], [0.45, 1], [0.25, 0.7], [-0.25, 0.7], [-0.45, 1], [-0.55, 0.55], [-0.45, 0.2], [-0.6, -0.4]],
  bird: [[-1, 0.1], [-0.4, 0.3], [-0.1, 0.9], [0.2, 0.35], [0.6, 0.4], [0.9, 0.6], [0.8, 0.25], [1, 0.1], [0.5, -0.1], [0.1, -0.35], [-0.4, -0.2]],
  rabbit: [[-0.5, -1], [0.5, -1], [0.6, -0.3], [0.35, 0.1], [0.35, 1], [0.15, 1], [0.1, 0.25], [-0.1, 0.25], [-0.15, 1], [-0.35, 1], [-0.35, 0.1], [-0.6, -0.3]],
  fish: [[-1, 0], [-0.6, 0.45], [0, 0.55], [0.5, 0.3], [1, 0.6], [0.85, 0], [1, -0.6], [0.5, -0.3], [0, -0.55], [-0.6, -0.45]],
  star: star(),
  moon: moon(),
  tree: [[-0.15, -1], [0.15, -1], [0.15, -0.6], [0.8, -0.6], [0.3, 0], [0.6, 0], [0.15, 0.55], [0.35, 0.55], [0, 1], [-0.35, 0.55], [-0.15, 0.55], [-0.6, 0], [-0.3, 0], [-0.8, -0.6], [-0.15, -0.6]],
  butterfly: [[0, -0.6], [-0.4, -1], [-1, -0.7], [-0.7, -0.1], [-1, 0.5], [-0.5, 1], [0, 0.4], [0.5, 1], [1, 0.5], [0.7, -0.1], [1, -0.7], [0.4, -1]],
};

export const SHAPE_NAMES_TR: Record<ShapeId, string> = {
  cat: 'Kedi', bird: 'Kuş', rabbit: 'Tavşan', fish: 'Balık', star: 'Yıldız', moon: 'Ay', tree: 'Ağaç', butterfly: 'Kelebek',
};

/** Shape points placed at (cx, cy) with half-size `r` in screen space (y down). */
export function placeShape(shape: ShapeId, cx: number, cy: number, r: number): { x: number; y: number }[] {
  return SHAPE_POINTS[shape].map(([x, y]) => ({ x: cx + x * r, y: cy - y * r }));
}
