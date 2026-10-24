package com.linkplay.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * A tiny GBA program made here, byte by byte, so the self-test (and anyone without a game) has
 * something to run. It contains no one else's code or graphics.
 *
 * What it does: video mode 3 (a 240x160 bitmap), then once per frame, at the start of vertical blank:
 *  - reads the buttons: A turns the picture green, B turns it blue (B wins when both are down), and the
 *    colour stays until the other button is pressed. The colour lives in register r3, so a saved
 *    state brings it back: that is what the self-test uses to prove save and load work;
 *  - fills the whole screen with that colour with one DMA transfer.
 * Before any button the screen is a pinkish purple ([IDLE_RGB]).
 *
 * It is not a bootable cartridge for real hardware (the logo that the GBA's own BIOS checks is left
 * out); mGBA without a BIOS only needs a branch instruction first and 0x96 at 0xB2, which the app's
 * ROM check wants as well.
 *
 * Layout: 0x00 branch to 0xC0, 0x04..0x9F logo area (zero), 0xA0 title, 0xAC game code, 0xB2 fixed 0x96,
 * 0xBD header check, 0xC0 the program, padded with zeros to 512 bytes. ARM instructions are 32-bit
 * little-endian words. Every word below was worked out by hand from the ARM instruction formats and
 * then checked against an assembler (LLVM, armv4t) and by running it on the real core.
 */
object TestRom {
    const val TITLE = "LINKPLAYTEST" // 12 bytes at 0xA0
    const val CODE = "ZLPT" // 4 bytes at 0xAC
    const val SIZE = 512

    /** Offset of the program; the branch at 0 jumps here. */
    private const val ENTRY = 0xC0

    // The colours the program uses, as the GBA stores them (bit 0-4 red, 5-9 green, 10-14 blue) and
    // as copyFrameRgb returns them. mGBA widens each 5-bit channel c to 8 bits as (c shl 3) or (c shr 2).
    const val IDLE_BGR555 = 0x515F // red 31, green 10, blue 20
    const val IDLE_RGB = 0xFFFF52A5.toInt() // red 0xFF, green 0x52, blue 0xA5
    const val GREEN_BGR555 = 0x03E0 // green 31
    const val GREEN_RGB = 0xFF00FF00.toInt()
    const val BLUE_BGR555 = 0x7C00 // blue 31
    const val BLUE_RGB = 0xFF0000FF.toInt()

    private class Insn(val word: Long, val text: String)

    /**
     * The program. ARM data-processing words: cond(4) 00 I opcode(4) S Rn(4) Rd(4) rotate(4) imm8, an
     * immediate being imm8 rotated right by 2 * rotate. Always-condition is 0xE, equal is 0x0.
     * Halfword transfers: cond 000 P U 1 W L Rn Rd offsetHigh 1 S H 1 offsetLow. Word transfers: cond 01 I P U B W L Rn Rd imm12.
     */
    private val program = listOf(
        // r0 = 0x04000000, the I/O registers
        // E3A00301: cond E, MOV (1101), I=1, Rd=r0, rotate 3 (ror 6), imm 0x01 -> 1 ror 6 = 0x04000000
        Insn(0xE3A00301, "mov   r0, #0x04000000        @ I/O register base"),
        // E3A01B01: MOV, Rd=r1, rotate 11 (ror 22), imm 0x01 -> 0x0400 = BG2 enable
        Insn(0xE3A01B01, "mov   r1, #0x0400            @ DISPCNT: BG2 on"),
        // E3811003: ORR (1100), Rn=r1, Rd=r1, rotate 0, imm 3 -> video mode 3
        Insn(0xE3811003, "orr   r1, r1, #3             @ ... in video mode 3"),
        // E1C010B0: STRH, P=1 U=1 imm offset, Rn=r0, Rd=r1, offset 0 -> store at 0x04000000 (DISPCNT)
        Insn(0xE1C010B0, "strh  r1, [r0]               @ DISPCNT = 0x0403"),
        // E2806E13: ADD (0100), Rn=r0, Rd=r6, rotate 14 (ror 28 = shl 4), imm 0x13 -> 0x130
        Insn(0xE2806E13, "add   r6, r0, #0x130         @ r6 = 0x04000130, KEYINPUT (0 = pressed)"),
        // E3A05403: MOV, Rd=r5, rotate 4 (ror 8), imm 0x03 -> 0x03000000 (fast work RAM)
        Insn(0xE3A05403, "mov   r5, #0x03000000        @ r5: where the colour sits for the DMA to read"),
        // E3A04406: MOV, Rd=r4, rotate 4, imm 0x06 -> 0x06000000
        Insn(0xE3A04406, "mov   r4, #0x06000000        @ r4 = VRAM, the picture"),
        // E3A07481: MOV, Rd=r7, rotate 4, imm 0x81 -> 0x81000000 = DMA control: enable, source fixed, 16 bit, now
        Insn(0xE3A07481, "mov   r7, #0x81000000        @ DMA3 control (top half): on, fixed source"),
        // E3877C96: ORR, Rn=r7, Rd=r7, rotate 12 (ror 24 = shl 8), imm 0x96 -> 0x9600 = 38400 transfers (240*160)
        Insn(0xE3877C96, "orr   r7, r7, #0x9600        @ DMA3 count (bottom half): 38400 pixels"),
        // E3A03C51: MOV, Rd=r3, rotate 12, imm 0x51 -> 0x5100
        Insn(0xE3A03C51, "mov   r3, #0x5100            @ r3 = the colour, first half"),
        // E383305F: ORR, Rn=r3, Rd=r3, imm 0x5F -> 0x515F
        Insn(0xE383305F, "orr   r3, r3, #0x5F          @ ... 0x515F, the idle colour"),
        // wait_vb (at ENTRY + 0x2C):
        // E1D020B6: LDRH (L=1), Rn=r0, Rd=r2, offsetHigh 0, offsetLow 6 -> VCOUNT at 0x04000006
        Insn(0xE1D020B6, "wait_vb: ldrh r2, [r0, #6]   @ VCOUNT: the line being drawn"),
        // E35200A0: CMP (1010), S=1, Rn=r2, imm 0xA0 = 160, the first line of vertical blank
        Insn(0xE35200A0, "cmp   r2, #160"),
        // 1AFFFFFC: cond NE (1), 101, L=0, offset -4 words: PC (this + 8) - 16 = wait_vb
        Insn(0x1AFFFFFC, "bne   wait_vb                @ wait for line 160"),
        // E1D620B0: LDRH, Rn=r6, Rd=r2, offset 0 -> KEYINPUT
        Insn(0xE1D620B0, "ldrh  r2, [r6]               @ buttons, 1 = up, 0 = down"),
        // E3120001: TST (1000), S=1, Rn=r2, imm 1 -> Z set when button A (bit 0) is down
        Insn(0xE3120001, "tst   r2, #1"),
        // 03A03E3E: cond EQ (0), MOV, Rd=r3, rotate 14 (shl 4), imm 0x3E -> 0x3E0 = green
        Insn(0x03A03E3E, "moveq r3, #0x3E0             @ A down: green"),
        // E3120002: TST r2, #2 -> Z set when button B (bit 1) is down
        Insn(0xE3120002, "tst   r2, #2"),
        // 03A03B1F: cond EQ, MOV, Rd=r3, rotate 11 (shl 10), imm 0x1F -> 0x7C00 = blue
        Insn(0x03A03B1F, "moveq r3, #0x7C00            @ B down: blue"),
        // E1C530B0: STRH, Rn=r5, Rd=r3, offset 0 -> the colour into work RAM
        Insn(0xE1C530B0, "strh  r3, [r5]"),
        // E58050D4: STR, P=1 U=1 imm, Rn=r0, Rd=r5, offset 0xD4 -> DMA3SAD (source address)
        Insn(0xE58050D4, "str   r5, [r0, #0xD4]        @ DMA3SAD = the colour"),
        // E58040D8: STR, Rn=r0, Rd=r4, offset 0xD8 -> DMA3DAD (destination address)
        Insn(0xE58040D8, "str   r4, [r0, #0xD8]        @ DMA3DAD = VRAM"),
        // E58070DC: STR, Rn=r0, Rd=r7, offset 0xDC -> DMA3CNT (count and control): starts now, the CPU waits
        Insn(0xE58070DC, "str   r7, [r0, #0xDC]        @ DMA3CNT: fill the screen"),
        // wait_end (at ENTRY + 0x5C):
        Insn(0xE1D020B6, "wait_end: ldrh r2, [r0, #6]  @ still in line 160? wait it out"),
        Insn(0xE35200A0, "cmp   r2, #160"),
        // 0AFFFFFC: cond EQ, B, offset -4 words -> wait_end
        Insn(0x0AFFFFFC, "beq   wait_end"),
        // EAFFFFEF: cond AL, B, offset -17 words: PC (this + 8) - 68 = wait_vb
        Insn(0xEAFFFFEF, "b     wait_vb"),
    )

    /** The program as assembler text with each instruction's address and word, for reading and for checking. */
    fun listing(): String = buildString {
        program.forEachIndexed { i, insn ->
            append("%04X: %08X  %s\n".format(ENTRY + i * 4, insn.word, insn.text))
        }
    }

    /** The whole ROM image. */
    fun bytes(): ByteArray {
        val rom = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // 0x00: B ENTRY. cond AL, 101, offset = (ENTRY - 8) / 4 words (PC reads 8 bytes ahead) -> EA00002E.
        // The top byte 0xEA is also what mGBA looks at to recognise a GBA ROM.
        rom.putInt(0, (0xEA000000L or ((ENTRY - 8) / 4).toLong()).toInt())
        rom.position(0xA0)
        rom.put(TITLE.toByteArray(Charsets.ISO_8859_1))
        rom.position(0xAC)
        rom.put(CODE.toByteArray(Charsets.ISO_8859_1))
        rom.position(0xB0)
        rom.put("00".toByteArray(Charsets.ISO_8859_1)) // maker code
        rom.put(0xB2, 0x96.toByte()) // fixed value; the app and mGBA both check it
        // 0xBD: complement check over 0xA0..0xBC, as the BIOS computes it: -(sum) - 0x19.
        var check = 0
        for (i in 0xA0..0xBC) check -= rom.get(i).toInt() and 0xFF
        rom.put(0xBD, (check - 0x19).toByte())
        program.forEachIndexed { i, insn -> rom.putInt(ENTRY + i * 4, insn.word.toInt()) }
        return rom.array()
    }

    /** Writes the ROM to [file] (parent folders are made) and returns it. */
    fun write(file: File): File {
        file.absoluteFile.parentFile?.mkdirs()
        Files.write(file.toPath(), bytes())
        return file
    }
}
