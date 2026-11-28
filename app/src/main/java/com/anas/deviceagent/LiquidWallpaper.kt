package com.anas.deviceagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val LIQ_FRAG = """
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uBass;
uniform float uBeat;
uniform float uHigh;
uniform float uPanX;
uniform float uPanY;
uniform vec2 uDir;
uniform vec4 uPull;
uniform float uRim;
uniform sampler2D uTex;
uniform float uFade;
uniform float uOffY;
uniform float uStretch;

float h3(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }

float p0r;
float map(vec3 p0) {
    p0r = 0.0;
    float t = uTime;
    vec3 p = p0;
    float sx = mix(uPull.x, uPull.y, smoothstep(-0.12, 0.12, p.x));
    float sy = mix(uPull.z, uPull.w, smoothstep(-0.12, 0.12, p.y));
    p.y *= 1.0 + 0.12 * sx;
    p.x *= 1.0 + 0.12 * sy;
    if (uRim > 0.5) {
        float ax = abs(p0.x); float ay = abs(p0.y);
        float w = 1.0 - abs(ax - ay) / (ax + ay + 0.001);
        w *= w;
        p0r = 0.12 * (sx * sy + 0.15 * (sx + sy)) * w;
    }
    p.x /= 1.0 + 0.9 * sx;
    p.y /= 1.0 + 0.9 * sy;
    float d = length(p) - (0.44 + 0.035 * uBass) + p0r;
    vec3 q0 = p0;
    float wob = sin(q0.x * 3.0 + t * 0.35) * sin(q0.y * 3.2 - t * 0.3) * sin(q0.z * 2.8 + t * 0.25);
    float fine = 0.0;
    float rip = sin(length(q0 - vec3(0.17, 0.2, 0.5)) * 38.0 - t * 3.0) * 0.0015
              + sin(length(q0 - vec3(-0.2, -0.14, 0.5)) * 30.0 + 1.0 - t * 2.0) * 0.0012;
    d += (0.004 + 0.004 * uBass) * wob + 0.010 * uHigh * fine
       + rip * (1.0 + 1.3 * uBeat) * smoothstep(0.8, 0.35, length(q0));
    return d * 0.8;
}

vec3 envc(vec3 d) {
    float up = smoothstep(-0.2, 1.0, d.y);
    vec3 c = vec3(0.012) + vec3(0.03) * up;
    c += vec3(1.0) * smoothstep(0.82, 0.96, dot(d, normalize(vec3(-0.6, 0.7, 0.5)))) * 2.4;
    c += vec3(0.75, 0.77, 0.8) * smoothstep(0.9, 0.985, dot(d, normalize(vec3(0.8, 0.2, 0.4)))) * 1.8;
    c += vec3(0.5) * smoothstep(0.93, 0.99, dot(d, normalize(vec3(0.0, -0.8, 0.6)))) * 0.5;
    float strip = smoothstep(0.025, 0.0, abs(d.y - 0.35 + 0.15 * d.x)) * smoothstep(-0.2, 0.6, d.x) * 1.4;
    c += vec3(0.85, 0.87, 0.9) * strip;
    return c;
}

vec3 background(vec2 uv, float vg) {
    float t = uTime;
    vec3 bg = vec3(0.018, 0.019, 0.022) + vec3(0.05) * exp(-vg * vg * 6.0) - vec3(0.03) * smoothstep(0.2, 0.9, vg);
    // one soft light beam
    float ang = uv.x + (uv.y - 0.6) * 0.1;
    bg += vec3(0.05, 0.052, 0.056) * exp(-pow((ang + 0.02) / 0.09, 2.0)) * smoothstep(-0.45, 0.55, uv.y) * 0.9;
    // halo rings that breathe with the bass
    float rr = length(uv - vec2(uPanX * 0.2, 0.02 + uPanY * 0.2));
    for (int k = 0; k < 4; k++) {
        float fk = float(k);
        float rad = 0.22 + 0.05 * fk * fk * 0.5 + fk * 0.06 + uBass * 0.01 * (fk + 1.0);
        bg += vec3(0.5, 0.52, 0.56) * exp(-pow((rr - rad) / 0.0016, 2.0)) * (0.11 / (1.0 + fk * 0.55)) * (1.0 + 0.8 * uBeat);
    }
    return bg;
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uRes - vec2(0.0, uOffY)) / uRes.y;
    float vg = length(uv * vec2(1.0, 0.7));
    vec3 ro = vec3(0.0, 0.0, 3.0);
    vec3 rd = normalize(vec3(uv, -0.62));
    vec3 ctr = vec3(uPanX, uPanY, 0.0);
    float R = 0.44 + 0.035 * uBass + 0.08 + 0.55 * abs(uStretch);
    vec3 oc = ro - ctr;
    float bb = dot(oc, rd);
    float disc = bb * bb - (dot(oc, oc) - R * R);
    float cov = 0.0;
    vec3 c = vec3(0.0);
    if (disc > 0.0) {
        float sq = sqrt(disc);
        float tt = max(0.5, -bb - sq);
        float tmax = -bb + sq;
        float dmin = 1e9; float tmin = tt;
        bool hit = false;
        for (int i = 0; i < 32; i++) {
            float h = map(ro + rd * tt - ctr);
            if (h < dmin) { dmin = h; tmin = tt; }
            if (h < 0.002) { hit = true; break; }
            tt += h;
            if (tt > tmax) break;
        }
        float px = tmin * 1.2 / uRes.y;
        cov = hit ? 1.0 : (1.0 - smoothstep(0.0, px * 1.3, dmin));
        if (cov > 0.001) {
            vec3 p = ro + rd * tmin - ctr;
            vec2 k = vec2(1.0, -1.0);
            float e = 0.003;
            vec3 n = normalize(k.xyy * map(p + k.xyy * e) + k.yyx * map(p + k.yyx * e) + k.yxy * map(p + k.yxy * e) + k.xxx * map(p + k.xxx * e));
            float fr = pow(1.0 - max(dot(n, -rd), 0.0), 3.5);
            vec3 refl = envc(reflect(rd, n));
            vec3 ri = refract(rd, n, 0.7);
            float th = max(-map(p - n * 0.02 + ri * 0.12), 0.0) + max(-map(p - n * 0.02 + ri * 0.24), 0.0);
            vec3 body = vec3(0.012, 0.013, 0.016) + vec3(0.05, 0.053, 0.06) * exp(-th * 4.0) * 0.8;
            c = body + refl * (0.10 + 0.9 * fr) * (1.0 + 0.5 * uBeat) + refl * 0.35;
            c += vec3(0.5, 0.52, 0.56) * fr * fr * 0.25;
        }
    }
    vec3 col = c;
    if (cov < 0.999) col = mix(background(uv, vg), c, cov);
    col *= 1.0 - 0.5 * smoothstep(0.3, 1.0, vg);
    vec2 tc = vec2(uv.x / 0.21 + 0.5, (uv.y - 0.455) / 0.07 + 0.5);
    if (tc.x > 0.0 && tc.x < 1.0 && tc.y > 0.0 && tc.y < 1.0) {
        float m = texture2D(uTex, tc).r;
        vec2 e = vec2(1.6 / 576.0, 1.6 / 192.0);
        float gx = texture2D(uTex, tc + vec2(e.x, 0.0)).r - texture2D(uTex, tc - vec2(e.x, 0.0)).r;
        float gy = texture2D(uTex, tc + vec2(0.0, e.y)).r - texture2D(uTex, tc - vec2(0.0, e.y)).r;
        float edge = clamp(length(vec2(gx, gy)) * 3.2, 0.0, 1.0);
        float inside = smoothstep(0.3, 0.6, m);
        col += vec3(0.5, 0.52, 0.56) * m * 0.03;
        vec3 glass = vec3(0.012, 0.014, 0.018) + vec3(0.03, 0.034, 0.04) * (1.0 - tc.y) * inside;
        glass += vec3(0.55, 0.58, 0.63) * edge * (0.45 + 0.5 * clamp(-gy * 4.0 + 0.3, 0.0, 1.0));
        col = mix(col, glass, inside);
    }
    gl_FragColor = vec4(clamp(col, 0.0, 1.0) * uFade, 1.0);
}
"""

/** Minimal EGL/GLES2 full-screen shader renderer for the glass ring scene. */
class GlLiquid {
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prog = 0
    private var tex = 0
    private val loc = HashMap<String, Int>()
    var error = ""
    var offY = 0f
    private var oProg = 0
    private var oTex = 0
    private var hasOverlay = false
    private var oFrac = 0f
    private var oq: java.nio.FloatBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun setOverlay(bmp: Bitmap?, frac: Float) {
        if (bmp == null) { hasOverlay = false; return }
        if (oProg == 0) {
            val vs = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 t; varying vec2 v; void main(){ v = t; gl_Position = vec4(p, 0.0, 1.0); }")
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 v; uniform sampler2D s; void main(){ gl_FragColor = texture2D(s, v); }")
            if (vs == 0 || fs == 0) return
            oProg = GLES20.glCreateProgram()
            GLES20.glAttachShader(oProg, vs); GLES20.glAttachShader(oProg, fs)
            GLES20.glBindAttribLocation(oProg, 0, "p"); GLES20.glBindAttribLocation(oProg, 1, "t")
            GLES20.glLinkProgram(oProg)
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); oTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oTex)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        oFrac = frac
        hasOverlay = true
    }

    private fun drawOverlay() {
        if (!hasOverlay || oProg == 0) return
        val top = -1f + 2f * oFrac
        oq.position(0)
        oq.put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, top, 0f, 0f, 1f, top, 1f, 0f)); oq.position(0)
        GLES20.glUseProgram(oProg)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oProg, "s"), 0)
        GLES20.glEnableVertexAttribArray(0); GLES20.glEnableVertexAttribArray(1)
        oq.position(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, oq)
        oq.position(2); GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, oq)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    var w = 0; var h = 0
    var rim = 1f

    fun init(surface: Surface): Boolean {
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) { error = "eglInitialize"; return false }
            val attr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) || n[0] < 1) { error = "eglChooseConfig"; return false }
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) { error = "eglCreateWindowSurface " + EGL14.eglGetError(); return false }
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { error = "eglMakeCurrent"; return false }
            EGL14.eglSwapInterval(dpy, 1)
            val vs = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }")
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, LIQ_FRAG)
            if (vs == 0 || fs == 0) return false
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs)
            GLES20.glBindAttribLocation(prog, 0, "p")
            GLES20.glLinkProgram(prog)
            val st = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, st, 0)
            if (st[0] == 0) { error = "link: " + GLES20.glGetProgramInfoLog(prog); return false }
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, TW, TH, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(TW * TH))
            setTitle(titleMap("Anas"))
            return true
        } catch (e: Throwable) { error = "init: " + e.message; return false }
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val st = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) { error = "shader: " + GLES20.glGetShaderInfoLog(s); return 0 }
        return s
    }

    private fun u(name: String): Int = loc.getOrPut(name) { GLES20.glGetUniformLocation(prog, name) }

    fun setTitle(pixels: ByteArray) {
        val bb = ByteBuffer.allocateDirect(pixels.size); bb.put(pixels); bb.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, TW, TH, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb)
    }

    fun black() {
        val a = IntArray(1)
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, a, 0); w = a[0]
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, a, 0); h = a[0]
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawOverlay()
        EGL14.eglSwapBuffers(dpy, surf)
    }

    fun draw(bass: Float, beat: Float, high: Float, time: Float, panX: Float, stretch: Float, fade: Float, panY: Float = 0f, dx: Float = 1f, dy: Float = 0f, pl: Float = 0f, pr: Float = 0f, pd: Float = 0f, pu: Float = 0f) {
        val a = IntArray(1)
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, a, 0); w = a[0]
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, a, 0); h = a[0]
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        GLES20.glUniform2f(u("uRes"), w.toFloat(), h.toFloat())
        GLES20.glUniform1f(u("uTime"), time); GLES20.glUniform1f(u("uBass"), bass); GLES20.glUniform1f(u("uBeat"), beat)
        GLES20.glUniform1f(u("uHigh"), high); GLES20.glUniform1f(u("uPanX"), panX); GLES20.glUniform1f(u("uStretch"), stretch); GLES20.glUniform1f(u("uPanY"), panY); GLES20.glUniform2f(u("uDir"), dx, dy); GLES20.glUniform4f(u("uPull"), pl, pr, pd, pu)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex); GLES20.glUniform1i(u("uTex"), 0); GLES20.glUniform1f(u("uRim"), rim)
        GLES20.glUniform1f(u("uFade"), fade); GLES20.glUniform1f(u("uOffY"), offY)
        GLES20.glEnableVertexAttribArray(0)
        quad.position(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        drawOverlay()
        EGL14.eglSwapBuffers(dpy, surf)
    }

    fun release() {
        try {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        } catch (e: Throwable) {}
        dpy = EGL14.EGL_NO_DISPLAY; ctx = EGL14.EGL_NO_CONTEXT; surf = EGL14.EGL_NO_SURFACE
    }

    companion object {
        const val TW = 576
        const val TH = 192

        private fun blur(src: FloatArray, w: Int, h: Int, passes: Int): FloatArray {
            var a = src; var b = FloatArray(src.size)
            for (p in 0 until passes) {
                for (y in 0 until h) for (x in 0 until w) {
                    val l = a[y * w + Math.max(0, x - 1)]; val c = a[y * w + x]; val r = a[y * w + Math.min(w - 1, x + 1)]
                    b[y * w + x] = (l + c + r) / 3f
                }
                val t = a; a = b; b = t
                for (y in 0 until h) for (x in 0 until w) {
                    val u = a[Math.max(0, y - 1) * w + x]; val c = a[y * w + x]; val d = a[Math.min(h - 1, y + 1) * w + x]
                    b[y * w + x] = (u + c + d) / 3f
                }
                val t2 = a; a = b; b = t2
            }
            return a
        }

        /** Render the title (any script, RTL aware) and turn it into a soft height map for the liquid-glass look. Rows are bottom-first for GL. */
        fun titleMap(text: String): ByteArray {
            val bmp = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val tp = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            tp.typeface = Typeface.DEFAULT_BOLD; tp.color = Color.WHITE
            var lay: StaticLayout? = null
            var size = 84
            while (size >= 28) {
                tp.textSize = size.toFloat()
                val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, TW - 28).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                lay = l
                if (l.lineCount <= 2 && l.height <= TH - 20) break
                size -= 4
            }
            val l = lay!!
            cv.translate(14f, (TH - l.height) / 2f)
            l.draw(cv)
            val px = IntArray(TW * TH); bmp.getPixels(px, 0, TW, 0, 0, TW, TH)
            val m = FloatArray(TW * TH) { Color.alpha(px[it]) / 255f }
            val m1 = blur(m, TW, TH, 3)
            val g = FloatArray(m1.size) { val x = ((m1[it] - 0.28f) / 0.30f).coerceIn(0f, 1f); x * x * (3f - 2f * x) }
            val hh = blur(g, TW, TH, 3)
            val out = ByteArray(TW * TH)
            for (y in 0 until TH) for (x in 0 until TW) out[(TH - 1 - y) * TW + x] = (hh[y * TW + x].coerceIn(0f, 1f) * 255f).toInt().toByte()
            return out
        }
    }
}

class LiquidWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = LE()

    inner class LE : Engine() {
        private val h = Handler(Looper.getMainLooper())
        private var visible = false
        private var gl: GlLiquid? = null
        private var failed = false
        private var viz: Visualizer? = null
        private var vizFailed = false
        private var vizStart = 0L
        private var lastData = 0L
        private val bands = FloatArray(32)
        private var lastMeta = 0L
        private var lastNow = 0L
        private var bass = 0f; private var mid = 0f; private var high = 0f; private var bassAvg = 0f
        private var punch = 0f; private var punchV = 0f
        private var rT = 0f; private var rScale = 1f; private var rLoud = 0f; private var rSpin = 0f; private var rPulse = 0f
        private var form = 0f; private var shown = ""; private var want = ""
        private var blackIdle = true
        private var rimOn = true
        private var simT = 0f
        private var liqT = 0f; private var lpos = 0f; private var lposY = 0f; private var lvelY = 0f; private var ldx = 1f; private var ldy = 0f; private var lvel = 0f; private var lstretch = 0f; private var lbass = 0f
        private val tick = Runnable { step() }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            h.removeCallbacks(tick)
            if (v) {
                blackIdle = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_black_idle", true)
                rimOn = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_liquid_rim", true)
                failed = false
                h.post(tick)
            } else { stopViz(); releaseGl() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            releaseGl()
            if (visible) { h.removeCallbacks(tick); h.post(tick) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) { h.removeCallbacks(tick); releaseGl(); super.onSurfaceDestroyed(holder) }

        override fun onDestroy() { visible = false; h.removeCallbacks(tick); stopViz(); releaseGl(); super.onDestroy() }

        private fun releaseGl() { try { gl?.release() } catch (e: Throwable) {}; gl = null }

        private fun stopViz() { try { viz?.enabled = false; viz?.release() } catch (e: Throwable) {}; viz = null }

        private fun startViz() {
            if (viz != null || vizFailed) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                val v = Visualizer(0)
                v.enabled = false
                v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256).coerceAtMost(Visualizer.getCaptureSizeRange()[1])
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, r: Int) {
                        if (fft == null) return
                        val n = fft.size / 2
                        var total = 0f
                        val rawb = FloatArray(32)
                        for (i in 0 until 32) {
                            val from = 1 + i * (n - 1) / 32 / 2
                            val to = Math.max(from + 1, 1 + (i + 1) * (n - 1) / 32 / 2)
                            var s = 0f
                            for (k in from until Math.min(to, n)) { val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat(); s += Math.hypot(re.toDouble(), im.toDouble()).toFloat() }
                            rawb[i] = s / (to - from) / 60f
                            total += rawb[i]
                        }
                        VizNorm.apply(this@LiquidWallpaper, rawb)
                        for (i in 0 until 32) bands[i] = rawb[i]
                        if (total > 0.05f) lastData = System.currentTimeMillis()
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                v.enabled = true; viz = v; vizStart = System.currentTimeMillis()
            } catch (e: Throwable) { vizFailed = true; stopViz() }
        }

        private fun avg(a: FloatArray, from: Int, to: Int): Float { var s = 0f; for (i in from until to) s += a[i]; return s / (to - from) }

        private fun updateBands(real: Boolean, now: Long) {
            val tb: Float; val tm: Float; val th: Float
            if (real) {
                tb = Math.min(1f, avg(bands, 0, 4) * 2.16f); tm = Math.min(1f, avg(bands, 4, 16) * 2.97f); th = Math.min(1f, avg(bands, 16, 32) * 4.05f)
            } else {
                val ph = (now % 500L) / 500f
                val kick = Math.pow(Math.max(0.0, 1.0 - ph * 2.2), 3.0).toFloat()
                tb = kick * 0.9f; tm = 0.35f + 0.25f * Math.sin(simT * 3.0).toFloat(); th = 0.3f + 0.3f * Math.sin(simT * 9.0).toFloat()
            }
            bass = if (tb > bass) bass + (tb - bass) * 0.8f else bass * 0.86f
            mid += (tm - mid) * 0.2f
            high = if (th > high) high + (th - high) * 0.5f else high * 0.88f
            bassAvg = bassAvg * 0.96f + bass * 0.04f
            val strong = bass > 0.4f && bass > bassAvg * 1.45f
            if (strong) punchV += 0.6f * bass
            punchV += -punch * 0.22f; punchV *= 0.82f; punch += punchV
        }

        private var wasPlaying = false; private var idleSince = 0L; private var playSince = 0L

        override fun onCreate(sh: SurfaceHolder) {
            super.onCreate(sh)
            applyScale(NotifState.locked(this@LiquidWallpaper))
        }

        private var scaledLocked: Boolean? = null

        /** Lock screen: sharper and 60fps. Unlocked (home screen, app drawer): cheaper so the launcher stays smooth. */
        private fun applyScale(lockedNow: Boolean) {
            if (scaledLocked == lockedNow) return
            scaledLocked = lockedNow
            try {
                val dm = resources.displayMetrics
                val f = if (lockedNow) 0.5f else 0.3f
                surfaceHolder.setFixedSize((dm.widthPixels * f).toInt(), (dm.heightPixels * f).toInt())
            } catch (e: Throwable) {}
        }

        private fun step() {
            if (!visible) return
            applyScale(NotifState.locked(this@LiquidWallpaper))
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val playing = am.isMusicActive
            val now = System.currentTimeMillis()
            if (playing && !wasPlaying) { playSince = now; idleSince = 0L }
            if (!playing && wasPlaying) idleSince = now
            wasPlaying = playing
            if (!playing) {
                stopViz()
                bass = 0f; mid = 0f; high = 0f
                if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@LiquidWallpaper) }
                render(false, false, now)
                h.postDelayed(tick, if (blackIdle) (if (idleSince > 0L && now - idleSince < 1700L) 16L else 1000L) else 33L)
                return
            }
            startViz()
            if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@LiquidWallpaper) }
            val vizLive = viz != null && now - lastData < 3000
            if (viz != null && !vizLive && now - vizStart > 4000) { stopViz(); vizStart = now; vizFailed = false }
            MusicState.mode = if (vizLive) "visualizer" else "simple"
            render(true, vizLive, now)
            val target = if (NotifState.locked(this@LiquidWallpaper)) 33L else 50L
            h.postDelayed(tick, Math.max(1L, target - (System.currentTimeMillis() - now)))
        }

        private var ovBmp: Bitmap? = null
        private var ovKey = 0
        private var rOff = 0f

        private fun render(playing: Boolean, real: Boolean, now: Long) {
            try {
                var g = gl
                if (g == null) {
                    if (failed) return
                    val ng = GlLiquid()
                    if (!ng.init(surfaceHolder.surface)) {
                        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("liquid_error", ng.error).apply()
                        failed = true; ng.release(); return
                    }
                    gl = ng; g = ng; shown = ""; want = ""; form = 0f; ovKey = 0
                }
                val lock = NotifState.show(this@LiquidWallpaper)
                val musicCard = lock && playing && (MusicState.songTitle.isNotBlank() || MusicState.songArtist.isNotBlank())
                val hasCards = lock && (musicCard || NotifState.items.isNotEmpty())
                val fr = surfaceHolder.surfaceFrame
                val sw = fr.width(); val sh = fr.height()
                if (hasCards && sw > 0 && sh > 0) {
                    val key = java.util.Objects.hash(NotifState.version, musicCard, playing, MusicState.position() / 1000, MusicState.songTitle, MusicState.songArtist, MusicState.art, MusicState.isPlaying, now / 60000, sw, sh)
                    if (key != ovKey) {
                        ovKey = key
                        val rh = (sh * 0.5f).toInt()
                        var b = ovBmp
                        if (b == null || b.width != sw || b.height != rh) { b = Bitmap.createBitmap(sw, rh, Bitmap.Config.ARGB_8888); ovBmp = b }
                        b!!.eraseColor(Color.TRANSPARENT)
                        val cv = Canvas(b)
                        cv.translate(0f, -(sh - rh).toFloat())
                        NotifRender.draw(cv, sw.toFloat(), sh.toFloat(), true, playing)
                        g.setOverlay(b, rh.toFloat() / sh)
                    }
                } else if (ovKey != 0) { g.setOverlay(null, 0f); ovKey = 0 }
                rOff += ((if (hasCards) 0.075f * sh else 0f) - rOff) * 0.1f
                g.offY = rOff
                g.rim = if (rimOn) 1f else 0f
                if (!playing && blackIdle && (idleSince == 0L || now - idleSince >= 1600L)) { g.black(); return }
                val fade = if (!playing && blackIdle) 1f - (now - idleSince) / 1600f else if (playing && playSince > 0L && now - playSince < 1000L) (now - playSince) / 1000f else 1f
                val dt = if (lastNow == 0L) 0.033f else ((now - lastNow) / 1000f).coerceIn(0.004f, 0.1f)
                lastNow = now
                if (playing) { simT += dt; updateBands(real, now) } else { punch *= 0.9f }
                rT += dt
                val loudT = if (playing) (0.55f * mid + 0.4f * bass + 0.5f * high).coerceIn(0f, 1f) else 0f
                rLoud += (loudT - rLoud) * (if (loudT > rLoud) 0.06f else 0.010f) * (dt / 0.033f).coerceAtMost(3f)
                val sT = 1f + 0.047f * bass + 0.068f * mid + 0.047f * high + 0.095f * punch.coerceIn(-0.4f, 1f)
                rScale += (sT - rScale) * 0.2f
                rPulse += (Math.max(0f, punch) * 1.35f + 0.47f * high + 0.34f * mid - rPulse) * 0.25f
                rSpin += dt * (0.16f + 0.35f * rLoud)
                // title: melt old, then form new
                val t = if (playing) MusicState.songTitle.trim() else ""; val a = if (playing) MusicState.songArtist.trim() else ""
                want = ""
                if (shown != want) {
                    form -= dt / 0.7f
                    if (form <= 0f) { form = 0f; shown = want; if (want.isNotEmpty()) g.setTitle(GlLiquid.titleMap(want)) }
                } else if (shown.isNotEmpty()) form = Math.min(1f, form + dt / 1.2f)
                val fs = form * form * (3f - 2f * form)
                // stereo pull with resistance: attraction point = pan, body trails on a soft spring and stretches under tension
                val stLive = StereoState.live(now)
                val sim = 0f
                liqT += dt * (0.5f + 1.2f * rLoud)
                val on = playing && stLive
                val kk = if (on) 1f else 0f
                // independent per-side pulls, smoothed (fast rise, slower relax for an elastic feel)
                val aR = 1f - Math.exp(-dt * 14.0).toFloat(); val aF = 1f - Math.exp(-dt * 6.0).toFloat()
                fun sm(cur: Float, tg: Float) = cur + (tg - cur) * (if (tg > cur) aR else aF)
                lvel = sm(lvel, StereoState.sL * kk); lpos = sm(lpos, StereoState.sR * kk)
                lvelY = sm(lvelY, StereoState.sD * kk); lposY = sm(lposY, StereoState.sU * kk)
                lstretch = Math.max(Math.max(lvel, lpos), Math.max(lvelY, lposY))
                lbass += ((0.6f * bass + 0.4f * punch.coerceIn(0f, 1f)) - lbass) * 0.25f
                g.draw(lbass.coerceIn(0f, 1.2f), rPulse.coerceIn(0f, 1f), high.coerceIn(0f, 1f), liqT, 0f, lstretch, fade.coerceIn(0f, 1f), 0f, ldx, ldy, lvel, lpos, lvelY, lposY)
            } catch (e: Throwable) {
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("liquid_error", "render: " + e.message).apply()
                failed = true; releaseGl()
            }
        }
    }
}
