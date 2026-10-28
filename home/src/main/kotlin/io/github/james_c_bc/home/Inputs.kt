package io.github.james_c_bc.home

/**
 * A TV input as the TV reports it. [hdmi] means a pass-through HDMI input:
 * a hardware port, not a tuner or a CEC device behind a port.
 */
data class Input(val id: String, val label: String, val hdmi: Boolean)

/** A hardware HDMI port and the TV input that shows it. */
data class HdmiInput(val port: Int, val inputId: String)

private val HDMI_PORT = Regex("""\bHDMI\s*(\d+)\b""", RegexOption.IGNORE_CASE)

/**
 * Picks out the HDMI inputs in [inputs] and the port each one is, from its
 * label ("HDMI 2", "HDMI2"), in port order. Inputs whose label names no port
 * are left out, and so is any later input for a port already taken.
 */
fun hdmiInputs(inputs: List<Input>): List<HdmiInput> =
    inputs
        .filter { it.hdmi }
        .mapNotNull { input ->
            HDMI_PORT.find(input.label)?.groupValues?.get(1)?.toIntOrNull()?.let { HdmiInput(it, input.id) }
        }
        .distinctBy { it.port }
        .sortedBy { it.port }
