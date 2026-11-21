package com.oryksa.sdk

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class AttachmentsTest {
    @Test
    fun aDocumentGoesAsFileBase64NameAndMime() = runBlocking {
        val pdf = "%PDF-1.4 orcamento".toByteArray()
        val bodies = mutableListOf<JSONObject>()
        TinyServer { target, _, body ->
            assertEquals("/v1/client/chat", target)
            bodies.add(JSONObject(body))
            200 to """{"status":"replied","reply":"Recebi o documento."}"""
        }.use { server ->
            val c = OryksaClient(token = "oryk_cs_test", baseUrl = "http://127.0.0.1:${server.port}/v1")
            val reply = c.sendAndWait("Veja o orcamento", file = OryksaFile(pdf, "orcamento.pdf", "application/pdf"))
            assertEquals("Recebi o documento.", reply)
            val b = bodies.single()
            assertEquals("Veja o orcamento", b.getString("message"))
            assertEquals(Base64.getEncoder().encodeToString(pdf), b.getString("file_base64"))
            assertEquals("orcamento.pdf", b.getString("file_name"))
            assertEquals("application/pdf", b.getString("file_mime"))
            assertFalse(b.has("image_base64"))

            val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
            c.send("", image = OryksaImage(jpg))
            val i = bodies.last()
            assertEquals("", i.getString("message"))
            assertEquals(Base64.getEncoder().encodeToString(jpg), i.getString("image_base64"))
            assertEquals("image/jpeg", i.getString("image_mime"))
            assertFalse(i.has("file_base64"))
        }
    }

    @Test
    fun noAttachmentNoAttachmentFields() {
        assertEquals(listOf("message"), OryksaClient.chatBody("Olá").keys().asSequence().toList())
    }

    @Test
    fun base64IsTheStandardOne() {
        val r = Random(7)
        for (n in 0..40) {
            val b = ByteArray(n) { r.nextInt(256).toByte() }
            assertEquals(Base64.getEncoder().encodeToString(b), oryksaBase64(b))
        }
        val big = ByteArray(300_001) { r.nextInt(256).toByte() }
        assertEquals(Base64.getEncoder().encodeToString(big), oryksaBase64(big))
    }

    @Test
    fun theLimits() {
        assertEquals(2 * 1024 * 1024, ORYKSA_MAX_PHOTO_BYTES)
        assertEquals(8 * 1024 * 1024, ORYKSA_MAX_FILE_BYTES)
        assertSame(OryksaAttachment.TooBig, oryksaDocumentAttachment(ByteArray(ORYKSA_MAX_FILE_BYTES + 1), "big.pdf"))
        val f = oryksaDocumentAttachment(ByteArray(ORYKSA_MAX_FILE_BYTES), "Contrato.PDF") as OryksaAttachment.File
        assertEquals("application/pdf", f.file.mime)
        assertEquals("Contrato.PDF", f.file.name)
        assertSame(OryksaAttachment.TooBig, oryksaPhotoAttachment(ByteArray(ORYKSA_MAX_PHOTO_BYTES + 1)))
        val p = oryksaPhotoAttachment(ByteArray(10)) as OryksaAttachment.Image
        assertEquals("image/jpeg", p.image.mime)
    }

    @Test
    fun aPictureFromTheDocumentPicker() {
        // up to 2 MB it goes as a photo (the AI looks at it), larger as a file
        val small = oryksaDocumentAttachment(ByteArray(1000), "foto.png") as OryksaAttachment.Image
        assertEquals("image/png", small.image.mime)
        val large = oryksaDocumentAttachment(ByteArray(ORYKSA_MAX_PHOTO_BYTES + 1), "foto.png") as OryksaAttachment.File
        assertEquals("image/png", large.file.mime)
    }

    @Test
    fun theTypesOfDocument() {
        assertEquals("application/pdf", oryksaMimeOf("a.pdf"))
        assertEquals("application/msword", oryksaMimeOf("a.doc"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", oryksaMimeOf("a.docx"))
        assertEquals("text/plain", oryksaMimeOf("lista.TXT"))
        assertEquals("text/csv", oryksaMimeOf("a.csv"))
        assertEquals("application/octet-stream", oryksaMimeOf("a.zip"))
        val unknown = oryksaDocumentAttachment(ByteArray(5), "notas", "text/markdown") as OryksaAttachment.File
        assertEquals("text/markdown", unknown.file.mime)
        assertTrue(oryksaDocPickerTypes.contains("application/pdf") && oryksaDocPickerTypes.contains("image/*"))
    }

    @Test
    fun aPhotoIsMadeSmaller() {
        assertEquals(1600 to 1000, oryksaScaledSize(3200, 2000))
        assertEquals(1000 to 1600, oryksaScaledSize(2000, 3200))
        assertEquals(800 to 600, oryksaScaledSize(800, 600))
        assertEquals(2, oryksaSampleSize(4000, 3000))
        assertEquals(1, oryksaSampleSize(3000, 2000))
        assertEquals(4, oryksaSampleSize(8000, 6000))
    }
}
