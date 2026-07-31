package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ModelIntegrityTest {
    @Test
    fun `valid gguf header and minimum size are accepted`() {
        val file = File.createTempFile("intendra-model", ".gguf")
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 1, 2, 3, 4))
            assertThat(ModelIntegrity.isValidGguf(file, minimumBytes = 8L)).isTrue()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `html response with enough bytes is rejected`() {
        val file = File.createTempFile("intendra-response", ".part")
        try {
            file.writeText("<html>not a model</html>")
            assertThat(ModelIntegrity.isValidGguf(file, minimumBytes = file.length())).isFalse()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `partial gguf below minimum size is rejected`() {
        val file = File.createTempFile("intendra-partial", ".part")
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            assertThat(ModelIntegrity.isValidGguf(file, minimumBytes = 1024L)).isFalse()
        } finally {
            file.delete()
        }
    }
}
