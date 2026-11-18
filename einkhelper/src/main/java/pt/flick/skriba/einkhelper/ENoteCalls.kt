package pt.flick.skriba.einkhelper

import android.content.Context
import java.lang.reflect.Method

/** Chamadas ao controlador do Viwoods (android.os.enote.ENoteSetting) por reflexao, todas registadas. */
class ENoteCalls(private val ctx: Context) {
    private val inst: Any?
    private val methods: Array<Method>

    init {
        var i: Any? = null
        var m: Array<Method> = emptyArray()
        try {
            val cls = Class.forName("android.os.enote.ENoteSetting")
            i = cls.getMethod("getInstance").invoke(null)
            m = cls.methods
            HelperLog.add(ctx, "ENoteSetting.getInstance: ok")
        } catch (t: Throwable) {
            HelperLog.add(ctx, "ENoteSetting.getInstance: ERRO ${(t.cause ?: t)}")
        }
        inst = i
        methods = m
    }

    val available: Boolean get() = inst != null

    /** Como call(), mas sem registar nada (para chamadas repetidas). Devolve o resultado, ou null. */
    fun quiet(name: String, vararg args: Any?): Any? {
        val target = inst ?: return null
        val m = methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size } ?: return null
        return try { m.invoke(target, *args) } catch (_: Throwable) { null }
    }

    /** Chama o primeiro metodo com este nome e numero de argumentos. Devolve false se nao existir ou falhar. */
    fun call(name: String, vararg args: Any?): Boolean {
        val target = inst ?: return false
        val m = methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
        if (m == null) {
            HelperLog.add(ctx, "  $name(${args.size} args): nao existe")
            return false
        }
        HelperLog.add(ctx, "-> $name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})")
        return try {
            val r = m.invoke(target, *args)
            HelperLog.add(ctx, "<- $name = ${r ?: "ok"}")
            true
        } catch (t: Throwable) {
            HelperLog.add(ctx, "<- $name ERRO ${(t.cause ?: t)}")
            false
        }
    }
}
