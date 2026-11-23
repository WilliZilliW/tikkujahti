// The small part of three.js this game uses. Parameters that three.js takes as option objects are `dynamic`.
@file:JsModule("three")
@file:JsNonModule

package fi.otsosoft.tikkujahti.web

import org.w3c.dom.HTMLCanvasElement

external class Vector3(x: Double = definedExternally, y: Double = definedExternally, z: Double = definedExternally) {
    var x: Double
    var y: Double
    var z: Double
    fun set(x: Double, y: Double, z: Double): Vector3
    fun applyQuaternion(q: Quaternion): Vector3
    fun setFromMatrixPosition(m: Matrix4): Vector3
}

external class Quaternion {
    var x: Double
    var y: Double
    var z: Double
    var w: Double
    fun set(x: Double, y: Double, z: Double, w: Double): Quaternion
    fun setFromAxisAngle(axis: Vector3, angle: Double): Quaternion
    fun premultiply(q: Quaternion): Quaternion
    fun setFromRotationMatrix(m: Matrix4): Quaternion
}

external class Matrix4 {
    val elements: Array<Double>
    fun fromArray(a: dynamic): Matrix4
}

external class Color(hex: Int = definedExternally) {
    fun setRGB(r: Double, g: Double, b: Double, colorSpace: String = definedExternally): Color
}

external open class Object3D {
    val position: Vector3
    val scale: Vector3
    val quaternion: Quaternion
    val matrixWorld: Matrix4
    var visible: Boolean
    var castShadow: Boolean
    var receiveShadow: Boolean
    var frustumCulled: Boolean
    var renderOrder: Int
    fun add(o: Object3D): Object3D
    fun remove(o: Object3D): Object3D
    fun lookAt(x: Double, y: Double, z: Double)
    fun rotateX(a: Double): Object3D
    fun updateMatrixWorld(force: Boolean = definedExternally)
}

external class Scene : Object3D {
    var background: dynamic
}

external class Group : Object3D

external class PerspectiveCamera(fov: Double, aspect: Double, near: Double, far: Double) : Object3D {
    var aspect: Double
    var fov: Double
    val projectionMatrix: Matrix4
    fun updateProjectionMatrix()
}

external class WebGLRenderer(params: dynamic) {
    val domElement: HTMLCanvasElement
    val shadowMap: dynamic
    val xr: dynamic
    fun setSize(w: Double, h: Double, updateStyle: Boolean = definedExternally)
    fun setPixelRatio(r: Double)
    fun render(scene: Scene, camera: Object3D)
    fun setAnimationLoop(cb: ((Double, dynamic) -> Unit)?)
    fun setClearColor(color: Int, alpha: Double)
}

external class BufferAttribute(array: dynamic, itemSize: Int) {
    var needsUpdate: Boolean
    fun setUsage(u: Int): BufferAttribute
}

external open class BufferGeometry {
    fun setAttribute(name: String, attr: BufferAttribute): BufferGeometry
    fun setIndex(attr: BufferAttribute): BufferGeometry
    fun addGroup(start: Int, count: Int, materialIndex: Int)
    fun rotateX(a: Double): BufferGeometry
    fun dispose()
}

external class PlaneGeometry(w: Double, h: Double) : BufferGeometry
external class CircleGeometry(r: Double, segments: Int) : BufferGeometry
external class RingGeometry(inner: Double, outer: Double, segments: Int) : BufferGeometry
external class BoxGeometry(w: Double, h: Double, d: Double) : BufferGeometry
external class ShapeGeometry(shape: Shape) : BufferGeometry

external class Shape {
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
}

external open class Material {
    var visible: Boolean
    var opacity: Double
    var transparent: Boolean
    val color: Color
    fun dispose()
}

external class MeshStandardMaterial(params: dynamic = definedExternally) : Material
external class MeshBasicMaterial(params: dynamic = definedExternally) : Material
external class ShadowMaterial(params: dynamic = definedExternally) : Material

external class LineBasicMaterial(params: dynamic = definedExternally) : Material
external class LineLoop(geometry: BufferGeometry, material: Material) : Object3D {
    val geometry: BufferGeometry
}

external class Mesh(geometry: BufferGeometry, material: dynamic) : Object3D {
    val geometry: BufferGeometry
    val material: dynamic
}

external open class Light : Object3D {
    var intensity: Double
    val color: Color
}

external class HemisphereLight(sky: Int, ground: Int, intensity: Double) : Light
external class DirectionalLight(color: Int, intensity: Double) : Light {
    val shadow: dynamic
    val target: Object3D
}

external class CanvasTexture(canvas: HTMLCanvasElement) {
    var wrapS: Int
    var wrapT: Int
    var colorSpace: String
    var anisotropy: Int
    val repeat: dynamic
}

external val RepeatWrapping: Int
external val DynamicDrawUsage: Int
external val DoubleSide: Int

external class ShaderMaterial(params: dynamic = definedExternally) : Material {
    val uniforms: dynamic
}

external class DataTexture(data: dynamic, width: Int, height: Int, format: Int = definedExternally, type: Int = definedExternally) {
    var needsUpdate: Boolean
    var magFilter: Int
    var minFilter: Int
    fun dispose()
}

external val RedFormat: Int
external val FloatType: Int
external val NearestFilter: Int
external val AlwaysDepth: Int
external val GLSL3: String
