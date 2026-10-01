import { useMemo, useRef } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { Canvas, useFrame } from '@react-three/fiber';
import { Float } from '@react-three/drei';
import * as THREE from 'three';
import { SHAPES } from '@stage/shared';
import { SHAPE_POINTS } from '../game/shapes';

/** One silhouette from the game, extruded into a thin 3D cut-out. */
function Silhouette({ shape, position, color }: { shape: (typeof SHAPES)[number]; position: [number, number, number]; color: string }) {
  const geometry = useMemo(() => {
    const pts = SHAPE_POINTS[shape];
    const s = new THREE.Shape(pts.map(([x, y]) => new THREE.Vector2(x, y)));
    return new THREE.ExtrudeGeometry(s, { depth: 0.12, bevelEnabled: false });
  }, [shape]);
  return (
    <Float speed={1.4} rotationIntensity={0.6} floatIntensity={0.8}>
      <mesh geometry={geometry} position={position} scale={0.45} castShadow>
        <meshStandardMaterial color={color} roughness={0.7} />
      </mesh>
    </Float>
  );
}

/** The game's pendulum lamp, swinging and casting real shadows on the wall. */
function Lamp() {
  const pivot = useRef<THREE.Group>(null);
  useFrame(({ clock }) => {
    if (pivot.current) pivot.current.rotation.z = 0.55 * Math.sin(clock.elapsedTime * 1.6);
  });
  return (
    <group ref={pivot} position={[0, 3.2, 1.6]}>
      <mesh position={[0, -0.7, 0]}>
        <cylinderGeometry args={[0.015, 0.015, 1.4]} />
        <meshStandardMaterial color="#222" />
      </mesh>
      <mesh position={[0, -1.45, 0]}>
        <sphereGeometry args={[0.16, 24, 24]} />
        <meshStandardMaterial color="#ffc46b" emissive="#ffc46b" emissiveIntensity={2} />
      </mesh>
      <pointLight position={[0, -1.45, 0]} intensity={18} distance={12} color="#ffc46b" castShadow shadow-mapSize={[512, 512]} />
    </group>
  );
}

function Stage() {
  const picks = useMemo(() => [
    { shape: SHAPES[0], position: [-1.6, 0.6, 0.4] as [number, number, number], color: '#ff8c42' },
    { shape: SHAPES[1], position: [1.5, 1.1, 0.2] as [number, number, number], color: '#9b5de5' },
    { shape: SHAPES[4], position: [0.1, -0.2, 0.6] as [number, number, number], color: '#2ec4b6' },
    { shape: SHAPES[7], position: [-0.9, -1.2, 0.3] as [number, number, number], color: '#ffd700' },
  ], []);
  return (
    <>
      <ambientLight intensity={0.15} />
      <Lamp />
      <mesh position={[0, 0, -0.6]} receiveShadow>
        <planeGeometry args={[14, 9]} />
        <meshStandardMaterial color="#5a4632" roughness={1} />
      </mesh>
      {picks.map((p) => <Silhouette key={p.shape} {...p} />)}
    </>
  );
}

let root: Root | null = null;

/** Mounts the decorative 3D menu background into `el` (lazy-loaded chunk; only on the home screen). */
export function mountMenu3D(el: HTMLElement): void {
  if (root) return;
  root = createRoot(el);
  root.render(
    <Canvas shadows dpr={[1, 1.5]} camera={{ position: [0, 0, 6], fov: 50 }} gl={{ powerPreference: 'low-power', antialias: true }} frameloop="always">
      <Stage />
    </Canvas>,
  );
}

export function unmountMenu3D(): void {
  root?.unmount();
  root = null;
}
