package com.storyteller_f.dush

import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class CredentialDeletionTest {
    @Test fun retryAfterNativeDeletionCompletesMarkerCleanup() {
        val directory = Files.createTempDirectory("credential-delete")
        val marker = Files.createDirectory(directory.resolve("marker"))
        val obstruction = Files.createFile(marker.resolve("obstruction"))
        var native: String? = "secret"
        val empty = { native = "" }
        val delete = { check(native != null); native = null }
        try {
            assertFailsWith<java.nio.file.DirectoryNotEmptyException> {
                removeMarkedCredential(marker, empty, delete)
            }
            assertNull(native)
            Files.delete(obstruction)

            removeMarkedCredential(marker, empty, delete)

            assertNull(native)
            assertFalse(Files.exists(marker))
        } finally {
            Files.deleteIfExists(obstruction)
            Files.deleteIfExists(marker)
            Files.delete(directory)
        }
    }

    @Test fun accessFailureRetainsMarkerAndDoesNotAttemptDeletion() {
        val marker = Files.createTempFile("credential-marker", ".tmp")
        try {
            assertFailsWith<IllegalStateException> {
                removeMarkedCredential(marker, empty = { error("locked") },
                    delete = { fail("Must not delete after access failure") })
            }
            assertTrue(Files.exists(marker))
        } finally { Files.delete(marker) }
    }
}
