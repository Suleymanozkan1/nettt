package com.bookmind.app.ai

/**
 * Open-weights models the app can download and run on the phone with LiteRT-LM. All are free for commercial use
 * (Apache-2.0) and hosted without a login on Hugging Face (litert-community). Downloads are pinned to a revision
 * and verified by size and SHA-256, so the file that runs is exactly the one listed here.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    /** Context window the engine is created with (prompt + answer, in tokens). */
    val contextTokens: Int,
    /** Total RAM (as Android reports it) from which the model runs comfortably. */
    val minRamGb: Double,
    /** RAM shown to the user, in the marketing sizes phones are sold with. */
    val recommendedRamGb: Int,
    val license: String,
    val tier: Tier,
) {
    enum class Tier { LIGHT, RECOMMENDED, BEST }

    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
    val sizeGb: Double get() = bytes / 1_000_000_000.0
}

object ModelCatalog {
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b", displayName = "Gemma 4 E2B",
        repo = "litert-community/gemma-4-E2B-it-litert-lm", revision = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
        fileName = "gemma-4-E2B-it.litertlm", bytes = 2_588_147_712,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        contextTokens = 8_192, minRamGb = 5.0, recommendedRamGb = 6, license = "Apache-2.0", tier = ModelSpec.Tier.RECOMMENDED,
    )

    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b", displayName = "Gemma 4 E4B",
        repo = "litert-community/gemma-4-E4B-it-litert-lm", revision = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da",
        fileName = "gemma-4-E4B-it.litertlm", bytes = 3_659_530_240,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        contextTokens = 8_192, minRamGb = 10.0, recommendedRamGb = 12, license = "Apache-2.0", tier = ModelSpec.Tier.BEST,
    )

    val QWEN_3_06B = ModelSpec(
        id = "qwen3-0.6b", displayName = "Qwen3 0.6B",
        repo = "litert-community/Qwen3-0.6B", revision = "e6687177e00897731e7716267eebd4eba0dde7c2",
        fileName = "Qwen3-0.6B.litertlm", bytes = 613_722_264,
        sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
        contextTokens = 4_096, minRamGb = 2.5, recommendedRamGb = 3, license = "Apache-2.0", tier = ModelSpec.Tier.LIGHT,
    )

    val all = listOf(GEMMA_4_E2B, GEMMA_4_E4B, QWEN_3_06B)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /** The best model this phone runs comfortably; null if even the light one would not fit. */
    fun recommended(ramGb: Double): ModelSpec? = when {
        ramGb <= 0.0 -> GEMMA_4_E2B
        ramGb >= GEMMA_4_E2B.minRamGb -> GEMMA_4_E2B
        ramGb >= QWEN_3_06B.minRamGb -> QWEN_3_06B
        else -> null
    }

    fun fits(spec: ModelSpec, ramGb: Double): Boolean = ramGb <= 0.0 || ramGb >= spec.minRamGb
}
