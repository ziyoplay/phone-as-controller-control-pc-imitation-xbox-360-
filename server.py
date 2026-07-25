import socket
import vgamepad as vg
import struct
import os

# UDP Server sozlamalari
UDP_IP = "0.0.0.0"
UDP_PORT = 5005

def main():
    os.system('cls' if os.name == 'nt' else 'clear')
    print("========================================")
    print("   GAMEPAD SERVER: PRO EDITION")
    print("========================================")

    # 1. ViGEmBus drayverini va Gamepadni tekshirish
    try:
        gamepad = vg.VX360Gamepad()
        print("[OK] Virtual Xbox 360 kontrolleri yaratildi.")
    except Exception as e:
        print(f"[XATO] ViGEmBus drayveri topilmadi: {e}")
        print("\nIltimos, ViGEmBus'ni o'rnating: https://github.com/ViGEm/ViGEmBus/releases")
        input("\nChiqish uchun Enter bosing...")
        return

    # 2. Tarmoq socketini yaratish
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind((UDP_IP, UDP_PORT))

        # Kompyuter IP manzilini chiroyli chiqarish
        hostname = socket.gethostname()
        local_ip = socket.gethostbyname(hostname)
        print(f"[OK] Server ishga tushdi.")
        print(f"[!] Kompyuter IP manzili: {local_ip}")
        print(f"[!] Port: {UDP_PORT}")
    except Exception as e:
        print(f"[XATO] Socket yaratishda xato: {e}")
        return

    print("----------------------------------------")
    print("Telefonda 'ULANISH' tugmasini bosing...")

    # Tugmalar mapping (Android -> Xbox)
    MAP = {
        0x0001: vg.XUSB_BUTTON.XUSB_GAMEPAD_A,              # Cross
        0x0002: vg.XUSB_BUTTON.XUSB_GAMEPAD_B,              # Circle
        0x0008: vg.XUSB_BUTTON.XUSB_GAMEPAD_X,              # Square
        0x0010: vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,              # Triangle
        0x0040: vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,           # Select
        0x0080: vg.XUSB_BUTTON.XUSB_GAMEPAD_START,          # Start
        0x0100: vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,  # L1
        0x0200: vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER, # R1
        0x1000: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,        # D-Pad Up
        0x2000: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,      # D-Pad Down
        0x4000: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,      # D-Pad Left
        0x8000: vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,     # D-Pad Right
    }

    connected = False

    while True:
        try:
            # 8 baytlik paketni kutish
            data, addr = sock.recvfrom(1024)

            if not connected:
                print(f"[OK] Telefon ulandi: {addr[0]}")
                connected = True

            if len(data) == 8:
                # Unpack: H (Short/2 byte), B (Byte/1 byte) x 6
                # Format: [Buttons(2)][LX][LY][RX][RY][L2][R2]
                btns, lx, ly, rx, ry, l2, r2 = struct.unpack(">HBBBBBB", data)

                # 1. Joystiklarni o'tkazish (0-255 -> -32768 to 32767)
                def scale_joy(val):
                    return int((val - 128) * 256)

                gamepad.left_joystick(x_value=scale_joy(lx), y_value=-scale_joy(ly))
                gamepad.right_joystick(x_value=scale_joy(rx), y_value=-scale_joy(ry))

                # 2. Tugmalarni bosish
                for bit, xbtn in MAP.items():
                    if btns & bit:
                        gamepad.press_button(button=xbtn)
                    else:
                        gamepad.release_button(button=xbtn)

                # 3. Triggerlarni o'tkazish (L2/R2)
                gamepad.left_trigger(value=l2)
                gamepad.right_trigger(value=r2)

                # 4. Holatni yangilash
                gamepad.update()

        except KeyboardInterrupt:
            print("\nServer to'xtatildi.")
            break
        except Exception as e:
            print(f"Xato: {e}")

if __name__ == "__main__":
    main()
