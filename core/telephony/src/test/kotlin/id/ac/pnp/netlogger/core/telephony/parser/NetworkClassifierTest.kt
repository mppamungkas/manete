package id.ac.pnp.netlogger.core.telephony.parser

import id.ac.pnp.netlogger.core.model.radio.NetworkType
import id.ac.pnp.netlogger.core.model.raw.RawCellInfoResult
import id.ac.pnp.netlogger.core.model.raw.RawSignalStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClassifierTest {

    private val parser = CellParser()

    private fun classify(
        info: RawCellInfoResult,
        ss: RawSignalStrength? = null,
        override: Int? = 0,
        dataType: Int? = 13,
        serviceState: Int? = 0,
    ): Pair<NetworkType, id.ac.pnp.netlogger.core.model.radio.NsaInputs> {
        val result = parser.parse(ParseInput(info, ss, MOTO, NOW_MS))
        val inputs = NetworkClassifier.nsaInputs(result.cells, result.nrSignalStrengthPresent, override, dataType)
        return NetworkClassifier.classify(inputs, result.cells, serviceState) to inputs
    }

    @Test
    fun `plain LTE`() {
        assertEquals(NetworkType.LTE, classify(cellInfo(lteCell())).first)
    }

    @Test
    fun `NSA from NR signal in SignalStrength only`() {
        val (type, inputs) = classify(cellInfo(lteCell()), signalStrength(nr = listOf(nrSignal())))
        assertEquals(NetworkType.NR_NSA, type)
        assertTrue(inputs.lteServing)
        assertFalse(inputs.nrServingCellInfo)
        assertTrue(inputs.nrSignalStrength)
    }

    @Test
    fun `NSA from CellInfoNr`() {
        val (type, inputs) = classify(cellInfo(lteCell(), nrCell()))
        assertEquals(NetworkType.NR_NSA, type)
        assertTrue(inputs.nrServingCellInfo)
    }

    @Test
    fun `NSA from display override only, inputs kept`() {
        val (type, inputs) = classify(cellInfo(lteCell()), override = 3)
        assertEquals(NetworkType.NR_NSA, type)
        assertFalse(inputs.nrServingCellInfo)
        assertFalse(inputs.nrSignalStrength)
        assertEquals(3, inputs.overrideNetworkType)
    }

    @Test
    fun `NR data network type is SA`() {
        val type = classify(cellInfo(nrCell(registered = true, status = 1)), dataType = 20).first
        assertEquals(NetworkType.NR_SA, type)
    }

    @Test
    fun `LTE anchor without cell info still counts via data network type`() {
        assertEquals(NetworkType.NR_NSA, classify(cellInfo(), override = 5, dataType = 13).first)
    }

    @Test
    fun `out of service with no serving cells is NONE`() {
        assertEquals(NetworkType.NONE, classify(cellInfo(lteCell(registered = false)), dataType = 0, serviceState = 1).first)
    }

    @Test
    fun `legacy RATs`() {
        assertEquals(NetworkType.WCDMA, classify(cellInfo(wcdmaCell(registered = true)), dataType = 10).first)
        assertEquals(NetworkType.GSM, classify(cellInfo(gsmCell(registered = true)), dataType = 2).first)
    }
}
