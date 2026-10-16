import type { CustomRenderMethodInput, MercatorCoordinate } from 'maplibre-gl';
import * as THREE from 'three';

/**
 * Points a three.js camera the way MapLibre's camera looks, for a custom layer whose scene is in
 * metres around `origin` (x east, y up, z south).
 *
 * MapLibre hands over one matrix (mercator to clip space). Used as the projection alone, three.js
 * would think the eye sits at the scene origin on the ground, and every highlight and reflection
 * would point the wrong way. So the eye is recovered from the matrix (the point it sends to w = 0,
 * which is the camera centre) and the matrix is split into a view (the eye's position) and a
 * projection, whose product is still exactly MapLibre's.
 */
export function syncCamera(camera: THREE.Camera, args: CustomRenderMethodInput, origin: MercatorCoordinate, unit: number) {
  const main = new THREE.Matrix4().fromArray(args.defaultProjectionData.mainMatrix as unknown as number[]);
  const local = new THREE.Matrix4()
    .makeTranslation(origin.x, origin.y, origin.z)
    .scale(new THREE.Vector3(unit, -unit, unit))
    .multiply(new THREE.Matrix4().makeRotationAxis(new THREE.Vector3(1, 0, 0), Math.PI / 2));
  const clip = main.multiply(local);
  const e = new THREE.Vector4(0, 0, 1, 0).applyMatrix4(clip.clone().invert());
  if (Math.abs(e.w) > 1e-12) camera.position.set(e.x / e.w, e.y / e.w, e.z / e.w);
  else camera.position.set(0, 0, 0);
  camera.updateMatrixWorld(true);
  camera.projectionMatrix.copy(clip).multiply(new THREE.Matrix4().makeTranslation(camera.position.x, camera.position.y, camera.position.z));
  camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
}

/** A light's direction (unit vector, towards the light) for a sky position: azimuth from north, elevation up. */
export function skyDirection(azimuth: number, elevation: number): THREE.Vector3 {
  const az = (azimuth * Math.PI) / 180;
  const el = (Math.max(4, elevation) * Math.PI) / 180;
  // x east, y up, z south
  return new THREE.Vector3(Math.sin(az) * Math.cos(el), Math.sin(el), -Math.cos(az) * Math.cos(el));
}
