package io.github.james_c_bc.home

import org.junit.Assert.assertEquals
import org.junit.Test

class InputsTest {
    private val service = "com.tcl.tvinput/.passthroughinput.TvPassThroughService"

    private fun hdmi(label: String, hw: Int) = Input("$service/HW$hw", label, hdmi = true)

    @Test
    fun picksHdmiInputsByPortInPortOrder() {
        // As this TV lists them: out of port order, with the tuner and AV.
        val inputs = listOf(
            Input("com.tcl.tvinput/.TDTVInputService/HW1413742848", "TV", hdmi = false),
            Input("$service/HW1413743104", "AV", hdmi = false),
            hdmi("HDMI 1", 1413744128),
            hdmi("HDMI 3", 1413744640),
            hdmi("HDMI 2", 1413744384),
        )
        assertEquals(
            listOf(
                HdmiInput(1, "$service/HW1413744128"),
                HdmiInput(2, "$service/HW1413744384"),
                HdmiInput(3, "$service/HW1413744640"),
            ),
            hdmiInputs(inputs),
        )
    }

    @Test
    fun readsThePortWithOrWithoutASpace() {
        assertEquals(
            listOf(HdmiInput(1, "$service/HW1"), HdmiInput(4, "$service/HW4")),
            hdmiInputs(listOf(hdmi("hdmi4", 4), hdmi("HDMI 1", 1))),
        )
    }

    @Test
    fun leavesOutInputsThatArentHdmi() {
        assertEquals(emptyList<HdmiInput>(), hdmiInputs(listOf(Input("$service/HW1", "HDMI 1", hdmi = false))))
    }

    @Test
    fun leavesOutHdmiInputsWithNoPortInTheirLabel() {
        assertEquals(
            listOf(HdmiInput(2, "$service/HW2")),
            hdmiInputs(listOf(hdmi("HDMI", 1), hdmi("Game console", 9), hdmi("HDMIX 3", 3), hdmi("HDMI 2", 2))),
        )
    }

    @Test
    fun keepsTheFirstInputForAPort() {
        assertEquals(
            listOf(HdmiInput(1, "$service/HW1")),
            hdmiInputs(listOf(hdmi("HDMI 1", 1), hdmi("HDMI 1", 2))),
        )
    }

    @Test
    fun noInputsMeansNoTiles() {
        assertEquals(emptyList<HdmiInput>(), hdmiInputs(emptyList()))
    }
}
