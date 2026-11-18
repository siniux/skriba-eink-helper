package pt.flick.skriba.einkhelper

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Caneta rapida do Viwoods PARA A SKRIBA (versao Play Store, targetSdk 36, que nao pode ligar a escrita por hardware).
 *
 * A Skriba liga-se a este servico enquanto a nota esta aberta. Esta app (targetSdk 30) faz o initWriting e guarda uma
 * copia da imagem da folha da Skriba. Os pixeis chegam por uma memoria partilhada: em cada movimento da caneta a Skriba
 * copia para la o pedaco que acabou de desenhar e manda so o retangulo; aqui copia-se para a imagem e pede-se ao ecra
 * rapido para o mostrar (renderWriting), exatamente como a Skriba antiga fazia dentro do proprio processo.
 *
 * Protocolo (codigos de transacao, sem resposta, por ordem): CODE_* (iguais em pt.flick.skriba EinkHelperClient).
 */
class InkBridgeService : Service() {

    companion object {
        const val CODE_ENABLE = IBinder.FIRST_CALL_TRANSACTION + 0      // int on
        const val CODE_HOVER = IBinder.FIRST_CALL_TRANSACTION + 1       // int x, int y
        const val CODE_BITMAP = IBinder.FIRST_CALL_TRANSACTION + 2      // int w, int h, int x, int y, SharedMemory
        const val CODE_START = IBinder.FIRST_CALL_TRANSACTION + 3       // int x, int y
        const val CODE_PIXELS = IBinder.FIRST_CALL_TRANSACTION + 4      // int l, int t, int w, int h (na memoria)
        const val CODE_RENDER = IBinder.FIRST_CALL_TRANSACTION + 5      // int l, int t, int r, int b (no ecra)
        const val CODE_END = IBinder.FIRST_CALL_TRANSACTION + 6
        const val CODE_POSITION = IBinder.FIRST_CALL_TRANSACTION + 7    // int x, int y

        const val SKRIBA_PACKAGE = "pt.flick.skriba"

        /**
         * SHA-256 (hex, sem ':') dos certificados aceites para a Skriba: o da Play Store
         * (Play Console > Integridade da app > Assinatura da app). So a Skriba assinada assim se pode ligar.
         * Para testar com a Skriba instalada pelo Android Studio, usa a app auxiliar em debug (ver callerAllowed)
         * ou junta aqui o SHA-256 da chave com que a instalas.
         */
        private val ALLOWED_SIGNER_SHA256: Set<String> = setOf(
            "2C8AEEF6CE6420093E7119CCE0DE4804ADCC7B2EA2102AB8B380F8DDA0611C1B"   // Skriba na Play Store
        )

        /** Para o ecra da app: a Skriba esta ligada agora? */
        @Volatile var skribaConnected = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var enote: ENoteCalls? = null
    private var inited = false
    private var bmp: Bitmap? = null
    private var screenX = 0
    private var screenY = 0
    private var allowedUid = -1
    private val loggedFirst = HashSet<Int>()

    private var shm: SharedMemory? = null
    private var shmMap: ByteBuffer? = null
    private var shmInts: IntBuffer? = null
    private var shmW = 0
    private var rowBuf = IntArray(0)

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < IBinder.FIRST_CALL_TRANSACTION || code > IBinder.LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            if (!callerAllowed(Binder.getCallingUid())) return false
            try {
                // Le tudo ja (o Parcel e reciclado ao sair daqui) e trata na thread principal, por ordem.
                when (code) {
                    CODE_ENABLE -> { val on = data.readInt() != 0; main.post { enable(on) } }
                    CODE_HOVER -> { val x = data.readInt(); val y = data.readInt(); main.post { hover(x, y) } }
                    CODE_BITMAP -> {
                        val w = data.readInt(); val h = data.readInt(); val x = data.readInt(); val y = data.readInt()
                        val mem = SharedMemory.CREATOR.createFromParcel(data)
                        main.post { setBitmap(w, h, x, y, mem) }
                    }
                    CODE_START -> { val x = data.readInt(); val y = data.readInt(); main.post { start(x, y) } }
                    CODE_PIXELS -> {
                        val l = data.readInt(); val t = data.readInt(); val w = data.readInt(); val h = data.readInt()
                        main.post { copyFromShared(l, t, w, h) }
                    }
                    CODE_RENDER -> {
                        val r = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                        main.post { enote?.quiet("renderWriting", r) }
                    }
                    CODE_END -> main.post { enote?.quiet("onWritingEnd") }
                    CODE_POSITION -> { val x = data.readInt(); val y = data.readInt(); main.post { position(x, y) } }
                    else -> return false
                }
                if (loggedFirst.add(code)) HelperLog.add(this@InkBridgeService, "Skriba: primeira mensagem $code")
            } catch (t: Throwable) {
                HelperLog.add(this@InkBridgeService, "Skriba: erro na mensagem $code: $t")
            }
            return true
        }
    }

    private fun callerAllowed(uid: Int): Boolean {
        if (uid == allowedUid || uid == android.os.Process.myUid()) return true
        val pm = packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return false
        // app auxiliar compilada em debug (testes): aceita a Skriba de qualquer chave
        val helperIsDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val ok = pkgs.any { pkg -> pkg == SKRIBA_PACKAGE && (helperIsDebug || ALLOWED_SIGNER_SHA256.isEmpty() || signerOk(pm, pkg)) }
        if (ok) allowedUid = uid else HelperLog.add(this, "Ligacao recusada (uid $uid: ${pkgs.joinToString()})")
        return ok
    }

    private fun signerOk(pm: PackageManager, pkg: String): Boolean = ALLOWED_SIGNER_SHA256.any { hex ->
        try {
            val cert = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            pm.hasSigningCertificate(pkg, cert, PackageManager.CERT_INPUT_SHA256)
        } catch (_: Throwable) { false }
    }

    override fun onBind(intent: Intent?): IBinder {
        HelperLog.add(this, "=== Skriba ligou-se | targetSdk ${applicationInfo.targetSdkVersion} ===")
        skribaConnected = true
        main.post { ensureInit() }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        HelperLog.add(this, "=== Skriba desligou-se ===")
        skribaConnected = false
        main.post { shutdown() }
        return false
    }

    override fun onDestroy() {
        skribaConnected = false
        shutdown()
        super.onDestroy()
    }

    private fun ensureInit() {
        if (inited) return
        val e = enote ?: ENoteCalls(this).also { enote = it }
        if (!e.available) return
        e.call("setApplicationContext", applicationContext)
        e.call("initWriting")
        fastMode(e)
        e.call("setT1000AutoDrawEnabled", false)
        if (!e.call("setViewAutoDrawEnabled", null, false)) {
            e.call("setViewAutoDrawEnabled", android.view.View(this), false)
        }
        e.call("setAutoDrawToolType", 0)
        e.call("setAutoDrawPenWidthRange", 2, 4)
        inited = true
        HelperLog.add(this, "=== caneta rapida pronta ===")
    }

    private fun shutdown() {
        releaseShared()
        bmp = null
        if (!inited) return
        inited = false
        val e = enote ?: return
        e.quiet("onWritingEnd")
        e.quiet("setWritingEnabled", false)
        e.quiet("exitWriting")
        e.quiet("setPictureMode", 0)
    }

    /** enableFastWritingMode(true) da Skriba antiga. */
    private fun fastMode(e: ENoteCalls) {
        e.quiet("setPictureMode", 4)
        e.quiet("setRenderWritingDelayCount", 0)
        e.quiet("setWritingInputJumpPointCount", 0)
        e.quiet("setOpenTextContrast", true)
        e.quiet("setAllRegionUnAutoDraw", false)
        e.quiet("setWritingEnabled", true)
    }

    private fun bind(x: Int, y: Int) {
        val e = enote ?: return
        val b = bmp ?: return
        screenX = x
        screenY = y
        e.quiet("setWritingJavaBitmap", b, 0, x, y)
        e.quiet("setWritingJavaBitmap", b, x, y)
        e.quiet("setWritingJavaBitmap", b)
    }

    private fun enable(on: Boolean) {
        ensureInit()
        val e = enote ?: return
        if (on) fastMode(e) else e.quiet("setWritingEnabled", false)
    }

    /** prepareOnHoverEnter da Skriba antiga. */
    private fun hover(x: Int, y: Int) {
        ensureInit()
        val e = enote ?: return
        e.quiet("setPictureMode", 4)
        e.quiet("setRenderWritingDelayCount", 0)
        e.quiet("setWritingInputJumpPointCount", 0)
        e.quiet("setAllRegionUnAutoDraw", false)
        e.quiet("setWritingEnabled", true)
        bind(x, y)
    }

    /** prebindBitmapToHardware da Skriba antiga (mesma imagem, nova posicao). */
    private fun position(x: Int, y: Int) {
        ensureInit()
        val e = enote ?: return
        e.quiet("setPictureMode", 4)
        e.quiet("setWritingEnabled", true)
        bind(x, y)
    }

    /** bindBitmapAndStartStroke da Skriba antiga. */
    private fun start(x: Int, y: Int) {
        ensureInit()
        val e = enote ?: return
        e.quiet("setPictureMode", 4)
        e.quiet("setWritingEnabled", true)
        bind(x, y)
        e.quiet("onWritingStart")
    }

    private fun releaseShared() {
        shmMap?.let { try { SharedMemory.unmap(it) } catch (_: Throwable) { } }
        try { shm?.close() } catch (_: Throwable) { }
        shm = null
        shmMap = null
        shmInts = null
        shmW = 0
    }

    /** Nova imagem da folha: memoria partilhada com os pixeis (ja copiados pela Skriba). */
    private fun setBitmap(w: Int, h: Int, x: Int, y: Int, mem: SharedMemory) {
        if (w <= 0 || h <= 0) { try { mem.close() } catch (_: Throwable) { }; return }
        ensureInit()
        releaseShared()
        try {
            val map = mem.mapReadOnly().order(ByteOrder.nativeOrder())
            shm = mem
            shmMap = map
            shmInts = map.asIntBuffer()
            shmW = w
        } catch (t: Throwable) {
            HelperLog.add(this, "Erro ao abrir a memoria partilhada: $t")
            try { mem.close() } catch (_: Throwable) { }
            return
        }
        var b = bmp
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            bmp = b
        }
        copyFromShared(0, 0, w, h)
        position(x, y)
    }

    /** Copia um retangulo da memoria partilhada para a imagem dada ao ecra rapido. */
    private fun copyFromShared(l: Int, t: Int, w: Int, h: Int) {
        val b = bmp ?: return
        val ints = shmInts ?: return
        if (w <= 0 || h <= 0 || l < 0 || t < 0 || l + w > b.width || t + h > b.height) return
        val rowsPerChunk = ((64 * 1024) / w).coerceAtLeast(1)
        var top = t
        try {
            while (top < t + h) {
                val rows = minOf(rowsPerChunk, t + h - top)
                val n = w * rows
                if (rowBuf.size < n) rowBuf = IntArray(n)
                for (row in 0 until rows) {
                    ints.position((top + row) * shmW + l)
                    ints.get(rowBuf, row * w, w)
                }
                b.setPixels(rowBuf, 0, w, l, top, w, rows)
                top += rows
            }
        } catch (_: Throwable) { }
    }
}
