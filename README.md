# Phone as Controller — Xbox 360 Emulation

Turn an Android phone into a virtual Xbox 360 gamepad for your PC. The phone sends
button and stick state over UDP; a small Python server on the PC feeds that state into
the ViGEmBus driver, which exposes a real Xbox 360 controller to Windows. Games see an
ordinary gamepad — no per-game configuration needed.

## Components

| Part | Location | Role |
| --- | --- | --- |
| Android client | [app/](app/) | Jetpack Compose gamepad UI, sends UDP packets |
| PC server | [server.py](server.py) | Receives UDP packets, drives a virtual Xbox 360 controller |

## Features

- **Customizable layout** — drag widgets around, resize them, add or delete buttons in Edit mode
- **Persistent settings** — layout and last-used IP are saved with DataStore
- Two analog sticks, D-Pad, action buttons, L1/R1 and digital L2/R2 triggers
- **Motion mode** — tilt the phone to drive the left stick (accelerometer)
- State is sent every 15 ms (~66 Hz)

## Requirements

- **PC:** Windows, Python 3.8+, [ViGEmBus driver](https://github.com/ViGEm/ViGEmBus/releases)
- **Phone:** Android 8.0+ (minSdk 26), accelerometer
- Both devices on the same network (same Wi-Fi, or a VPN such as Radmin/Hamachi)
- To build from source: Android Studio, JDK 17, compileSdk 35

## Setup

### 1. Start the server on the PC

Install the ViGEmBus driver first, then:

```bash
pip install vgamepad
python server.py
```

The server prints your PC's IP address and starts listening on UDP port `5005`:

```
========================================
   GAMEPAD SERVER: PRO EDITION
========================================
[OK] Virtual Xbox 360 kontrolleri yaratildi.
[OK] Server ishga tushdi.
[!] Kompyuter IP manzili: 192.168.1.42     <-- you need this
[!] Port: 5005
```

If Windows Firewall asks for permission, allow Python on private networks — otherwise the
packets never reach the server.

### 2. Install the app on the phone

Build and install over ADB:

```bash
./gradlew installDebug
```

Or open the project in Android Studio and hit Run. A prebuilt debug APK ends up at
`app/build/outputs/apk/debug/app-debug.apk` (not committed to this repo).

### 3. Connect the client

1. Open the app — it starts on the **Gamepad Pro Builder** screen.
2. Type the IP address the server printed into the **PC IP** field (port is not needed —
   it is always 5005). The last IP you used is restored automatically next time.
3. Tap **CONNECT**. The gamepad screen appears and the phone immediately starts streaming
   input.
4. The server console confirms it with `[OK] Telefon ulandi: <phone-ip>`.

The transport is UDP, so there is no handshake — the app only validates that the address
is resolvable. If you enter a *valid but wrong* IP, the app will still show the gamepad
screen while nothing reaches the PC. Always check for the `Telefon ulandi` line in the
server console.

### 4. Customize the layout (optional)

On the gamepad screen:

- ⚙️ **Settings icon** — enter Edit mode. Drag widgets to move them, tap one to select it,
  then use the slider to resize, the dropdown to reassign its function, or 🗑 to delete it.
  ➕ adds a new button. **SAVE** stores the layout and exits Edit mode.
- 📍 **Location icon** — toggle motion mode (green = on). The left stick is then driven by
  tilting the phone.

## Protocol

Each packet is 8 bytes, big-endian, struct format `>HBBBBBB`:

```
[buttons: uint16][LX][LY][RX][RY][L2][R2]
```

Stick and trigger bytes are `0..255` with `128` as the neutral center. Button bits follow
the Android/XInput-style mask defined in [server.py](server.py#L45-L58):

| Bit | Button | Bit | Button |
| --- | --- | --- | --- |
| `0x0001` | A (✕) | `0x0100` | L1 |
| `0x0002` | B (◯) | `0x0200` | R1 |
| `0x0008` | X (□) | `0x1000` | D-Pad Up |
| `0x0010` | Y (△) | `0x2000` | D-Pad Down |
| `0x0040` | Back / Select | `0x4000` | D-Pad Left |
| `0x0080` | Start | `0x8000` | D-Pad Right |

Port: `5005/udp`.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `[XATO] ViGEmBus drayveri topilmadi` | The ViGEmBus driver is not installed — install it and reboot |
| Toast "IP xato!" on the phone | The address could not be resolved — check for typos |
| Gamepad screen opens but nothing happens on the PC | Wrong IP, blocked by the firewall, or the devices are on different networks |
| Server never prints `Telefon ulandi` | No packets arriving — same causes as above |
| Game does not see the controller | Start the server *before* launching the game |
