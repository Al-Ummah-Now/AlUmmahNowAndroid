package net.alummahnow.core.bridge.studio

import net.alummahnow.core.AunException
import net.alummahnow.core.bridge.studio.core.isNativeProofRequired
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProofRequiredTest {
    @Test fun `aun-core's 403 with the code as message is recognised`() {
        assertTrue(isNativeProofRequired(AunException.Forbidden("native_proof_required")))
        assertFalse(isNativeProofRequired(AunException.Forbidden("forbidden")))
        assertFalse(isNativeProofRequired(IllegalStateException("native_proof_required")))
    }
}
