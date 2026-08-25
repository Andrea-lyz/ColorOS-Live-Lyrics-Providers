package io.github.andrealtb.coloroslyrics.provider.salt

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaltEntryPolicyTest {
    @Test fun rootEntryNotifiesXposed() = assertTrue(SaltEntryPolicy.shouldNotifyXposed(false))
    @Test fun npatchEntryDoesNotNotifyXposed() = assertFalse(SaltEntryPolicy.shouldNotifyXposed(true))
}
