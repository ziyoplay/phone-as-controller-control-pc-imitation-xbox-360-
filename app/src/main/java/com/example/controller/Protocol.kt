package com.example.controller

// Wire protocol: 8 bytes, big-endian, ">HBBBBBB"
//   [buttons: uint16][LX][LY][RX][RY][LT][RT]
// Stick and trigger bytes are 0..255 with 128 as the neutral centre.
//
// Bits must match MAP in server.py exactly — only these ten are wired up there.
const val BTN_A = 0x0001
const val BTN_B = 0x0002
const val BTN_X = 0x0008
const val BTN_Y = 0x0010
const val BTN_BACK = 0x0040
const val BTN_START = 0x0080
const val BTN_LB = 0x0100
const val BTN_RB = 0x0200
const val BTN_DPAD_UP = 0x1000
const val BTN_DPAD_DOWN = 0x2000
const val BTN_DPAD_LEFT = 0x4000
const val BTN_DPAD_RIGHT = 0x8000

const val UDP_PORT = 5005

/** Functions offered for the extra buttons added in the layout editor. */
val AssignableButtons: List<Pair<String, Int>> = listOf(
    "A" to BTN_A,
    "B" to BTN_B,
    "X" to BTN_X,
    "Y" to BTN_Y,
    "LB" to BTN_LB,
    "RB" to BTN_RB,
    "BACK" to BTN_BACK,
    "START" to BTN_START,
)
