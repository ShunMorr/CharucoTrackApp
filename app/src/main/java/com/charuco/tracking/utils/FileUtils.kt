package com.charuco.tracking.utils

/**
 * File utility functions
 */
object FileUtils {
    /**
     * Sanitize filename to prevent path traversal and invalid characters
     * Removes or replaces: / \ : * ? " < > | ..
     */
    fun sanitizeFileName(fileName: String): String {
        if (fileName.isBlank()) {
            return "untitled"
        }

        return fileName
            .trim()
            // Remove path traversal attempts
            .replace("..", "")
            // Replace invalid characters with underscore
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            // Limit length to 200 characters
            .take(200)
            .ifBlank { "untitled" }
    }
}
