package org.zoocode.jetbrains.extensions.common

/**
 * Extension type enum for Zoo Code
 * Defines different types of extensions that can be supported
 */
enum class ExtensionType(val code: String, val displayName: String, val description: String) {
    ZOO_CODE("zoo-code", "Zoo Code", "AI-powered code assistant"),
    CLINE("cline", "Cline AI", "AI-powered coding assistant with advanced features"),
    KILO_CODE("kilo-code", "Kilo Code", "AI-powered code assistant with advanced capabilities"),
    ;

    companion object {
        /**
         * Get extension type by code
         * @param code Extension code
         * @return Extension type or null if not found
         */
        fun fromCode(code: String): ExtensionType? {
            return values().find { it.code == code }
        }

        /**
         * Get default extension type
         * @return Default extension type
         */
        fun getDefault(): ExtensionType {
            return ZOO_CODE
        }

        /**
         * Get all supported extension types
         * @return List of all extension types
         */
        fun getAllTypes(): List<ExtensionType> {
            return values().toList()
        }
    }
}