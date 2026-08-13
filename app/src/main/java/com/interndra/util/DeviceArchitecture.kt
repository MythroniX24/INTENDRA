package com.interndra.util

/**
 * DeviceArchitecture — pure, testable Android ABI detection and mapping.
 *
 * The embedded Linux environment must never download or execute binaries for
 * the wrong architecture. This helper centralizes the mapping between Android
 * ABIs (`Build.SUPPORTED_ABIS`) and the architecture names used by each
 * Linux userspace layer:
 *
 * - **Termux** bootstrap archives: aarch64 / arm / i686 / x86_64
 * - **proot-distro**: aarch64 / arm / x86_64 / i686
 *
 * All functions are pure (no `android.os.Build`) so the logic is unit-testable
 * on the JVM.
 */
object DeviceArchitecture {

    /** Android ABI -> Termux bootstrap architecture name. */
    private val TERMUX_ARCH_MAP = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "arm",
        "x86" to "i686",
        "x86_64" to "x86_64"
    )

    /** Android ABI -> proot-distro architecture name. */
    private val PROOT_ARCH_MAP = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "arm",
        "x86" to "i686",
        "x86_64" to "x86_64"
    )

    /** ABIs we can serve today. */
    val SUPPORTED_ABIS: Set<String> = TERMUX_ARCH_MAP.keys

    /** Result of a detection run. */
    data class Detection(
        /** The chosen Android ABI (e.g. "arm64-v8a"), or null if unsupported. */
        val abi: String?,
        /** Termux bootstrap architecture name (e.g. "aarch64"), or null. */
        val termuxArch: String?,
        /** proot-distro architecture name (e.g. "aarch64"), or null. */
        val prootArch: String?,
        /** Human-readable label used in the UI. */
        val label: String
    ) {
        val supported: Boolean get() = abi != null
    }

    /** Map a single Android ABI to the Termux bootstrap architecture name. */
    fun termuxArchName(abi: String): String? = TERMUX_ARCH_MAP[abi]

    /** Map a single Android ABI to the proot-distro architecture name. */
    fun prootArchName(abi: String): String? = PROOT_ARCH_MAP[abi]

    /**
     * Pick the first supported ABI from a list (typically `Build.SUPPORTED_ABIS`).
     * Order matters — earlier entries are preferred by the platform.
     */
    fun pickSupportedAbi(abis: List<String>): String? = abis.firstOrNull { it in SUPPORTED_ABIS }

    /**
     * Full detection from a list of ABIs. Returns an unsupported [Detection]
     * (with a descriptive label) when nothing matches.
     */
    fun detect(abis: List<String>): Detection {
        val abi = pickSupportedAbi(abis)
        if (abi == null) {
            return Detection(
                abi = null,
                termuxArch = null,
                prootArch = null,
                label = "Unsupported (${abis.joinToString(", ").ifBlank { "unknown" }})"
            )
        }
        return Detection(
            abi = abi,
            termuxArch = termuxArchName(abi),
            prootArch = prootArchName(abi),
            label = abi
        )
    }
}
