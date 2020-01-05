package com.pocketimps.unlzma

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val SIZE_PROPERTIES = 5
private const val MODEL_BIT_COUNT = 11
private const val MODEL_BIT_PATTERN = (1 shl MODEL_BIT_COUNT)
private const val MODEL_INIT = (MODEL_BIT_PATTERN shr 1).toShort()

private const val STATE_COUNT = 12
private const val LEN_TO_POS_STATES = 4
private const val MATCH_MIN_LEN = 2
private const val ALIGN_BITS = 4

private const val START_POS_MODEL_INDEX = 4
private const val END_POS_MODEL_INDEX = 14
private const val DISTANCE_COUNT = (1 shl (END_POS_MODEL_INDEX shr 1))

private const val MAX_LC_BITS = 8
private const val MAX_LP_BITS = 4
private const val MAX_PB_BITS = 4


@Suppress("NOTHING_TO_INLINE")
private inline fun Byte.toIntFixed() = toInt().let {
    if (it < 0)
        it + 256
    else
        it
}


private fun ShortArray.initBitModel() = apply {
    for (i in 0 until size)
        this[i] = MODEL_INIT
}


private class BitTreeDecoder(private val bitLevel: Int) {
    private val models = ShortArray(1 shl bitLevel).initBitModel()

    fun decode(rangeDecoder: RangeDecoder): Int {
        var m = 1
        for (bitIndex in 0 until bitLevel)
            m = (m shl 1) + rangeDecoder.decode(models, m)

        return m - models.size
    }

    fun reverseDecode(rangeDecoder: RangeDecoder): Int {
        var m = 1
        var symbol = 0
        for (bitIndex in 0 until bitLevel) {
            val bit = rangeDecoder.decode(models, m)
            m = (m shl 1) + bit
            symbol = symbol or (bit shl bitIndex)
        }

        return symbol
    }
}


private class LenDecoder(stateCount: Int) {
    private val choiceModel = ShortArray(2).initBitModel()
    private val highCoder = BitTreeDecoder(8)

    private val midCoders = Array(stateCount) {
        BitTreeDecoder(3)
    }

    private val lowCoders = Array(stateCount) {
        BitTreeDecoder(3)
    }

    fun decode(rangeDecoder: RangeDecoder, posState: Int): Int {
        if (rangeDecoder.decode(choiceModel, 0) == 0)
            return lowCoders[posState].decode(rangeDecoder)

        return 8 + if (rangeDecoder.decode(choiceModel, 1) == 0)
            midCoders[posState].decode(rangeDecoder)
        else
            8 + highCoder.decode(rangeDecoder)
    }
}


private class LiteralDecoder(posBitCount: Int, private val prevBitCount: Int) {
    private val posMask = (1 shl posBitCount) - 1

    private val coders = Array((1 shl prevBitCount) + posBitCount) {
        SingleDecoder()
    }


    class SingleDecoder {
        private val decoders = ShortArray(0x300).initBitModel()

        fun decodeNormal(rangeDecoder: RangeDecoder): Byte {
            var symbol = 1
            do {
                symbol = symbol shl 1 or rangeDecoder.decode(decoders, symbol)
            } while (symbol < 0x100)
            return symbol.toByte()
        }

        fun decodeWithMatchByte(rangeDecoder: RangeDecoder, matchByte: Byte): Byte {
            var match = matchByte.toIntFixed()
            var symbol = 1
            var loop = true

            do {
                val matchBit = match shr 7 and 1
                match = match shl 1
                val bit = rangeDecoder.decode(decoders, (1 + matchBit shl 8) + symbol)
                symbol = symbol shl 1 or bit
                if (matchBit != bit) {
                    while (symbol < 0x100)
                        symbol = symbol shl 1 or rangeDecoder.decode(decoders, symbol)
                    loop = false
                }
            } while (loop && symbol < 0x100)

            return symbol.toByte()
        }
    }


    fun getDecoder(pos: Int, prevByte: Byte): SingleDecoder {
        val pos1 = (pos and posMask shl prevBitCount)
        val pos2 = (prevByte.toIntFixed() and 0xFF).ushr(8 - prevBitCount)

        return coders[pos1 + pos2]
    }
}


private class RangeDecoder(private val stream: InputStream) {
    private val rangeMask = ((1 shl 24) - 1).inv()
    private var code = 0
    private var range = -1

    init {
        for (i in 0..4)
            readNextCode()
    }

    private fun readNextCode() {
        code = (code shl 8) or stream.read()
    }

    private fun checkRange() {
        if (range and rangeMask == 0) {
            readNextCode()
            range = range shl 8
        }
    }

    fun decodeDirect(totalBitCount: Int): Int {
        var result = 0
        for (i in totalBitCount downTo 1) {
            range = range ushr 1
            val t = (code - range).ushr(31)
            code -= range and t - 1
            result = (result shl 1) or (1 - t)
            checkRange()
        }
        return result
    }

    fun decode(probs: ShortArray, index: Int): Int {
        val prob = probs[index].toInt()
        val newBound = range.ushr(MODEL_BIT_COUNT) * prob
        if (code xor -0x80000000 < newBound xor -0x80000000) {
            range = newBound
            probs[index] = (prob + (MODEL_BIT_PATTERN - prob).ushr(5)).toShort()
            checkRange()
            return 0
        }

        range -= newBound
        code -= newBound
        probs[index] = (prob - prob.ushr(5)).toShort()
        checkRange()
        return 1
    }
}


private class OutWindow(private val stream: OutputStream,
                        private val size: Int)
    : Flushable, Closeable {
    private val buffer = ByteArray(size)
    private var bufferPos = 0
    private var streamPos = 0

    fun copy(distance: Int, len: Int) {
        var counter = len
        var current = bufferPos - distance - 1
        if (current < 0)
            current += size

        while (counter != 0) {
            if (current >= size)
                current = 0

            buffer[bufferPos++] = buffer[current++]
            if (bufferPos >= size)
                flush()

            counter--
        }
    }

    fun put(b: Byte) {
        buffer[bufferPos++] = b
        if (bufferPos >= size)
            flush()
    }

    operator fun get(distance: Int): Byte {
        var pos = bufferPos - distance - 1
        if (pos < 0)
            pos += size

        return buffer[pos]
    }

    override fun flush() {
        val size = bufferPos - streamPos
        if (size == 0)
            return

        stream.write(buffer, streamPos, size)
        if (bufferPos >= size)
            bufferPos = 0
        streamPos = bufferPos
    }

    override fun close() = flush()
}


private class Decoder {
    private lateinit var rangeDecoder: RangeDecoder
    private lateinit var outWindow: OutWindow

    private val matchDecoders = ShortArray(STATE_COUNT shl MAX_PB_BITS).initBitModel()
    private val repDecoders = ShortArray(STATE_COUNT).initBitModel()
    private val repG0Decoders = ShortArray(STATE_COUNT).initBitModel()
    private val repG1Decoders = ShortArray(STATE_COUNT).initBitModel()
    private val repG2Decoders = ShortArray(STATE_COUNT).initBitModel()
    private val rep0LongDecoders = ShortArray(STATE_COUNT shl MAX_PB_BITS).initBitModel()
    private val posDecoders = ShortArray(DISTANCE_COUNT - END_POS_MODEL_INDEX).initBitModel()

    private val posSlotDecoders = Array(LEN_TO_POS_STATES) {
        BitTreeDecoder(6)
    }

    private val posAlignDecoder = BitTreeDecoder(ALIGN_BITS)

    private lateinit var lenDecoder: LenDecoder
    private lateinit var repLenDecoder: LenDecoder
    private lateinit var literalDecoder: LiteralDecoder

    private var dictionarySize = 0
    private var posStateMask = 0


    private fun Int.updateStateChar() = when {
        this < 4 -> 0
        this < 10 -> this - 3
        else -> this - 6
    }

    private fun Int.isCharState() = (this < 7)
    private fun Int.updateStateMatch() = if (isCharState()) 7 else 10
    private fun Int.updateStateRep() = if (isCharState()) 8 else 11
    private fun Int.updateStateShortRep() = if (isCharState()) 9 else 11

    private fun Int.toPosState(): Int {
        val res = this - MATCH_MIN_LEN
        return when {
            res < LEN_TO_POS_STATES -> res
            else -> LEN_TO_POS_STATES - 1
        }
    }

    private fun setDictionarySize(size: Int): Boolean {
        if (size < 0)
            return false

        dictionarySize = size.coerceAtLeast(1 shl 12)
        return true
    }

    private fun setLcLpPb(lc: Int, lp: Int, pb: Int): Boolean {
        if (lc > MAX_LC_BITS || lp > MAX_LP_BITS || pb > MAX_PB_BITS)
            return false

        literalDecoder = LiteralDecoder(lp, lc)

        val numPosStates = (1 shl pb)
        lenDecoder = LenDecoder(numPosStates)
        repLenDecoder = LenDecoder(numPosStates)
        posStateMask = numPosStates - 1

        return true
    }

    private fun reverseDecode(startIndex: Int, bitLevelCount: Int): Int {
        var m = 1
        var symbol = 0
        for (bitIndex in 0 until bitLevelCount) {
            val bit = rangeDecoder.decode(posDecoders, startIndex + m)
            m = (m shl 1) + bit
            symbol = symbol or (bit shl bitIndex)
        }
        return symbol
    }

    fun decode(inStream: InputStream, outStream: OutputStream, outSize: Long): Boolean {
        rangeDecoder = RangeDecoder(inStream)
        outWindow = OutWindow(outStream, dictionarySize)

        var state = 0
        var rep0 = 0
        var rep1 = 0
        var rep2 = 0
        var rep3 = 0

        var currentPos = 0L
        var prevByte: Byte = 0
        while (outSize < 0 || currentPos < outSize) {
            val posState = currentPos.toInt() and posStateMask
            if (rangeDecoder.decode(matchDecoders, (state shl MAX_PB_BITS) + posState) == 0) {
                literalDecoder.getDecoder(currentPos.toInt(), prevByte).apply {
                    prevByte = if (state.isCharState())
                        decodeNormal(rangeDecoder)
                    else
                        decodeWithMatchByte(rangeDecoder, outWindow[rep0])
                }

                outWindow.put(prevByte)
                state = state.updateStateChar()
                currentPos++
            } else {
                var len: Int
                if (rangeDecoder.decode(repDecoders, state) == 1) {
                    len = 0
                    if (rangeDecoder.decode(repG0Decoders, state) == 0) {
                        if (rangeDecoder.decode(rep0LongDecoders, (state shl MAX_PB_BITS) + posState) == 0) {
                            state = state.updateStateShortRep()
                            len = 1
                        }
                    } else {
                        val distance: Int
                        if (rangeDecoder.decode(repG1Decoders, state) == 0)
                            distance = rep1
                        else {
                            if (rangeDecoder.decode(repG2Decoders, state) == 0)
                                distance = rep2
                            else {
                                distance = rep3
                                rep3 = rep2
                            }
                            rep2 = rep1
                        }
                        rep1 = rep0
                        rep0 = distance
                    }

                    if (len == 0) {
                        len = repLenDecoder.decode(rangeDecoder, posState) + MATCH_MIN_LEN
                        state = state.updateStateRep()
                    }
                } else {
                    rep3 = rep2
                    rep2 = rep1
                    rep1 = rep0
                    len = MATCH_MIN_LEN + lenDecoder.decode(rangeDecoder, posState)
                    state = state.updateStateMatch()
                    val posSlot = posSlotDecoders[len.toPosState()].decode(rangeDecoder)
                    if (posSlot >= START_POS_MODEL_INDEX) {
                        val numDirectBits = (posSlot shr 1) - 1
                        rep0 = 2 or (posSlot and 1) shl numDirectBits
                        if (posSlot < END_POS_MODEL_INDEX)
                            rep0 += reverseDecode(rep0 - posSlot - 1, numDirectBits)
                        else {
                            rep0 += rangeDecoder.decodeDirect(numDirectBits - ALIGN_BITS) shl ALIGN_BITS
                            rep0 += posAlignDecoder.reverseDecode(rangeDecoder)
                            if (rep0 < 0) {
                                if (rep0 == -1)
                                    break
                                return false
                            }
                        }
                    } else
                        rep0 = posSlot
                }

                if (rep0 >= currentPos || rep0 >= dictionarySize)
                    return false

                outWindow.copy(rep0, len)
                currentPos += len.toLong()
                prevByte = outWindow[0]
            }
        }

        outWindow.close()
        return true
    }

    fun setDecoderProperties(properties: ByteArray): Boolean {
        if (properties.size < SIZE_PROPERTIES)
            return false

        val propValue = properties[0].toIntFixed() and 0xFF
        val lc = propValue % 9
        val remainder = propValue / 9
        val lp = remainder % 5
        val pb = remainder / 5
        val dictionarySize = (0..3).sumBy {
            (properties[1 + it].toIntFixed() and 0xFF) shl (it * 8)
        }

        return (setLcLpPb(lc, lp, pb) &&
                setDictionarySize(dictionarySize))
    }
}

/**
 * Unpacks LZMA-compressed stream to [output] stream.
 * @throws IOException if unpack fails on any reason.
 */
@Throws(IOException::class)
fun InputStream.lzmaUnpack(output: OutputStream) {
    val properties = ByteArray(SIZE_PROPERTIES)
    if (read(properties) != properties.size)
        throw IOException("Input stream is too short")

    val decoder = Decoder()
    if (!decoder.setDecoderProperties(properties))
        throw IOException("Incorrect stream properties")

    val sizeBuffer = ByteArray(8)
    if (read(sizeBuffer) != sizeBuffer.size)
        throw IOException("Failed to read output size")

    var outSize = 0L
    for (i in 0..7)
        outSize = (outSize shl 8) or (sizeBuffer[7 - i].toLong() and 0x000000FF)

    if (!decoder.decode(this, output, outSize))
        throw IOException("Error in data stream")
}

/**
 * Unpacks LZMA-compressed file to [outFile].
 * @throws IOException if unpack fails on any reason.
 */
@Throws(IOException::class)
fun File.lzmaUnpack(outFile: File) = inputStream().use { ins ->
    BufferedOutputStream(outFile.outputStream()).use(ins::lzmaUnpack)
}
