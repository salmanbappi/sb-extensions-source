package eu.kanade.tachiyomi.animeextension.en.reanime

class MiniWasmInterpreter(private val wasmBytes: ByteArray) {
    private val memory = ByteArray(65536)
    private val globals = IntArray(16)

    fun readVarUint(bytes: ByteArray, offset: IntArray): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = bytes[offset[0]++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if ((byte and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    fun readVarSint(bytes: ByteArray, offset: IntArray): Int {
        var result = 0
        var shift = 0
        var byte = 0
        while (true) {
            byte = bytes[offset[0]++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            shift += 7
            if ((byte and 0x80) == 0) break
        }
        if (shift < 32 && (byte and 0x40) != 0) {
            result = result or (-1 shl shift)
        }
        return result
    }

    fun parseWasm(): List<ByteArray> {
        var offset = 8
        val funcs = mutableListOf<ByteArray>()
        while (offset < wasmBytes.size) {
            val type = wasmBytes[offset++].toInt() and 0xff
            val offsetRef = intArrayOf(offset)
            val size = readVarUint(wasmBytes, offsetRef)
            offset = offsetRef[0]
            val end = offset + size
            if (type == 10) { // Code section
                val funcCount = readVarUint(wasmBytes, offsetRef)
                offset = offsetRef[0]
                for (f in 0 until funcCount) {
                    val bodySize = readVarUint(wasmBytes, offsetRef)
                    val bodyStart = offsetRef[0]
                    val body = wasmBytes.copyOfRange(bodyStart, bodyStart + bodySize)
                    funcs.add(body)
                    offsetRef[0] = bodyStart + bodySize
                }
            }
            offset = end
        }
        return funcs
    }

    fun executeWasm(funcs: List<ByteArray>, frag1: ByteArray, frag2: ByteArray, keyPart: ByteArray, seedInt: Int): ByteArray {
        val k = frag1.size
        val p = 1000
        val v = p + k
        val tOffset = v + k
        val i = tOffset + k

        System.arraycopy(frag1, 0, memory, p, k)
        System.arraycopy(frag2, 0, memory, v, k)
        System.arraycopy(keyPart, 0, memory, tOffset, k)

        globals[0] = seedInt

        runFunc(funcs[0], intArrayOf(seedInt))
        runFunc(funcs[1], intArrayOf(p, v, tOffset, i, k))

        return memory.copyOfRange(i, i + k)
    }

    private class Block(val op: Int, val pc: Int, val end: Int)

    private fun runFunc(body: ByteArray, args: IntArray) {
        val offsetRef = intArrayOf(0)
        val localDeclCount = readVarUint(body, offsetRef)
        val locals = mutableListOf<Int>()
        for (arg in args) {
            locals.add(arg)
        }
        for (d in 0 until localDeclCount) {
            val count = readVarUint(body, offsetRef)
            val type = body[offsetRef[0]++].toInt() and 0xff // 0x7f
            for (c in 0 until count) {
                locals.add(0)
            }
        }

        val code = body.copyOfRange(offsetRef[0], body.size)
        val stack = mutableListOf<Int>()
        var pc = 0

        // Build jump table
        val jumps = mutableMapOf<Int, Int>()
        val blockStack = mutableListOf<Pair<Int, Int>>() // Pair(op, pc)

        var tpc = 0
        while (tpc < code.size) {
            val op = code[tpc].toInt() and 0xff
            if (op == 0x02 || op == 0x03) { // block or loop
                blockStack.add(Pair(op, tpc))
                tpc += 2
            } else if (op == 0x0b) { // end
                if (blockStack.isNotEmpty()) {
                    val entry = blockStack.removeAt(blockStack.size - 1)
                    jumps[entry.second] = tpc
                    jumps[tpc] = entry.second
                }
                tpc++
            } else if (op == 0x0c || op == 0x0d) { // br or br_if
                tpc++
                val ref = intArrayOf(tpc)
                readVarUint(code, ref)
                tpc = ref[0]
            } else if (op == 0x20 || op == 0x21 || op == 0x23 || op == 0x24) {
                tpc++
                val ref = intArrayOf(tpc)
                readVarUint(code, ref)
                tpc = ref[0]
            } else if (op == 0x41) {
                tpc++
                val ref = intArrayOf(tpc)
                readVarSint(code, ref)
                tpc = ref[0]
            } else if (op == 0x2d || op == 0x3a) {
                tpc += 3
            } else {
                tpc++
            }
        }

        val activeBlocks = mutableListOf<Block>()
        while (pc < code.size) {
            val op = code[pc].toInt() and 0xff
            if (op == 0x02) { // block
                activeBlocks.add(Block(op, pc, jumps[pc] ?: 0))
                pc += 2
            } else if (op == 0x03) { // loop
                activeBlocks.add(Block(op, pc, jumps[pc] ?: 0))
                pc += 2
            } else if (op == 0x0b) { // end
                if (activeBlocks.isNotEmpty()) {
                    activeBlocks.removeAt(activeBlocks.size - 1)
                }
                pc++
            } else if (op == 0x0c) { // br
                pc++
                val ref = intArrayOf(pc)
                val depth = readVarUint(code, ref)
                pc = ref[0]
                val target = activeBlocks[activeBlocks.size - 1 - depth]
                if (target.op == 0x02) { // block
                    pc = target.end + 1
                    for (x in 0 until depth + 1) activeBlocks.removeAt(activeBlocks.size - 1)
                } else { // loop
                    pc = target.pc + 2
                    for (x in 0 until depth) activeBlocks.removeAt(activeBlocks.size - 1)
                }
            } else if (op == 0x0d) { // br_if
                pc++
                val ref = intArrayOf(pc)
                val depth = readVarUint(code, ref)
                pc = ref[0]
                val cond = stack.removeAt(stack.size - 1)
                if (cond != 0) {
                    val target = activeBlocks[activeBlocks.size - 1 - depth]
                    if (target.op == 0x02) { // block
                        pc = target.end + 1
                        for (x in 0 until depth + 1) activeBlocks.removeAt(activeBlocks.size - 1)
                    } else { // loop
                        pc = target.pc + 2
                        for (x in 0 until depth) activeBlocks.removeAt(activeBlocks.size - 1)
                    }
                }
            } else if (op == 0x20) { // local.get
                pc++
                val ref = intArrayOf(pc)
                val idx = readVarUint(code, ref)
                pc = ref[0]
                stack.add(locals[idx])
            } else if (op == 0x21) { // local.set
                pc++
                val ref = intArrayOf(pc)
                val idx = readVarUint(code, ref)
                pc = ref[0]
                locals[idx] = stack.removeAt(stack.size - 1)
            } else if (op == 0x23) { // global.get
                pc++
                val ref = intArrayOf(pc)
                val idx = readVarUint(code, ref)
                pc = ref[0]
                stack.add(globals[idx])
            } else if (op == 0x24) { // global.set
                pc++
                val ref = intArrayOf(pc)
                val idx = readVarUint(code, ref)
                pc = ref[0]
                globals[idx] = stack.removeAt(stack.size - 1)
            } else if (op == 0x41) { // i32.const
                pc++
                val ref = intArrayOf(pc)
                val valConst = readVarSint(code, ref)
                pc = ref[0]
                stack.add(valConst)
            } else if (op == 0x2d) { // i32.load8_u
                pc += 3
                val addr = stack.removeAt(stack.size - 1)
                stack.add(memory[addr].toInt() and 0xff)
            } else if (op == 0x3a) { // i32.store8
                pc += 3
                val valStore = stack.removeAt(stack.size - 1)
                val addr = stack.removeAt(stack.size - 1)
                memory[addr] = (valStore and 0xff).toByte()
            } else if (op == 0x6a) { // i32.add
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a + b)
            } else if (op == 0x6b) { // i32.sub
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a - b)
            } else if (op == 0x6c) { // i32.mul
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a * b)
            } else if (op == 0x73) { // i32.xor
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a xor b)
            } else if (op == 0x74) { // i32.shl
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a shl (b and 31))
            } else if (op == 0x76) { // i32.shr_u
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a ushr (b and 31))
            } else if (op == 0x72) { // i32.or
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a or b)
            } else if (op == 0x71) { // i32.and
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                stack.add(a and b)
            } else if (op == 0x4f) { // i32.ge_u
                pc++
                val b = stack.removeAt(stack.size - 1)
                val a = stack.removeAt(stack.size - 1)
                val aLong = a.toLong() and 0xffffffffL
                val bLong = b.toLong() and 0xffffffffL
                stack.add(if (aLong >= bLong) 1 else 0)
            } else {
                throw Exception("Unsupported opcode: " + op.toString(16))
            }
        }
    }
}
