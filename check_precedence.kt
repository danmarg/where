fun main() {
    val b0: Long = 0x01
    val res = b0 and 0xFF shl 8
    println("b0 and 0xFF shl 8 = $res")
    println("Expected 0x0100 (256)")

    val b1: Long = 0x80
    val res2 = b1 and 0xFF shl 8
    println("0x80 and 0xFF shl 8 = $res2")
    println("Expected 0x8000 (32768)")
}
