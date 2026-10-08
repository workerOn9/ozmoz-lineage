package io.github.workeron9.ozmoz.lineage.ir

/**
 * Never-wrong 的类型化载体：**宁可 Unknown，也不给猜测值**。
 *
 * 这是本项目的第一条设计原则在类型系统里的落点。任何「无法从权威来源推导」的结果
 * 都必须返回 [Unknown]，而不是 null、空串或一个看起来合理的默认值。
 * 调用方的 `when` 因此被迫处理未知分支——未知不会在传播中悄悄丢失。
 */
public sealed interface Resolved<out T> {

    /** 已确知的结果。 */
    public data class Known<out T>(val value: T) : Resolved<T>

    /**
     * 明确放弃推断。[reason] 必须说明**为什么**推不出来（如 `schema 缺失`、
     * `列名有歧义: id 出现在 a, b`），它会被聚合成公开的 unknown 率指标。
     */
    public data class Unknown(
        val reason: String,
        val span: Span? = null,
    ) : Resolved<Nothing> {
        init {
            require(reason.isNotBlank()) { "Unknown 必须给出非空的 reason" }
        }
    }

    /** 已知则取值，未知则返回 null。用于确实不在乎原因的调用点。 */
    public fun valueOrNull(): T? = when (this) {
        is Known -> value
        is Unknown -> null
    }

    public companion object {
        @JvmStatic
        public fun <T> known(value: T): Resolved<T> = Known(value)

        @JvmStatic
        public fun <T> unknown(reason: String, span: Span? = null): Resolved<T> = Unknown(reason, span)
    }
}

/**
 * 已知则取值，未知则用 [fallback] 现算——但不会把「未知」伪装成「已知」。
 *
 * 写成扩展函数而非成员：`Unknown` 是 `Resolved<Nothing>`，若作为成员，`T` 会被
 * 塌缩成 `Nothing`，导致运行期 `ClassCastException`。
 */
public fun <T> Resolved<T>.valueOrElse(fallback: (Resolved.Unknown) -> T): T = when (this) {
    is Resolved.Known -> value
    is Resolved.Unknown -> fallback(this)
}

/** 已知即返回自身；未知则原样透传（保持原因与位置）。 */
public inline fun <T, R> Resolved<T>.map(transform: (T) -> R): Resolved<R> = when (this) {
    is Resolved.Known -> Resolved.Known(transform(value))
    is Resolved.Unknown -> this
}

/** 链式解析：上一步未知就不进入下一步，原因与位置一路保留。 */
public inline fun <T, R> Resolved<T>.flatMap(transform: (T) -> Resolved<R>): Resolved<R> = when (this) {
    is Resolved.Known -> transform(value)
    is Resolved.Unknown -> this
}
