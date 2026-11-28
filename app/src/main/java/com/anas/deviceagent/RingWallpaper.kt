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

const val RING_FRAG = """
precision highp float;
uniform vec2 uRes;
uniform mat3 uR;
uniform float uScale;
uniform float uWave;
uniform float uSpin;
uniform float uTime;
uniform float uPulse;
uniform float uForm;
uniform float uLiq;
uniform float uFade;
uniform float uOffY;
uniform vec4 uRect;
uniform vec2 uTexel;
uniform sampler2D uTex;

const float RR = 0.72;
const float TR = 0.15;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdf(vec3 p) {
    vec3 q = uR * p;
    float ang = atan(q.z, q.x);
    float w2 = uWave - 1.0;
    float wav = uWave * (0.022 * sin(3.0 * ang + 0.7 + uSpin) + 0.012 * sin(5.0 * ang + 2.0 - uSpin * 1.3))
              + w2 * 0.006 * sin(8.0 * ang + uSpin * 2.0 + uTime * 0.8);
    float thick = 1.0 + 0.07 * sin(2.0 * ang + 1.3 + uSpin);
    float rr = length(q.xz);
    vec2 d2 = vec2(rr - (RR * uScale + wav), q.y + 0.02 * sin(4.0 * ang + uSpin));
    return length(d2) - TR * uScale * thick;
}

float boxl(vec3 dv, vec3 c, float wid) {
    return exp(-((1.0 - dot(dv, normalize(c))) / wid));
}

float particles(vec2 uv, float sc, float sp, float seed) {
    vec2 g = uv * sc + vec2(0.0, uTime * sp);
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    float h = hash(id + seed);
    if (h > 0.20) return 0.0;
    vec2 o = vec2(hash(id + seed + 3.1), hash(id + seed + 7.7)) - 0.5;
    vec2 dd = f - o * 0.7;
    float tw = 0.7 + 0.3 * sin(uTime * (0.5 + h * 6.0) + h * 40.0);
    return exp(-dot(dd, dd) * 900.0 / (sc * 0.12)) * (0.05 + 0.12 * hash(id + seed + 1.3)) * tw;
}

void main() {
    vec2 fc = gl_FragCoord.xy;
    float X = (fc.x - uRes.x * 0.5) / uRes.x * 2.0;
    float Y = (fc.y - uRes.y * 0.5 - uOffY) / uRes.x * 2.0;
    float r = sqrt(X * X + Y * 0.9 * Y * 0.9);
    float base = 0.05 + 0.065 * exp(-r * r * 1.4) - 0.025 * (Y * 0.5);
    vec3 bg = vec3(base * 0.98, base, base * 1.05);

    vec3 ro = vec3(0.0, 0.0, -3.9);
    vec3 rd = normalize(vec3(X * 0.55, Y * 0.55, 1.0));
    vec3 col = bg;

    float bb = dot(ro, rd);
    float rb = (RR + TR * 1.1) * uScale + 0.05 * uWave + 0.06;
    float cc = dot(ro, ro) - rb * rb;
    float disc = bb * bb - cc;
    if (disc > 0.0) {
        float sq = sqrt(disc);
        float t = max(0.5, -bb - sq);
        float tmax = -bb + sq;
        float dmin = 1e9;
        float tmin = t;
        bool hit = false;
        for (int i = 0; i < 44; i++) {
            vec3 p = ro + rd * t;
            float h = sdf(p);
            if (h < dmin) { dmin = h; tmin = t; }
            if (h < 0.002) { hit = true; break; }
            t += h * 0.9;
            if (t > tmax) break;
        }
        float px = tmin * 2.0 * 0.55 / uRes.x;
        float cov = hit ? 1.0 : (1.0 - smoothstep(0.0, px * 1.3, dmin));
        if (cov > 0.001) {
            vec3 p = ro + rd * tmin;
            vec2 k = vec2(1.0, -1.0);
            float e = 0.003;
            vec3 n = normalize(k.xyy * sdf(p + k.xyy * e) + k.yyx * sdf(p + k.yyx * e)
                             + k.yxy * sdf(p + k.yxy * e) + k.xxx * sdf(p + k.xxx * e));
            vec3 v = -rd;
            float ndv = clamp(dot(n, v), 0.0, 1.0);
            float fres = pow(1.0 - ndv, 2.5);
            vec3 refl = reflect(rd, n);
            float env = 0.9 * boxl(refl, vec3(-0.6, 0.8, -0.5), 0.035)
                      + 0.6 * boxl(refl, vec3(0.7, 0.5, -0.6), 0.05)
                      + 0.35 * boxl(refl, vec3(0.0, -0.9, -0.3), 0.09)
                      + 0.5 * boxl(refl, vec3(0.1, 0.2, -1.0), 0.02)
                      + 0.1 * (0.5 + 0.5 * refl.y);
            vec3 L = normalize(vec3(-0.5, 0.7, -0.6));
            vec3 hv = normalize(L + v);
            float spec = pow(clamp(dot(n, hv), 0.0, 1.0), 120.0);
            vec3 qn = uR * p;
            float rr = length(qn.xz);
            float dw = rr - (RR - TR * 0.8) * uScale;
            float inner_wall = exp(-(dw * dw) / 0.01) * (0.05 + 0.05 * uPulse);
            float body = 0.03 + 0.05 * ndv * ndv + inner_wall;
            vec3 sil = vec3(0.80, 0.85, 0.92);
            vec3 c = body * vec3(1.0, 1.02, 1.07)
                   + (0.85 * fres + 0.06) * env * sil * (1.0 + 0.6 * uPulse)
                   + spec * vec3(0.92, 0.95, 1.0) * (1.0 + 0.5 * uPulse)
                   + 0.12 * fres * fres * vec3(0.62, 0.67, 0.75);
            col = bg * (1.0 - cov) + (c + bg * 0.6 * 0.5 * ndv) * cov;
        }
    }
    // faint cold halo that breathes with the bass
    float hd = sqrt(X * X + Y * Y) - 0.68 * uScale;
    col += vec3(0.8, 0.85, 1.0) * exp(-(hd * hd) / 0.03) * (0.008 + 0.03 * uPulse);
    col += vec3(exp(-(X * X + Y * Y) * 2.2) * 0.018);

    // liquid glass title under the ring
    vec2 tuv = (fc - uRect.xy) / (uRect.zw - uRect.xy);
    if (tuv.x > 0.0 && tuv.x < 1.0 && tuv.y > -0.15 && tuv.y < 1.15 && uForm > 0.01) {
        float fl = uLiq;
        vec2 w = tuv;
        w.y += fl * 0.05 * sin(tuv.x * 7.0 + uTime * 1.2) + fl * 0.02 * sin(tuv.x * 17.0 - uTime * 1.9);
        w.x += fl * 0.012 * sin(tuv.y * 9.0 + uTime * 1.5);
        float hv = texture2D(uTex, w).r * uForm;
        float ex = uTexel.x * 1.5;
        float ey = uTexel.y * 1.5;
        float hx = texture2D(uTex, w + vec2(ex, 0.0)).r - texture2D(uTex, w - vec2(ex, 0.0)).r;
        float hy = texture2D(uTex, w + vec2(0.0, ey)).r - texture2D(uTex, w - vec2(0.0, ey)).r;
        float g = smoothstep(0.30, 0.58, hv);
        if (g > 0.003) {
            vec3 n2 = normalize(vec3(-hx * 5.0 * uForm, -hy * 5.0 * uForm, 1.0));
            vec3 rf = vec3(2.0 * n2.z * n2.x, 2.0 * n2.z * n2.y, -(2.0 * n2.z * n2.z - 1.0));
            float env2 = 1.7 * (0.9 * boxl(rf, vec3(-0.6, 0.8, -0.5), 0.06) + 0.6 * boxl(rf, vec3(0.7, 0.5, -0.6), 0.08)
                       + 0.4 * boxl(rf, vec3(0.0, -0.9, -0.3), 0.1)) + 0.12 * (0.5 + 0.5 * rf.y);
            float fr2 = pow(1.0 - n2.z, 1.2);
            vec3 Ls = normalize(vec3(-0.5, 0.7, 0.6));
            float sp2 = pow(clamp(dot(n2, Ls), 0.0, 1.0), 60.0);
            vec3 liquid = (0.03 + 0.05 * hv) * vec3(1.0, 1.02, 1.07)
                        + (0.95 * fr2 * env2 + 0.07) * vec3(0.80, 0.85, 0.92)
                        + 1.3 * sp2 * vec3(0.92, 0.95, 1.0);
            vec3 tcol = col * 0.5 + liquid * 1.7 * (1.0 + 0.5 * uPulse);
            col = mix(col, tcol, g * 0.96);
        }
    }

    // dim particles
    float pr = particles(vec2(X, Y), 9.0, 0.012, 0.0) + particles(vec2(X, Y), 17.0, 0.02, 11.0);
    col += vec3(0.8, 0.84, 0.9) * pr * 2.2;
    col *= (1.0 - 0.35 * r * r);
    col += (hash(fc + fract(uTime)) - 0.5) * 0.008;
    gl_FragColor = vec4(clamp(col, 0.0, 1.0) * uFade, 1.0);
}

"""


/** Output-mix FFT is captured AFTER the volume stage on many phones, so raw levels follow the volume keys.
 *  This undoes volume changes (stream volume ratio) and normalises against a slowly decaying peak so only the song's own dynamics remain. */
object VizNorm {
    private var ref = 0.05f
    private var lastIdx = -1
    private var lastT = 0L
    fun apply(ctx: Context, raw: FloatArray) {
        val now = System.currentTimeMillis()
        val dt = if (lastT == 0L) 0.05f else ((now - lastT) / 1000f).coerceIn(0.005f, 0.5f)
        lastT = now
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val idx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (lastIdx >= 0 && idx != lastIdx && idx > 0 && lastIdx > 0) {
                val g = Math.pow(idx.toDouble() / lastIdx.toDouble(), 1.6).toFloat()
                ref = (ref * g).coerceIn(0.01f, 4f)
            }
            lastIdx = idx
        } catch (e: Throwable) {}
        var e = 0f
        for (i in 0 until 16) e += raw[i]
        e /= 16f
        ref = if (e > ref) e else ref * Math.exp((-dt / 6f).toDouble()).toFloat()
        if (ref < 0.03f) ref = 0.03f
        val k = 0.8f / ref
        for (i in raw.indices) raw[i] = Math.min(1f, raw[i] * k)
    }
}

/** Minimal EGL/GLES2 full-screen shader renderer for the glass ring scene. */
class GlRing {
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
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, RING_FRAG)
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

    fun draw(ax: Float, ay: Float, scale: Float, wave: Float, spin: Float, time: Float, pulse: Float, form: Float, liq: Float, fade: Float) {
        val a = IntArray(1)
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, a, 0); w = a[0]
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, a, 0); h = a[0]
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        val cx = Math.cos(ax.toDouble()); val sx = Math.sin(ax.toDouble()); val cy = Math.cos(ay.toDouble()); val sy = Math.sin(ay.toDouble())
        // R = Ry * Rx
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cx, -sx), doubleArrayOf(0.0, sx, cx))
        val ry = arrayOf(doubleArrayOf(cy, 0.0, sy), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sy, 0.0, cy))
        val r = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0 until 3) { var s = 0.0; for (k in 0 until 3) s += ry[i][k] * rx[k][j]; r[i][j] = s }
        val m = FloatArray(9)
        for (j in 0 until 3) for (i in 0 until 3) m[j * 3 + i] = r[i][j].toFloat()   // column-major
        GLES20.glUniformMatrix3fv(u("uR"), 1, false, m, 0)
        GLES20.glUniform2f(u("uRes"), w.toFloat(), h.toFloat())
        GLES20.glUniform1f(u("uScale"), scale); GLES20.glUniform1f(u("uWave"), wave); GLES20.glUniform1f(u("uSpin"), spin)
        GLES20.glUniform1f(u("uTime"), time); GLES20.glUniform1f(u("uPulse"), pulse); GLES20.glUniform1f(u("uForm"), form); GLES20.glUniform1f(u("uLiq"), liq); GLES20.glUniform1f(u("uFade"), fade); GLES20.glUniform1f(u("uOffY"), offY)
        val tw = 0.94f * w; val th = tw / 3f; val cyc = h * 0.36f
        GLES20.glUniform4f(u("uRect"), w * 0.03f, cyc - th / 2f, w * 0.97f, cyc + th / 2f)
        GLES20.glUniform2f(u("uTexel"), 1f / TW, 1f / TH)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(u("uTex"), 0)
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

class RingWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = RE()

    inner class RE : Engine() {
        private val h = Handler(Looper.getMainLooper())
        private var visible = false
        private var gl: GlRing? = null
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
        private var simT = 0f
        private val tick = Runnable { step() }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            h.removeCallbacks(tick)
            if (v) {
                blackIdle = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_black_idle", true)
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
                        VizNorm.apply(this@RingWallpaper, rawb)
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
            setTouchEventsEnabled(true)
            applyScale(NotifState.locked(this@RingWallpaper))
        }

        private var scaledLocked: Boolean? = null
        private var lowBatt = false

        /** Lock screen: sharper and 60fps. Unlocked (home screen, app drawer): cheaper so the launcher stays smooth. */
        private fun applyScale(lockedNow: Boolean) {
            val lb = WpExtras.lowBattery(this@RingWallpaper)
            if (scaledLocked == lockedNow && lowBatt == lb) return
            scaledLocked = lockedNow; lowBatt = lb
            try {
                val dm = resources.displayMetrics
                val f = (if (lockedNow) 0.65f else 0.45f) * (if (lb) 0.75f else 1f)
                surfaceHolder.setFixedSize((dm.widthPixels * f).toInt(), (dm.heightPixels * f).toInt())
            } catch (e: Throwable) {}
        }

        private fun touchPulse() { punch = 1f; punchV = 0f; h.removeCallbacks(tick); h.post(tick) }

        override fun onTouchEvent(e: android.view.MotionEvent) {
            if (e.action == android.view.MotionEvent.ACTION_DOWN) touchPulse()
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: android.os.Bundle?, resultRequested: Boolean): android.os.Bundle? {
            if (action == "android.wallpaper.tap") touchPulse()
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun step() {
            if (!visible) return
            applyScale(NotifState.locked(this@RingWallpaper))
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val playing = MusicGate.playing(this@RingWallpaper, System.currentTimeMillis())
            val now = System.currentTimeMillis()
            if (playing && !wasPlaying) { playSince = now; idleSince = 0L }
            if (!playing && wasPlaying) idleSince = now
            wasPlaying = playing
            if (!playing) {
                stopViz()
                bass = 0f; mid = 0f; high = 0f
                if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@RingWallpaper) }
                render(false, false, now)
                h.postDelayed(tick, if (blackIdle) (if (idleSince > 0L && now - idleSince < 1700L) 16L else 1000L) else if (WpExtras.lowBattery(this@RingWallpaper)) 66L else 33L)
                return
            }
            startViz()
            if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@RingWallpaper) }
            val vizLive = viz != null && now - lastData < 3000
            if (viz != null && !vizLive && now - vizStart > 4000) { stopViz(); vizStart = now; vizFailed = false }
            MusicState.mode = if (vizLive) "visualizer" else "simple"
            render(true, vizLive, now)
            val target = (if (NotifState.locked(this@RingWallpaper)) 16L else 40L) * (if (WpExtras.lowBattery(this@RingWallpaper)) 2L else 1L)
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
                    val ng = GlRing()
                    if (!ng.init(surfaceHolder.surface)) {
                        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("ring_error", ng.error).apply()
                        failed = true; ng.release(); return
                    }
                    gl = ng; g = ng; shown = ""; want = ""; form = 0f; ovKey = 0
                }
                val lock = NotifState.show(this@RingWallpaper)
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
                want = if (hasCards || (t.isEmpty() && a.isEmpty())) "" else if (a.isEmpty()) t else "$t - $a"
                if (shown != want) {
                    form -= dt / 0.7f
                    if (form <= 0f) { form = 0f; shown = want; if (want.isNotEmpty()) g.setTitle(GlRing.titleMap(want)) }
                } else if (shown.isNotEmpty()) form = Math.min(1f, form + dt / 1.2f)
                val fs = form * form * (3f - 2f * form)
                val ax = -0.95f + 0.09f * Math.sin(rT * 0.31).toFloat()
                val ay = 0.5f + 0.30f * Math.sin(rT * 0.19).toFloat()
                g.draw(ax, ay, rScale, 1f + 4.3f * (0.6f * rLoud + 0.4f * (0.6f * mid + 0.8f * high).coerceIn(0f, 1f)), rSpin, rT, rPulse.coerceIn(0f, 1f), fs, (0.35f + 0.9f * rLoud).coerceAtMost(1.2f), fade.coerceIn(0f, 1f))
            } catch (e: Throwable) {
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("ring_error", "render: " + e.message).apply()
                failed = true; releaseGl()
            }
        }
    }
}
