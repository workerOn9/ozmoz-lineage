package io.github.workeron9.ozmoz.lineage.ir

/**
 * 诊断的严重级别。
 */
public enum class Severity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * 引擎无关的诊断信息。
 *
 * [code] 是稳定的机器可读标识（如 `parse.unexpected_token`），调用方按它分支，
 * 不要按 [message] 的文案分支。文案可以改，[code] 一旦发布即视为契约。
 */
public data class Diagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val span: Span? = null,
    val engineId: String? = null,
) {
    init {
        require(code.isNotBlank()) { "diagnostic code 不能为空" }
        require(message.isNotBlank()) { "diagnostic message 不能为空" }
    }

    public companion object {
        @JvmStatic
        public fun info(code: String, message: String, span: Span? = null): Diagnostic =
            Diagnostic(Severity.INFO, code, message, span)

        @JvmStatic
        public fun warning(code: String, message: String, span: Span? = null): Diagnostic =
            Diagnostic(Severity.WARNING, code, message, span)

        @JvmStatic
        public fun error(code: String, message: String, span: Span? = null): Diagnostic =
            Diagnostic(Severity.ERROR, code, message, span)
    }
}
