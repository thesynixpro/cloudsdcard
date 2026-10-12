package com.aprax.csd

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatBytesTest {
    @Test fun formatsBytesWithoutUnitConversion() { assertEquals("512 B", formatBytes(512)) }
    @Test fun formatsKilobytes() { assertEquals("1 KB", formatBytes(1024)) }
}
