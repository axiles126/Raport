package com.example.report

import com.example.report.core.PosName
import org.junit.Assert.assertEquals
import org.junit.Test

class PosNameTest {
    @Test fun names() {
        assertEquals("ТЗ \"Дирижабль\"", PosName.quoted("Дирижабль"))
        assertEquals("ТЗ \"Дирижабль\"", PosName.quoted(" \"Дирижабль\" "))
        assertEquals("СП \"Дирижабль\"", PosName.quoted("СП Дирижабль"))
        assertEquals("ВОП \"Ромашка 2\"", PosName.quoted("ВОП «Ромашка 2»"))
        assertEquals("ТЗ \"Біла Церква\"", PosName.quoted("Біла Церква"))
        assertEquals("ТЗ Дирижабль", PosName.plain("Дирижабль"))
    }
}
